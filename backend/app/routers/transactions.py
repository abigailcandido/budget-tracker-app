"""Income and expense entries, transfers between wallets, and the wallet
balances they move.

Every change to a transaction changes its wallet's balance in the same
database transaction, so the cached balance and the transaction log can never
disagree: either both are saved or neither is.

An entry is never changed or removed once recorded, so its history can always
be audited. Correcting an entry adds a reversal, which cancels it, and the
corrected entry; deleting an entry adds only the reversal. A reversal is a copy
of the entry with the amount negated and the same date, so every total that
adds up amounts by kind, category, wallet or date stays right without knowing
about adjustments at all. A deleted entry stays in the tracker's list, marked
as deleted, and a reversal records why it was made.
"""

from datetime import date
from decimal import Decimal

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Query, status
from sqlalchemy import and_, case, func, or_, select
from sqlalchemy.orm import Session, aliased

from app.category_lookup import find_or_create_leaf, find_or_create_path, is_others, visible_in
from app.deps import get_current_user, get_db
from app.email_codes import (
    CODE_LIFETIME,
    DELETE_TRANSACTION,
    issue_code,
    masked_email,
    refuse_if_too_soon,
    use_code,
)
from app.mailer import is_undeliverable, send_email
from app.models import (
    OTHERS_CATEGORY_NAME,
    TRANSACTION_EXPENSE,
    TRANSACTION_INCOME,
    TRANSACTION_OPENING,
    TRANSACTION_TRANSFER_IN,
    TRANSACTION_TRANSFER_OUT,
    TRANSFER_FEE_CATEGORY_NAME,
    TRANSFER_FEE_NOTE,
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
from app.routers.categories import GENERAL_SUBCATEGORY_NAME
from app.schemas import (
    CurrencyTotalResponse,
    EmailCodeResponse,
    PromoteOtherRequest,
    PromoteOtherResponse,
    TrackerSummaryResponse,
    TransactionCreateRequest,
    TransactionDeleteRequest,
    TransactionResponse,
    TransactionRestoreRequest,
    TransactionUpdateRequest,
    TransferCreateRequest,
    TransferUpdateRequest,
    WalletSummaryResponse,
    format_money,
)

router = APIRouter(tags=["transactions"])

ParentCategory = aliased(Category)
# The reversal that cancels a transaction, if there is one.
Reversal = aliased(Transaction)
# The correction that replaced a transaction, if there is one. A version that
# was reversed with nothing replacing it was deleted.
Correction = aliased(Transaction)

# On a transfer, the row where the money arrived, and that row's wallet.
Arrival = aliased(Transaction)
ArrivalWallet = aliased(Wallet)

# On a transfer, its fee, if it has one.
Fee = aliased(Transaction)

# On a fee, the version of the transfer it was charged with, and the latest
# version of that transfer: the newest row in its history that is not a
# reversal. After a correction or a deletion, the fee can still find it.
FeeFor = aliased(Transaction)
TransferVersion = aliased(Transaction)
_fee_for_origin = func.coalesce(FeeFor.origin_id, FeeFor.id)
_latest_transfer = (
    select(func.max(TransferVersion.id))
    .where(
        or_(TransferVersion.id == _fee_for_origin, TransferVersion.origin_id == _fee_for_origin),
        TransferVersion.reverses_id.is_(None),
    )
    .correlate(FeeFor)
    .scalar_subquery()
)

# --------------------------------------------------------------------------
# Shared helpers
# --------------------------------------------------------------------------


def _effect(kind: str, amount: Decimal) -> Decimal:
    """How much a transaction adds to its wallet's balance."""
    return -amount if kind in (TRANSACTION_EXPENSE, TRANSACTION_TRANSFER_OUT) else amount


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


def _reverse(
    transaction: Transaction,
    wallets: dict[int, Wallet],
    reason: str | None = None,
) -> Transaction:
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
        other_name=transaction.other_name,
        reason=reason,
    )
    wallets[transaction.wallet_id].balance += _effect(reversal.type, reversal.amount)
    return reversal


def _other_name_for(category: Category | None, other_name: str | None) -> str | None:
    """An entry under Others needs a name for what it was; no other entry has one.

    The name is what lets the entry be given a category of its own later.
    """
    if is_others(category):
        if not other_name:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail="Say what this entry was, so it can be given a category later",
            )
        if other_name.strip().lower() == OTHERS_CATEGORY_NAME.lower():
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail="Say what this entry was, rather than just Others",
            )
        return other_name

    if other_name is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Only an entry under Others has a name of its own",
        )
    return None


