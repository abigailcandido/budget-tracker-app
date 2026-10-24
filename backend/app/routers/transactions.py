"""Income and expense entries, and the wallet balances they move.

Every change to a transaction changes its wallet's balance in the same
database transaction, so the cached balance and the transaction log can never
disagree: either both are saved or neither is.
"""

from decimal import Decimal

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import case
from sqlalchemy.orm import Session, aliased

from app.deps import get_current_user, get_db
from app.models import (
    TRANSACTION_EXPENSE,
    TRANSACTION_OPENING,
    Category,
    TrackerCategory,
    Transaction,
    User,
    Wallet,
)
from app.ownership import (
    get_owned_category,
    get_owned_tracker,
    get_owned_transaction,
    get_owned_wallet,
)
from app.schemas import (
    TransactionCreateRequest,
    TransactionResponse,
    TransactionUpdateRequest,
    format_money,
)

router = APIRouter(tags=["transactions"])

ParentCategory = aliased(Category)


# --------------------------------------------------------------------------
# Shared helpers
# --------------------------------------------------------------------------


def _effect(kind: str, amount: Decimal) -> Decimal:
    """How much a transaction adds to its wallet's balance."""
    return -amount if kind == TRANSACTION_EXPENSE else amount


def _lock_wallets(wallet_ids: set[int], db: Session) -> dict[int, Wallet]:
    """Lock the wallets whose balances are about to change.

    Two requests arriving at the same moment would otherwise both read the
    same starting balance, and one of the two changes would be lost. Locking
    in id order means two requests touching the same pair of wallets can never
    end up waiting on each other forever.
    """
    wallets = (
        db.query(Wallet)
        .filter(Wallet.id.in_(wallet_ids))
        .order_by(Wallet.id)
        .with_for_update()
        .populate_existing()
        .all()
    )
    return {wallet.id: wallet for wallet in wallets}


def _require_main_tracker_wallet(wallet: Wallet) -> None:
    if wallet.main_tracker_id is None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Savings trackers cannot record transactions yet",
        )


def _check_category(
    category_id: int,
    kind: str,
    wallet: Wallet,
    user: User,
    db: Session,
    must_be_switched_on: bool = True,
) -> Category:
    """Confirm a category can hold this transaction in this wallet's tracker.

    must_be_switched_on is relaxed when an existing transaction keeps the
    category it already had. Switching a category off hides it from new
    entries; it should not stop someone correcting the amount on last month's
    groceries.
    """
    category = get_owned_category(category_id, user, db)

    if category.kind != kind:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"An {kind} needs an {kind} category",
        )

    if (
        category.main_tracker_id is not None
        and category.main_tracker_id != wallet.main_tracker_id
    ):
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This category is private to a different tracker",
        )

    # Transactions attach to leaves only, so a parent's total is always exactly
    # the sum of its subcategories.
    has_children = (
        db.query(Category.id).filter(Category.parent_id == category.id).first()
        is not None
    )
    if has_children:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Choose one of this category's subcategories",
        )

    if must_be_switched_on:
        switched_on = (
            db.query(TrackerCategory.id)
            .filter(
                TrackerCategory.category_id == category.id,
                TrackerCategory.main_tracker_id == wallet.main_tracker_id,
            )
            .first()
            is not None
        )
        if not switched_on:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="This category is switched off for this tracker",
            )

    return category


def _described(db: Session):
    """A query returning each transaction with its wallet and category names."""
    return (
        db.query(Transaction, Wallet, Category, ParentCategory)
        .join(Wallet, Transaction.wallet_id == Wallet.id)
        .outerjoin(Category, Transaction.category_id == Category.id)
        .outerjoin(ParentCategory, Category.parent_id == ParentCategory.id)
    )


def _to_response(
    transaction: Transaction,
    wallet: Wallet,
    category: Category | None,
    parent: Category | None,
) -> TransactionResponse:
    return TransactionResponse(
        id=transaction.id,
        wallet_id=wallet.id,
        wallet_name=wallet.name,
        currency=wallet.currency,
        type=transaction.type,
        amount=format_money(transaction.amount),
        category_id=category.id if category is not None else None,
        category_name=category.name if category is not None else None,
        parent_category_name=parent.name if parent is not None else None,
        description=transaction.description,
        transaction_date=transaction.transaction_date,
        created_at=transaction.created_at,
    )


def _load_response(transaction_id: int, db: Session) -> TransactionResponse:
    row = _described(db).filter(Transaction.id == transaction_id).one()
    return _to_response(*row)


# --------------------------------------------------------------------------
# Endpoints
# --------------------------------------------------------------------------


