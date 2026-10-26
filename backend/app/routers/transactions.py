"""Income and expense entries, and the wallet balances they move.

Every change to a transaction changes its wallet's balance in the same
database transaction, so the cached balance and the transaction log can never
disagree: either both are saved or neither is.

An entry is never changed or removed once recorded, so its history can always
be audited. Correcting an entry adds a reversal, which cancels it, and the
corrected entry; deleting an entry adds only the reversal. A reversal is a copy
of the entry with the amount negated and the same date, so every total that
adds up amounts by kind, category, wallet or date stays right without knowing
about adjustments at all.
"""

from datetime import date
from decimal import Decimal

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import and_, case, func, or_
from sqlalchemy.orm import Session, aliased

from app.deps import get_current_user, get_db
from app.models import (
    TRANSACTION_EXPENSE,
    TRANSACTION_INCOME,
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
    CurrencyTotalResponse,
    TrackerSummaryResponse,
    TransactionCreateRequest,
    TransactionResponse,
    TransactionUpdateRequest,
    WalletSummaryResponse,
    format_money,
)

router = APIRouter(tags=["transactions"])

ParentCategory = aliased(Category)
# The reversal that cancels a transaction, if there is one.
Reversal = aliased(Transaction)


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


def _require_current(transaction: Transaction, db: Session) -> None:
    """Only the latest version of an entry can be corrected or deleted.

    Older versions and reversals are history. Changing them would rewrite the
    record the reversals exist to keep.
    """
    if transaction.reverses_id is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="A reversal is part of an entry's history and cannot be changed",
        )

    already_reversed = (
        db.query(Transaction.id).filter(Transaction.reverses_id == transaction.id).first()
        is not None
    )
    if already_reversed:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This entry has already been corrected or deleted. Open its latest version instead",
        )


def _reverse(transaction: Transaction, wallets: dict[int, Wallet]) -> Transaction:
    """A reversal that cancels this transaction, applied to its wallet's balance.

    It carries the same date, wallet and category as the entry it cancels, so
    the period, wallet and category that entry counted towards all go back to
    what they would have been without it.
    """
    reversal = Transaction(
        user_id=transaction.user_id,
        wallet_id=transaction.wallet_id,
        category_id=transaction.category_id,
        recurring_rule_id=transaction.recurring_rule_id,
        source_main_tracker_id=transaction.source_main_tracker_id,
        amount=-transaction.amount,
        type=transaction.type,
        description=transaction.description,
        transaction_date=transaction.transaction_date,
        reverses_id=transaction.id,
        origin_id=transaction.origin_id or transaction.id,
    )
    wallets[transaction.wallet_id].balance += _effect(reversal.type, reversal.amount)
    return reversal


def _described(db: Session):
    """A query returning each transaction with its wallet and category names,
    and the id of the reversal that cancelled it, if any."""
    return (
        db.query(Transaction, Wallet, Category, ParentCategory, Reversal.id)
        .join(Wallet, Transaction.wallet_id == Wallet.id)
        .outerjoin(Category, Transaction.category_id == Category.id)
        .outerjoin(ParentCategory, Category.parent_id == ParentCategory.id)
        .outerjoin(Reversal, Reversal.reverses_id == Transaction.id)
    )


def _to_response(
    transaction: Transaction,
    wallet: Wallet,
    category: Category | None,
    parent: Category | None,
    reversal_id: int | None,
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
        replaces_id=transaction.replaces_id,
        reverses_id=transaction.reverses_id,
        is_reversed=reversal_id is not None,
    )


def _load_response(transaction_id: int, db: Session) -> TransactionResponse:
    row = _described(db).filter(Transaction.id == transaction_id).one()
    return _to_response(*row)