def _described(db: Session):
    """A query returning each transaction with its wallet and category names,
    the ids of the reversal that cancelled it and the correction that
    replaced it, if any, and, on a transfer, where the money arrived."""
    return (
        db.query(
            Transaction,
            Wallet,
            Category,
            ParentCategory,
            Reversal.id,
            Correction.id,
            Arrival,
            ArrivalWallet,
            Fee,
            _latest_transfer,
        )
        .join(Wallet, Transaction.wallet_id == Wallet.id)
        .outerjoin(Category, Transaction.category_id == Category.id)
        .outerjoin(ParentCategory, Category.parent_id == ParentCategory.id)
        .outerjoin(Reversal, Reversal.reverses_id == Transaction.id)
        .outerjoin(Correction, Correction.replaces_id == Transaction.id)
        .outerjoin(Arrival, Arrival.transfer_out_id == Transaction.id)
        .outerjoin(ArrivalWallet, Arrival.wallet_id == ArrivalWallet.id)
        .outerjoin(Fee, Fee.fee_for_id == Transaction.id)
        .outerjoin(FeeFor, FeeFor.id == Transaction.fee_for_id)
    )


def _to_response(
    transaction: Transaction,
    wallet: Wallet,
    category: Category | None,
    parent: Category | None,
    reversal_id: int | None,
    correction_id: int | None,
    arrival: Transaction | None = None,
    arrival_wallet: Wallet | None = None,
    fee: Transaction | None = None,
    fee_transfer_id: int | None = None,
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
        parent_category_is_global=(
            parent.main_tracker_id is None if parent is not None else None
        ),
        description=transaction.description,
        transaction_date=transaction.transaction_date,
        created_at=transaction.created_at,
        replaces_id=transaction.replaces_id,
        reverses_id=transaction.reverses_id,
        is_reversed=reversal_id is not None,
        is_deleted=reversal_id is not None and correction_id is None,
        is_restoration=transaction.is_restoration,
        other_name=transaction.other_name,
        reason=transaction.reason,
        to_wallet_id=arrival_wallet.id if arrival_wallet is not None else None,
        to_wallet_name=arrival_wallet.name if arrival_wallet is not None else None,
        to_currency=arrival_wallet.currency if arrival_wallet is not None else None,
        to_amount=format_money(arrival.amount) if arrival is not None else None,
        fee=format_money(fee.amount) if fee is not None else None,
        fee_for_id=transaction.fee_for_id,
        fee_transfer_id=fee_transfer_id,
    )


def _load_response(transaction_id: int, db: Session) -> TransactionResponse:
    row = _described(db).filter(Transaction.id == transaction_id).one()
    return _to_response(*row)


def _arrival(departure: Transaction, db: Session) -> Transaction | None:
    """On a transfer, the row where the money arrived. Locked, since it is
    corrected, deleted and restored along with the transfer."""
    return (
        db.query(Transaction)
        .filter(Transaction.transfer_out_id == departure.id)
        .with_for_update()
        .first()
    )


def _fee(departure: Transaction, db: Session) -> Transaction | None:
    """On a transfer, its fee, if it has one. Locked, since it is corrected,
    deleted and restored along with the transfer."""
    return (
        db.query(Transaction)
        .filter(Transaction.fee_for_id == departure.id)
        .with_for_update()
        .first()
    )


def _transfer_fee_category(wallet: Wallet, user: User, db: Session) -> Category:
    """Where every transfer fee goes: the tracker's Transfer Fee category, made
    and switched on there the first time a fee is charged. If Transfer Fee has
    been given sub-categories since, fees go under its General."""
    tracker = get_owned_tracker(wallet.main_tracker_id, user, db)
    top = (
        db.query(Category)
        .filter(
            Category.user_id == user.id,
            func.lower(Category.name) == TRANSFER_FEE_CATEGORY_NAME.lower(),
            Category.parent_id.is_(None),
            Category.kind == TRANSACTION_EXPENSE,
            visible_in(tracker),
        )
        .first()
    )
    has_children = top is not None and (
        db.query(Category.id).filter(Category.parent_id == top.id).first() is not None
    )
    if has_children:
        category, _ = find_or_create_leaf(
            GENERAL_SUBCATEGORY_NAME, TRANSACTION_EXPENSE, top, tracker, user, db
        )
    else:
        category, _ = find_or_create_leaf(
            TRANSFER_FEE_CATEGORY_NAME, TRANSACTION_EXPENSE, None, tracker, user, db
        )
    return category