@router.post(
    "/transactions",
    response_model=TransactionResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Record an income or an expense",
)
def create_transaction(
    payload: TransactionCreateRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> TransactionResponse:
    wallet = get_owned_wallet(payload.wallet_id, user, db)
    _require_main_tracker_wallet(wallet)
    category = _check_category(payload.category_id, payload.type, wallet, user, db)

    wallets = _lock_wallets({wallet.id}, db)

    transaction = Transaction(
        user_id=user.id,
        wallet_id=wallet.id,
        category_id=category.id,
        amount=payload.amount,
        type=payload.type,
        description=payload.description,
        transaction_date=payload.transaction_date,
    )
    db.add(transaction)

    # A wallet is allowed to go below zero. Credit cards and late-recorded
    # income both produce real negative balances, and refusing them would only
    # push people into entering numbers that are not true.
    wallets[wallet.id].balance += _effect(payload.type, payload.amount)

    db.commit()

    return _load_response(transaction.id, db)


@router.get(
    "/trackers/{tracker_id}/transactions",
    response_model=list[TransactionResponse],
    summary="List a tracker's transactions, newest first",
)
def list_transactions(
    tracker_id: int,
    wallet_id: int | None = None,
    limit: int = Query(default=100, ge=1, le=500),
    offset: int = Query(default=0, ge=0),
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> list[TransactionResponse]:
    tracker = get_owned_tracker(tracker_id, user, db)

    query = _described(db).filter(Wallet.main_tracker_id == tracker.id)

    if wallet_id is not None:
        wallet = get_owned_wallet(wallet_id, user, db)
        if wallet.main_tracker_id != tracker.id:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Wallet not found",
            )
        query = query.filter(Transaction.wallet_id == wallet.id)

    rows = (
        query.order_by(
            Transaction.transaction_date.desc(),
            # On the day a wallet was opened, its opening balance came first,
            # so in a newest-first list it sits below everything else that day.
            case((Transaction.type == TRANSACTION_OPENING, 1), else_=0),
            Transaction.created_at.desc(),
            Transaction.id.desc(),
        )
        .offset(offset)
        .limit(limit)
        .all()
    )

    return [_to_response(*row) for row in rows]


@router.get(
    "/transactions/{transaction_id}",
    response_model=TransactionResponse,
    summary="Get one transaction",
)
def get_transaction(
    transaction_id: int,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> TransactionResponse:
    transaction = get_owned_transaction(transaction_id, user, db)
    return _load_response(transaction.id, db)


@router.patch(
    "/transactions/{transaction_id}",
    response_model=TransactionResponse,
    summary="Correct a transaction",
)
def update_transaction(
    transaction_id: int,
    payload: TransactionUpdateRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> TransactionResponse:
    transaction = get_owned_transaction(transaction_id, user, db, for_update=True)
    is_opening = transaction.type == TRANSACTION_OPENING

    old_wallet = get_owned_wallet(transaction.wallet_id, user, db)
    new_wallet = old_wallet

    if payload.wallet_id is not None and payload.wallet_id != old_wallet.id:
        if is_opening:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="An opening balance stays with the wallet it opened",
            )

        new_wallet = get_owned_wallet(payload.wallet_id, user, db)

        # Moving between trackers would change which categories apply, and
        # moving between currencies would silently change what the amount
        # means. Both are better done as a delete and a fresh entry.
        if new_wallet.main_tracker_id != old_wallet.main_tracker_id:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="A transaction can only move to another wallet in the same tracker",
            )
        if new_wallet.currency != old_wallet.currency:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="A transaction can only move to a wallet with the same currency",
            )

    new_amount = payload.amount if payload.amount is not None else transaction.amount

    if is_opening:
        if payload.type is not None:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="An opening balance cannot become an income or an expense",
            )
        if payload.category_id is not None:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="An opening balance has no category",
            )
        if new_amount == 0:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail="An opening balance cannot be zero. Delete it instead",
            )
        new_type = transaction.type
        new_category_id = None
    else:
        if new_amount <= 0:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail="The amount must be more than zero",
            )

        new_type = payload.type if payload.type is not None else transaction.type
        new_category_id = (
            payload.category_id if payload.category_id is not None else transaction.category_id
        )
        if new_category_id is None:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="Choose a category for this transaction",
            )

        category_changed = new_category_id != transaction.category_id
        if category_changed or new_type != transaction.type:
            _check_category(
                new_category_id,
                new_type,
                new_wallet,
                user,
                db,
                must_be_switched_on=category_changed,
            )

    wallets = _lock_wallets({old_wallet.id, new_wallet.id}, db)
    wallets[old_wallet.id].balance -= _effect(transaction.type, transaction.amount)
    wallets[new_wallet.id].balance += _effect(new_type, new_amount)

    transaction.wallet_id = new_wallet.id
    transaction.type = new_type
    transaction.amount = new_amount
    transaction.category_id = new_category_id

    if payload.transaction_date is not None:
        transaction.transaction_date = payload.transaction_date

    # Left out means "leave it"; sent as null means "clear it".
    if "description" in payload.model_fields_set:
        transaction.description = payload.description

    db.commit()

    return _load_response(transaction.id, db)


@router.delete(
    "/transactions/{transaction_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Delete a transaction",
)
def delete_transaction(
    transaction_id: int,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> None:
    transaction = get_owned_transaction(transaction_id, user, db, for_update=True)

    wallets = _lock_wallets({transaction.wallet_id}, db)
    wallets[transaction.wallet_id].balance -= _effect(transaction.type, transaction.amount)

    db.delete(transaction)
    db.commit()