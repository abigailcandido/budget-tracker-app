from fastapi import HTTPException, status
from sqlalchemy.orm import Session

from app.models import MainTracker, User, Wallet


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