def _removed_fee(departure: Transaction, db: Session) -> Transaction | None:
    """The fee an earlier version of this transfer had, if a correction
    removed it: the transfer's latest fee, when that fee is deleted. Adding a
    fee back replaces it, so it is locked."""
    origin = departure.origin_id or departure.id
    versions = select(Transaction.id).where(
        or_(Transaction.id == origin, Transaction.origin_id == origin),
        Transaction.reverses_id.is_(None),
    )
    latest = (
        db.query(Transaction)
        .filter(Transaction.fee_for_id.in_(versions))
        .order_by(Transaction.id.desc())
        .with_for_update()
        .first()
    )
    if latest is None:
        return None
    is_reversed = (
        db.query(Transaction.id).filter(Transaction.reverses_id == latest.id).first()
        is not None
    )
    is_replaced = (
        db.query(Transaction.id).filter(Transaction.replaces_id == latest.id).first()
        is not None
    )
    return latest if is_reversed and not is_replaced else None


def _charge_fee(
    departure: Transaction,
    destination: Wallet,
    amount: Decimal,
    category: Category,
    wallets: dict[int, Wallet],
    replaces: Transaction | None = None,
    is_restoration: bool = False,
    reason: str | None = None,
) -> Transaction:
    """A transfer's fee: an expense from the wallet the money left, applied to
    its balance. When the transfer is corrected, the new fee replaces the old
    one; when a removed fee is added back, it restores that one. The departure
    must already have its id."""
    wallets[departure.wallet_id].balance += _effect(TRANSACTION_EXPENSE, amount)
    return Transaction(
        user_id=departure.user_id,
        wallet_id=departure.wallet_id,
        category_id=category.id,
        amount=amount,
        type=TRANSACTION_EXPENSE,
        description=TRANSFER_FEE_NOTE.format(destination.name)[:255],
        transaction_date=departure.transaction_date,
        replaces_id=replaces.id if replaces is not None else None,
        origin_id=(replaces.origin_id or replaces.id) if replaces is not None else None,
        is_restoration=is_restoration,
        reason=reason,
        fee_for_id=departure.id,
    )


def _arrive(
    departure: Transaction,
    wallet_id: int,
    amount: Decimal,
    wallets: dict[int, Wallet],
) -> Transaction:
    """The row where a transfer arrives, applied to that wallet's balance.
    The departure must already have its id."""
    wallets[wallet_id].balance += amount
    return Transaction(
        user_id=departure.user_id,
        wallet_id=wallet_id,
        amount=amount,
        type=TRANSACTION_TRANSFER_IN,
        description=departure.description,
        transaction_date=departure.transaction_date,
        transfer_out_id=departure.id,
    )


