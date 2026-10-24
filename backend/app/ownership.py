from fastapi import HTTPException, status
from sqlalchemy.orm import Session

from app.models import Category, MainTracker, Transaction, User, Wallet


def get_owned_tracker(tracker_id: int, user: User, db: Session) -> MainTracker:
    """Fetch a tracker belonging to this user, or raise 404.

    The owner check is part of the same query as the id lookup, so there is no
    moment where the row has been loaded but not yet authorised. A tracker owned
    by someone else is indistinguishable from one that does not exist.
    """
    tracker = (
        db.query(MainTracker)
        .filter(MainTracker.id == tracker_id, MainTracker.user_id == user.id)
        .first()
    )

    if tracker is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Tracker not found",
        )

    return tracker


def get_owned_wallet(wallet_id: int, user: User, db: Session) -> Wallet:
    """Fetch a wallet belonging to this user, or raise 404.

    Wallets carry their own user_id rather than being checked through their
    tracker. That denormalisation is deliberate: ownership is one condition in
    one query, with no join to forget.
    """
    wallet = (
        db.query(Wallet)
        .filter(Wallet.id == wallet_id, Wallet.user_id == user.id)
        .first()
    )

    if wallet is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Wallet not found",
        )

    return wallet


def get_owned_category(category_id: int, user: User, db: Session) -> Category:
    """Fetch a category belonging to this user, or raise 404."""
    category = (
        db.query(Category)
        .filter(Category.id == category_id, Category.user_id == user.id)
        .first()
    )

    if category is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Category not found",
        )

    return category


def get_owned_transaction(
    transaction_id: int,
    user: User,
    db: Session,
    for_update: bool = False,
) -> Transaction:
    """Fetch a transaction belonging to this user, or raise 404.

    With for_update, the row is locked until the request commits, so two edits
    to the same transaction arriving together are applied one after the other
    instead of both starting from the same old amount.
    """
    query = db.query(Transaction).filter(
        Transaction.id == transaction_id,
        Transaction.user_id == user.id,
    )
    if for_update:
        query = query.with_for_update().populate_existing()

    transaction = query.first()

    if transaction is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Transaction not found",
        )

    return transaction