def _reject_backwards_range(start_date: date | None, end_date: date | None) -> None:
    if start_date is not None and end_date is not None and start_date > end_date:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="The start date must be on or before the end date",
        )


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
    start_date: date | None = None,
    end_date: date | None = None,
    include_adjustments: bool = Query(
        default=False,
        description=(
            "Also list versions that were corrected or deleted, and the "
            "reversals that cancelled them. Normally only the latest version "
            "of each entry is listed."
        ),
    ),
    limit: int = Query(default=100, ge=1, le=500),
    offset: int = Query(default=0, ge=0),
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> list[TransactionResponse]:
    _reject_backwards_range(start_date, end_date)
    tracker = get_owned_tracker(tracker_id, user, db)

    query = _described(db).filter(Wallet.main_tracker_id == tracker.id)

    if not include_adjustments:
        query = query.filter(Transaction.reverses_id.is_(None), Reversal.id.is_(None))

    # Both ends are included, so a range of one day is start_date == end_date.
    if start_date is not None:
        query = query.filter(Transaction.transaction_date >= start_date)
    if end_date is not None:
        query = query.filter(Transaction.transaction_date <= end_date)

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
            # A correction keeps the place of the entry it replaced, so fixing
            # an amount never moves the entry up the list. With adjustments
            # included, an entry's versions sit together, newest first.
            func.coalesce(Transaction.origin_id, Transaction.id).desc(),
            Transaction.id.desc(),
        )
        .offset(offset)
        .limit(limit)
        .all()
    )

    return [_to_response(*row) for row in rows]