def _refuse_transfer_part(transaction: Transaction) -> None:
    """Where a transfer arrived, and a transfer's fee, go wherever the transfer
    goes, so neither is ever corrected, deleted or restored by itself."""
    if transaction.type == TRANSACTION_TRANSFER_IN:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This is where a transfer arrived. Open the transfer itself instead",
        )
    if transaction.fee_for_id is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This is a transfer's fee. Open the transfer to change or delete it",
        )

    
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
    other_name = _other_name_for(category, payload.other_name)

    wallets = _lock_wallets({wallet.id}, db)

    transaction = Transaction(
        user_id=user.id,
        wallet_id=wallet.id,
        category_id=category.id,
        amount=payload.amount,
        type=payload.type,
        description=payload.description,
        transaction_date=payload.transaction_date,
        other_name=other_name,
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
            "Also list versions that were corrected, and the reversals that "
            "cancelled them. Normally only the latest version of each entry "
            "is listed, deleted entries included."
        ),
    ),
    limit: int = Query(default=100, ge=1, le=500),
    offset: int = Query(default=0, ge=0),
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> list[TransactionResponse]:
    _reject_backwards_range(start_date, end_date)
    tracker = get_owned_tracker(tracker_id, user, db)

    # A transfer is listed once, by the row where the money left. Where it
    # arrived comes with it, as to_wallet and to_amount.
    query = _described(db).filter(
        Wallet.main_tracker_id == tracker.id,
        Transaction.type != TRANSACTION_TRANSFER_IN,
    )

    if not include_adjustments:
        # The current version of each entry, and deleted entries, which stay in
        # the list marked as deleted. Earlier versions of corrected entries,
        # and the reversals themselves, belong to the entry's history.
        query = query.filter(
            Transaction.reverses_id.is_(None),
            or_(Reversal.id.is_(None), Correction.id.is_(None)),
        )

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
        # A transfer into the wallet counts as one of its entries too.
        query = query.filter(
            or_(Transaction.wallet_id == wallet.id, Arrival.wallet_id == wallet.id)
        )

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
    summary="Each wallet's opening, income, expenses, transfers and ending balance over a date range",
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
        (
            Transaction.type.in_((TRANSACTION_EXPENSE, TRANSACTION_TRANSFER_OUT)),
            -Transaction.amount,
        ),
        else_=Transaction.amount,
    )
    in_range = Transaction.transaction_date >= start_date

    def total(condition, value):
        return func.coalesce(func.sum(case((condition, value), else_=0)), 0)

    def of_kind(kind):
        return and_(in_range, Transaction.type == kind)

    # One pass over each wallet's entries up to the end of the range. Opening
    # is everything before the range began, plus an opening balance recorded
    # inside it — a wallet created mid-month starts the month with that amount
    # rather than with zero and an unexplained jump. With opening defined that
    # way, opening + income + transfers in - expenses - transfers out is always
    # exactly the ending balance.
    rows = (
        db.query(
            Transaction.wallet_id,
            total(Transaction.transaction_date < start_date, signed),
            total(of_kind(TRANSACTION_OPENING), Transaction.amount),
            total(of_kind(TRANSACTION_INCOME), Transaction.amount),
            total(of_kind(TRANSACTION_EXPENSE), Transaction.amount),
            total(of_kind(TRANSACTION_TRANSFER_IN), Transaction.amount),
            total(of_kind(TRANSACTION_TRANSFER_OUT), Transaction.amount),
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
        wallet_id: (
            Decimal(before) + Decimal(opened),
            Decimal(income),
            Decimal(spent),
            Decimal(moved_in),
            Decimal(moved_out),
        )
        for wallet_id, before, opened, income, spent, moved_in, moved_out in rows
    }

    wallet_rows: list[WalletSummaryResponse] = []
    totals: dict[str, list[Decimal]] = {}

    for wallet in wallets:
        # A wallet that did not exist yet when the range ended has nothing to
        # say about it, so it is left out rather than shown as a row of zeros.
        if wallet.id not in figures and wallet.created_at.date() > end_date:
            continue

        opening, income, expenses, transfers_in, transfers_out = figures.get(
            wallet.id, (Decimal("0"),) * 5
        )
        ending = opening + income + transfers_in - expenses - transfers_out

        wallet_rows.append(
            WalletSummaryResponse(
                wallet_id=wallet.id,
                wallet_name=wallet.name,
                currency=wallet.currency,
                opening=format_money(opening),
                income=format_money(income),
                expenses=format_money(expenses),
                transfers_in=format_money(transfers_in),
                transfers_out=format_money(transfers_out),
                ending=format_money(ending),
            )
        )

        running = totals.setdefault(wallet.currency, [Decimal("0")] * 6)
        for index, amount in enumerate(
            (opening, income, expenses, transfers_in, transfers_out, ending)
        ):
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
                transfers_in=format_money(transfers_in),
                transfers_out=format_money(transfers_out),
                ending=format_money(ending),
            )
            for currency, (
                opening,
                income,
                expenses,
                transfers_in,
                transfers_out,
                ending,
            ) in totals.items()
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
    _refuse_transfer_part(transaction)
    if transaction.type == TRANSACTION_TRANSFER_OUT:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="A transfer is corrected as a whole, through the transfers endpoint",
        )
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
        if payload.category_id is not None or payload.other_name is not None:
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
        new_other_name = None
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
            new_category = _check_category(
                new_category_id,
                new_type,
                new_wallet,
                user,
                db,
                must_be_switched_on=category_changed,
            )
        else:
            new_category = get_owned_category(new_category_id, user, db)

        # Left out, an entry staying under Others keeps its name; one moving
        # to a different category has to be given a new one if it needs one.
        if "other_name" in payload.model_fields_set:
            requested_name = payload.other_name
        elif not category_changed:
            requested_name = transaction.other_name
        else:
            requested_name = None
        new_other_name = _other_name_for(new_category, requested_name)

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
        and new_other_name == transaction.other_name
    )
    if unchanged:
        return _load_response(transaction.id, db)

    wallets = _lock_wallets({old_wallet.id, new_wallet.id}, db)
    reversal = _reverse(transaction, wallets, payload.reason)

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
        other_name=new_other_name,
    )
    wallets[new_wallet.id].balance += _effect(new_type, new_amount)

    db.add_all([reversal, correction])
    db.commit()

    return _load_response(correction.id, db)


