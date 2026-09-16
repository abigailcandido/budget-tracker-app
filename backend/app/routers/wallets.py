from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.deps import get_current_user, get_db
from app.models import Transaction, User, Wallet
from app.ownership import get_owned_tracker, get_owned_wallet
from app.schemas import (
    WalletCreateRequest,
    WalletResponse,
    WalletUpdateRequest,
    format_money,
)

router = APIRouter(tags=["wallets"])


def _to_response(wallet: Wallet) -> WalletResponse:
    return WalletResponse(
        id=wallet.id,
        main_tracker_id=wallet.main_tracker_id,
        name=wallet.name,
        currency=wallet.currency,
        balance=format_money(wallet.balance),
        created_at=wallet.created_at,
    )


def _reject_duplicate_name(
    name: str,
    tracker_id: int,
    db: Session,
    exclude_id: int | None = None,
) -> None:
    query = db.query(Wallet).filter(
        Wallet.main_tracker_id == tracker_id,
        Wallet.name == name,
    )

    if exclude_id is not None:
        query = query.filter(Wallet.id != exclude_id)

    if query.first() is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This tracker already has a wallet with this name",
        )


@router.post(
    "/trackers/{tracker_id}/wallets",
    response_model=WalletResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Add a wallet to a tracker",
)
def create_wallet(
    tracker_id: int,
    payload: WalletCreateRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> WalletResponse:
    tracker = get_owned_tracker(tracker_id, user, db)
    _reject_duplicate_name(payload.name, tracker.id, db)

    wallet = Wallet(
        user_id=user.id,
        main_tracker_id=tracker.id,
        name=payload.name,
        currency=payload.currency,
        balance=payload.opening_balance,
    )
    db.add(wallet)
    db.commit()
    db.refresh(wallet)

    return _to_response(wallet)


@router.get(
    "/trackers/{tracker_id}/wallets",
    response_model=list[WalletResponse],
    summary="List a tracker's wallets",
)
def list_wallets(
    tracker_id: int,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> list[WalletResponse]:
    tracker = get_owned_tracker(tracker_id, user, db)

    wallets = (
        db.query(Wallet)
        .filter(Wallet.main_tracker_id == tracker.id)
        .order_by(Wallet.created_at)
        .all()
    )

    return [_to_response(wallet) for wallet in wallets]


@router.get(
    "/wallets/{wallet_id}",
    response_model=WalletResponse,
    summary="Get one wallet",
)
def get_wallet(
    wallet_id: int,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> WalletResponse:
    return _to_response(get_owned_wallet(wallet_id, user, db))


@router.patch(
    "/wallets/{wallet_id}",
    response_model=WalletResponse,
    summary="Rename a wallet",
)
def rename_wallet(
    wallet_id: int,
    payload: WalletUpdateRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> WalletResponse:
    wallet = get_owned_wallet(wallet_id, user, db)
    _reject_duplicate_name(payload.name, wallet.main_tracker_id, db, exclude_id=wallet.id)

    wallet.name = payload.name
    db.commit()
    db.refresh(wallet)

    return _to_response(wallet)


@router.delete(
    "/wallets/{wallet_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Delete an empty wallet",
)
def delete_wallet(
    wallet_id: int,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> None:
    wallet = get_owned_wallet(wallet_id, user, db)

    has_transactions = (
        db.query(Transaction).filter(Transaction.wallet_id == wallet.id).first()
        is not None
    )
    if has_transactions:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Remove this wallet's transactions before deleting it",
        )

    db.delete(wallet)
    db.commit()