@router.get(
    "/trackers/{tracker_id}/summary",
    response_model=TrackerSummaryResponse,
    summary="Each wallet's opening, income, expenses and ending balance over a date range",
)
def tracker_summary(
    tracker_id: int,
    start_date: date,
    end_date: date,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> TrackerSummaryResponse:
    _reject_backwards_range(start_date, end_date)
    tracker = get_owned_tracker(tracker_id, user, db)

    wallets = (
        db.query(Wallet)
        .filter(Wallet.main_tracker_id == tracker.id)
        .order_by(Wallet.created_at)
        .all()
    )

    signed = case(
        (Transaction.type == TRANSACTION_EXPENSE, -Transaction.amount),
        else_=Transaction.amount,
    )
    in_range = Transaction.transaction_date >= start_date

    def total(condition, value):
        return func.coalesce(func.sum(case((condition, value), else_=0)), 0)

    # One pass over each wallet's entries up to the end of the range. Opening
    # is everything before the range began, plus an opening balance recorded
    # inside it — a wallet created mid-month starts the month with that amount
    # rather than with zero and an unexplained jump. With opening defined that
    # way, opening + income - expenses is always exactly the ending balance.
    rows = (
        db.query(
            Transaction.wallet_id,
            total(Transaction.transaction_date < start_date, signed),
            total(and_(in_range, Transaction.type == TRANSACTION_OPENING), Transaction.amount),
            total(and_(in_range, Transaction.type == TRANSACTION_INCOME), Transaction.amount),
            total(and_(in_range, Transaction.type == TRANSACTION_EXPENSE), Transaction.amount),
        )
        .join(Wallet, Transaction.wallet_id == Wallet.id)
        .filter(
            Wallet.main_tracker_id == tracker.id,
            Transaction.transaction_date <= end_date,
        )
        .group_by(Transaction.wallet_id)
        .all()
    )
    figures = {
        wallet_id: (Decimal(before) + Decimal(opened), Decimal(income), Decimal(spent))
        for wallet_id, before, opened, income, spent in rows
    }

    wallet_rows: list[WalletSummaryResponse] = []
    totals: dict[str, list[Decimal]] = {}

    for wallet in wallets:
        # A wallet that did not exist yet when the range ended has nothing to
        # say about it, so it is left out rather than shown as a row of zeros.
        if wallet.id not in figures and wallet.created_at.date() > end_date:
            continue

        opening, income, expenses = figures.get(
            wallet.id, (Decimal("0"), Decimal("0"), Decimal("0"))
        )
        ending = opening + income - expenses

        wallet_rows.append(
            WalletSummaryResponse(
                wallet_id=wallet.id,
                wallet_name=wallet.name,
                currency=wallet.currency,
                opening=format_money(opening),
                income=format_money(income),
                expenses=format_money(expenses),
                ending=format_money(ending),
            )
        )

        running = totals.setdefault(wallet.currency, [Decimal("0")] * 4)
        for index, amount in enumerate((opening, income, expenses, ending)):
            running[index] += amount

    return TrackerSummaryResponse(
        start_date=start_date,
        end_date=end_date,
        wallets=wallet_rows,
        totals=[
            CurrencyTotalResponse(
                currency=currency,
                opening=format_money(opening),
                income=format_money(income),
                expenses=format_money(expenses),
                ending=format_money(ending),
            )
            for currency, (opening, income, expenses, ending) in totals.items()
        ],
    )


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


@router.get(
    "/transactions/{transaction_id}/history",
    response_model=list[TransactionResponse],
    summary="Every version of an entry and every reversal, oldest first",
)
def transaction_history(
    transaction_id: int,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> list[TransactionResponse]:
    transaction = get_owned_transaction(transaction_id, user, db)
    origin_id = transaction.origin_id or transaction.id

    rows = (
        _described(db)
        .filter(
            Transaction.user_id == user.id,
            or_(Transaction.id == origin_id, Transaction.origin_id == origin_id),
        )
        .order_by(Transaction.id)
        .all()
    )
    return [_to_response(*row) for row in rows]


@router.patch(
    "/transactions/{transaction_id}",
    response_model=TransactionResponse,
    summary="Correct a transaction",
    description=(
        "The entry itself is left as it was. A reversal that cancels it and "
        "the corrected entry are added instead, and the corrected entry is "
        "returned. Its id is new."
    ),
)
def update_transaction(
    transaction_id: int,
    payload: TransactionUpdateRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> TransactionResponse:
    transaction = get_owned_transaction(transaction_id, user, db, for_update=True)
    _require_current(transaction, db)
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

    new_date = (
        payload.transaction_date
        if payload.transaction_date is not None
        else transaction.transaction_date
    )
    # Left out means "leave it"; sent as null means "clear it".
    new_description = (
        payload.description
        if "description" in payload.model_fields_set
        else transaction.description
    )

    # Saving without changing anything adds nothing, so the history only ever
    # records real corrections.
    unchanged = (
        new_wallet.id == transaction.wallet_id
        and new_type == transaction.type
        and new_amount == transaction.amount
        and new_category_id == transaction.category_id
        and new_date == transaction.transaction_date
        and new_description == transaction.description
    )
    if unchanged:
        return _load_response(transaction.id, db)

    wallets = _lock_wallets({old_wallet.id, new_wallet.id}, db)
    reversal = _reverse(transaction, wallets)

    correction = Transaction(
        user_id=user.id,
        wallet_id=new_wallet.id,
        category_id=new_category_id,
        recurring_rule_id=transaction.recurring_rule_id,
        source_main_tracker_id=transaction.source_main_tracker_id,
        amount=new_amount,
        type=new_type,
        description=new_description,
        transaction_date=new_date,
        replaces_id=transaction.id,
        origin_id=transaction.origin_id or transaction.id,
    )
    wallets[new_wallet.id].balance += _effect(new_type, new_amount)

    db.add_all([reversal, correction])
    db.commit()

    return _load_response(correction.id, db)


@router.delete(
    "/transactions/{transaction_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Delete a transaction",
    description=(
        "The entry is kept as history. A reversal that cancels it is added, "
        "and the entry no longer appears in the tracker's list."
    ),
)
def delete_transaction(
    transaction_id: int,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> None:
    transaction = get_owned_transaction(transaction_id, user, db, for_update=True)
    _require_current(transaction, db)

    wallets = _lock_wallets({transaction.wallet_id}, db)
    db.add(_reverse(transaction, wallets))
    db.commit()