def _entry_summary(entry: TransactionResponse) -> str:
    """One line naming an entry, for the email that confirms deleting it."""
    day = entry.transaction_date
    if entry.type == TRANSACTION_TRANSFER_OUT:
        return (
            f"Transfer · {entry.currency} {entry.amount} · {day.day} {day:%b %Y} · "
            f"{entry.wallet_name} → {entry.to_wallet_name}"
            + (f" · fee {entry.currency} {entry.fee}" if entry.fee is not None else "")
        )

    if entry.type == TRANSACTION_OPENING:
        what = "Opening balance"
    else:
        category = entry.category_name or "No category"
        if entry.parent_category_name:
            category = f"{entry.parent_category_name} › {category}"
        if entry.other_name:
            category = f"{category}: {entry.other_name}"
        what = f"{entry.type.capitalize()} · {category}"

    return f"{what} · {entry.currency} {entry.amount} · {day.day} {day:%b %Y} · {entry.wallet_name}"


def _delete_code_email(code: str, summary: str, minutes: int) -> str:
    return (
        f"Your code is {code}\n\n"
        "Enter it in Budget Tracker to delete this transaction:\n"
        f"{summary}\n\n"
        f"The code works once, only for this transaction, and stops working "
        f"in {minutes} minutes.\n\n"
        "If you didn't ask to delete anything, someone may be using your phone. "
        "Change your password and your PIN.\n"
    )


@router.post(
    "/transactions/{transaction_id}/delete-code",
    response_model=EmailCodeResponse,
    summary="Email a code for deleting a transaction",
    description=(
        "Sends a six-digit code to the account's email address. Deleting the "
        "entry needs it. The code works once, only for this entry, and expires "
        "after ten minutes. Asking again sends a new code and cancels the old one."
    ),
)
def send_delete_code(
    transaction_id: int,
    background: BackgroundTasks,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> EmailCodeResponse:
    transaction = get_owned_transaction(transaction_id, user, db)
    _refuse_transfer_part(transaction)
    _require_current(transaction, db)
    refuse_if_too_soon(db, user, DELETE_TRANSACTION, transaction.id)

    summary = _entry_summary(_load_response(transaction.id, db))
    code = issue_code(db, user, DELETE_TRANSACTION, transaction.id)
    minutes = int(CODE_LIFETIME.total_seconds() // 60)

    # Sent after the response has gone, so a slow email service never holds
    # up the phone.
    background.add_task(
        send_email,
        user.email,
        f"Your Budget Tracker code: {code}",
        _delete_code_email(code, summary, minutes),
    )

    return EmailCodeResponse(
        sent_to=masked_email(user.email),
        expires_in_minutes=minutes,
        test_code=code if is_undeliverable(user.email) else None,
    )


@router.delete(
    "/transactions/{transaction_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Delete a transaction",
    description=(
        "Needs a reason and the code emailed by the delete-code endpoint. The "
        "entry is kept: a reversal that cancels it is added, carrying the "
        "reason, and the entry stays in the tracker's list marked as deleted."
    ),
)
def delete_transaction(
    transaction_id: int,
    payload: TransactionDeleteRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> None:
    transaction = get_owned_transaction(transaction_id, user, db, for_update=True)
    _refuse_transfer_part(transaction)
    _require_current(transaction, db)
    use_code(db, user, DELETE_TRANSACTION, transaction.id, payload.code)

    # Deleting a transfer cancels both of its rows and its fee, so the money
    # goes back. The fee came out of the wallet the transfer left.
    arrival = _arrival(transaction, db)
    fee = _fee(transaction, db)
    wallet_ids = {transaction.wallet_id}
    if arrival is not None:
        wallet_ids.add(arrival.wallet_id)
    wallets = _lock_wallets(wallet_ids, db)

    db.add(_reverse(transaction, wallets, payload.reason))
    if arrival is not None:
        db.add(_reverse(arrival, wallets, payload.reason))
    if fee is not None:
        db.add(_reverse(fee, wallets, payload.reason))
    db.commit()


@router.post(
    "/transactions/{transaction_id}/restore",
    response_model=TransactionResponse,
    summary="Restore a deleted transaction",
    description=(
        "The deleted entry and its deletion both stay in the history. A copy of "
        "the entry is added in place of the deleted version, so it counts "
        "towards its wallet again. The restored entry is returned. Its id is new."
    ),
)
def restore_transaction(
    transaction_id: int,
    payload: TransactionRestoreRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> TransactionResponse:
    transaction = get_owned_transaction(transaction_id, user, db, for_update=True)
    _refuse_transfer_part(transaction)

    if transaction.reverses_id is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="A reversal is part of an entry's history and cannot be restored",
        )

    is_reversed = (
        db.query(Transaction.id).filter(Transaction.reverses_id == transaction.id).first()
        is not None
    )
    is_replaced = (
        db.query(Transaction.id).filter(Transaction.replaces_id == transaction.id).first()
        is not None
    )
    if not is_reversed:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Only a deleted entry can be restored",
        )
    if is_replaced:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This entry was corrected or restored already. Open its latest version instead",
        )

    # A transfer comes back with the row where its money arrived. If that
    # wallet has since been deleted, there is nowhere for it to arrive.
    arrival = None
    fee = None
    if transaction.type == TRANSACTION_TRANSFER_OUT:
        arrival = _arrival(transaction, db)
        fee = _fee(transaction, db)
        if arrival is None:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="The wallet this transfer went to has been deleted, so it cannot be restored",
            )

    wallet_ids = {transaction.wallet_id}
    if arrival is not None:
        wallet_ids.add(arrival.wallet_id)
    wallets = _lock_wallets(wallet_ids, db)
    restored = Transaction(
        user_id=transaction.user_id,
        wallet_id=transaction.wallet_id,
        category_id=transaction.category_id,
        recurring_rule_id=transaction.recurring_rule_id,
        source_main_tracker_id=transaction.source_main_tracker_id,
        amount=transaction.amount,
        type=transaction.type,
        description=transaction.description,
        transaction_date=transaction.transaction_date,
        replaces_id=transaction.id,
        origin_id=transaction.origin_id or transaction.id,
        other_name=transaction.other_name,
        reason=payload.reason,
        is_restoration=True,
    )
    wallets[transaction.wallet_id].balance += _effect(transaction.type, transaction.amount)

    db.add(restored)
    if arrival is not None:
        db.flush()
        db.add(_arrive(restored, arrival.wallet_id, arrival.amount, wallets))
    # Its fee comes back with it, as a restored copy of the fee that was deleted.
    if fee is not None:
        db.add(
            Transaction(
                user_id=fee.user_id,
                wallet_id=fee.wallet_id,
                category_id=fee.category_id,
                amount=fee.amount,
                type=fee.type,
                description=fee.description,
                transaction_date=fee.transaction_date,
                replaces_id=fee.id,
                origin_id=fee.origin_id or fee.id,
                reason=payload.reason,
                is_restoration=True,
                fee_for_id=restored.id,
            )
        )
        wallets[fee.wallet_id].balance += _effect(fee.type, fee.amount)
    db.commit()

    return _load_response(restored.id, db)


@router.post(
    "/transactions/{transaction_id}/promote-other",
    response_model=PromoteOtherResponse,
    summary="Give an Others entry a category of its own",
    description=(
        "Finds the category with that name at the same level, ignoring "
        "capitals, or creates it. Under the top-level Others it can be given a "
        "sub-category too. Every current entry in the tracker filed under the "
        "same Others with the same name then moves into it, and each move is "
        "recorded as an adjustment."
    ),
)
def promote_other(
    transaction_id: int,
    payload: PromoteOtherRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> PromoteOtherResponse:
    transaction = get_owned_transaction(transaction_id, user, db)
    _require_current(transaction, db)

    others = (
        db.get(Category, transaction.category_id)
        if transaction.category_id is not None
        else None
    )
    if not is_others(others) or not transaction.other_name:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Only an entry under Others can be given a category this way",
        )

    name = payload.name or transaction.other_name

    wallet = get_owned_wallet(transaction.wallet_id, user, db)
    tracker = get_owned_tracker(wallet.main_tracker_id, user, db)
    parent = db.get(Category, others.parent_id) if others.parent_id is not None else None

    # Under Others at the top level, the new category is top-level too, with
    # its own sub-category if one was asked for; under "Bills › Others", it
    # becomes another of Bills' subcategories. Either way it belongs to this
    # tracker unless the user asked for it to be global.
    target, created = find_or_create_path(
        name,
        others.kind,
        parent,
        payload.subcategory_name,
        tracker,
        user,
        db,
        payload.is_global,
        payload.subcategory_is_global,
    )
    target_parent = db.get(Category, target.parent_id) if target.parent_id is not None else None
    label = f"{target_parent.name} › {target.name}" if target_parent is not None else target.name

    match_name = transaction.other_name.strip().lower()
    candidate_ids = [
        row.id
        for row in db.query(Transaction.id)
        .join(Wallet, Transaction.wallet_id == Wallet.id)
        .outerjoin(Reversal, Reversal.reverses_id == Transaction.id)
        .filter(
            Wallet.main_tracker_id == tracker.id,
            Transaction.category_id == others.id,
            Transaction.reverses_id.is_(None),
            Reversal.id.is_(None),
            func.lower(func.trim(Transaction.other_name)) == match_name,
        )
    ]
    entries = (
        db.query(Transaction)
        .filter(Transaction.id.in_(candidate_ids))
        .order_by(Transaction.id)
        .with_for_update()
        .populate_existing()
        .all()
    )
    wallets = _lock_wallets({entry.wallet_id for entry in entries}, db)

    reason = f"Moved from Others to {label}"
    moved = 0
    for entry in entries:
        # Checked again under the lock, in case a correction saved a moment
        # ago has already replaced this version.
        already_reversed = (
            db.query(Transaction.id).filter(Transaction.reverses_id == entry.id).first()
            is not None
        )
        if already_reversed:
            continue

        db.add(_reverse(entry, wallets, reason))
        db.add(
            Transaction(
                user_id=entry.user_id,
                wallet_id=entry.wallet_id,
                category_id=target.id,
                recurring_rule_id=entry.recurring_rule_id,
                source_main_tracker_id=entry.source_main_tracker_id,
                amount=entry.amount,
                type=entry.type,
                description=entry.description,
                transaction_date=entry.transaction_date,
                replaces_id=entry.id,
                origin_id=entry.origin_id or entry.id,
            )
        )
        wallets[entry.wallet_id].balance += _effect(entry.type, entry.amount)
        moved += 1

    db.commit()

    return PromoteOtherResponse(
        category_id=target.id,
        category_name=label,
        created=created,
        is_global=target.main_tracker_id is None,
        moved=moved,
    )

# --------------------------------------------------------------------------
# Transfers between wallets
# --------------------------------------------------------------------------


def _transfer_wallets(
    from_wallet_id: int,
    to_wallet_id: int,
    user: User,
    db: Session,
) -> tuple[Wallet, Wallet]:
    """The two wallets of a transfer: different, and in the same tracker."""
    source = get_owned_wallet(from_wallet_id, user, db)
    destination = get_owned_wallet(to_wallet_id, user, db)
    _require_main_tracker_wallet(source)
    _require_main_tracker_wallet(destination)

    if source.id == destination.id:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Choose two different wallets",
        )
    if source.main_tracker_id != destination.main_tracker_id:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="A transfer moves money between wallets of the same tracker",
        )
    return source, destination


def _amount_received(
    source: Wallet,
    destination: Wallet,
    amount: Decimal,
    to_amount: Decimal | None,
) -> Decimal:
    """What arrives. Between two wallets in one currency it is what left;
    between two currencies it has to be given, since there are no exchange
    rates yet."""
    if source.currency == destination.currency:
        if to_amount is not None and to_amount != amount:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail=(
                    f"Both wallets use {source.currency}, so what arrives is what leaves"
                ),
            )
        return amount

    if to_amount is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"Say how much arrives in {destination.name}, in {destination.currency}",
        )
    return to_amount


@router.post(
    "/transfers",
    response_model=TransactionResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Move money from one wallet to another",
    description=(
        "Both wallets must be in the same tracker. Between two currencies, "
        "to_amount says what arrives. The transfer is returned as the row where "
        "the money left, with to_wallet and to_amount for where it arrived. A "
        "fee, if given, is recorded as a separate expense from the first wallet."
    ),
)
def create_transfer(
    payload: TransferCreateRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> TransactionResponse:
    source, destination = _transfer_wallets(
        payload.from_wallet_id, payload.to_wallet_id, user, db
    )
    received = _amount_received(source, destination, payload.amount, payload.to_amount)

    # The fee is filed under Transfer Fee, which is made the first time.
    fee_category = None
    if payload.fee is not None:
        fee_category = _transfer_fee_category(source, user, db)

    wallets = _lock_wallets({source.id, destination.id}, db)

    departure = Transaction(
        user_id=user.id,
        wallet_id=source.id,
        amount=payload.amount,
        type=TRANSACTION_TRANSFER_OUT,
        description=payload.description,
        transaction_date=payload.transaction_date,
    )
    db.add(departure)
    db.flush()
    wallets[source.id].balance += _effect(TRANSACTION_TRANSFER_OUT, payload.amount)
    db.add(_arrive(departure, destination.id, received, wallets))

    # The fee is an ordinary expense, so it counts towards spending and the
    # Budget tab, which the transfer itself never does.
    if fee_category is not None:
        db.add(_charge_fee(departure, destination, payload.fee, fee_category, wallets))
    db.commit()

    return _load_response(departure.id, db)


@router.patch(
    "/transfers/{transfer_id}",
    response_model=TransactionResponse,
    summary="Correct a transfer",
    description=(
        "Give the transfer's own id, the row where the money left. Both of its "
        "rows are reversed and a corrected pair is added, so the history keeps "
        "every version. The corrected transfer is returned. Its id is new."
    ),
)
def update_transfer(
    transfer_id: int,
    payload: TransferUpdateRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> TransactionResponse:
    departure = get_owned_transaction(transfer_id, user, db, for_update=True)
    _refuse_transfer_part(departure)
    if departure.type != TRANSACTION_TRANSFER_OUT:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This entry is not a transfer",
        )
    _require_current(departure, db)
    arrival = _arrival(departure, db)
    if arrival is None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="The wallet this transfer went to has been deleted",
        )

    old_source = get_owned_wallet(departure.wallet_id, user, db)
    old_destination = get_owned_wallet(arrival.wallet_id, user, db)
    source, destination = _transfer_wallets(
        payload.from_wallet_id or old_source.id,
        payload.to_wallet_id or old_destination.id,
        user,
        db,
    )

    amount = payload.amount if payload.amount is not None else departure.amount
    to_amount = payload.to_amount
    same_currencies = (source.currency, destination.currency) == (
        old_source.currency,
        old_destination.currency,
    )
    # Between two currencies, what arrived stays as it was unless the
    # currencies themselves change, which needs a new figure.
    if to_amount is None and source.currency != destination.currency and same_currencies:
        to_amount = arrival.amount
    received = _amount_received(source, destination, amount, to_amount)

    new_date = (
        payload.transaction_date
        if payload.transaction_date is not None
        else departure.transaction_date
    )
    # Left out means "leave it"; sent as null means "clear it".
    new_description = (
        payload.description
        if "description" in payload.model_fields_set
        else departure.description
    )
    # The same for the fee: left out keeps it, null removes it.
    fee = _fee(departure, db)
    old_fee = fee.amount if fee is not None else None
    new_fee = payload.fee if "fee" in payload.model_fields_set else old_fee
    # A fee removed earlier and now added back carries on that fee's history,
    # the way a restore does, instead of starting a new expense.
    removed_fee = (
        _removed_fee(departure, db) if fee is None and new_fee is not None else None
    )

    unchanged = (
        source.id == departure.wallet_id
        and destination.id == arrival.wallet_id
        and amount == departure.amount
        and received == arrival.amount
        and new_date == departure.transaction_date
        and new_description == departure.description
        and new_fee == old_fee
    )
    if unchanged:
        return _load_response(departure.id, db)

    fee_category = (
        _transfer_fee_category(source, user, db) if new_fee is not None else None
    )
    wallets = _lock_wallets(
        {old_source.id, old_destination.id, source.id, destination.id}, db
    )
    db.add(_reverse(departure, wallets, payload.reason))
    db.add(_reverse(arrival, wallets, payload.reason))
    if fee is not None:
        db.add(_reverse(fee, wallets, payload.reason))

    correction = Transaction(
        user_id=user.id,
        wallet_id=source.id,
        amount=amount,
        type=TRANSACTION_TRANSFER_OUT,
        description=new_description,
        transaction_date=new_date,
        replaces_id=departure.id,
        origin_id=departure.origin_id or departure.id,
    )
    db.add(correction)
    db.flush()
    wallets[source.id].balance += _effect(TRANSACTION_TRANSFER_OUT, amount)
    db.add(_arrive(correction, destination.id, received, wallets))
    # A fee the transfer keeps is corrected along with it, and one added back
    # restores the removed one, so either way its history carries on. Only a
    # transfer's first fee starts a history of its own.
    if fee_category is not None:
        db.add(
            _charge_fee(
                correction,
                destination,
                new_fee,
                fee_category,
                wallets,
                replaces=fee or removed_fee,
                is_restoration=removed_fee is not None,
                reason=payload.reason if removed_fee is not None else None,
            )
        )
    db.commit()
    
    return _load_response(correction.id, db)