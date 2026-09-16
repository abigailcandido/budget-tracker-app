from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.deps import get_current_user, get_db
from app.models import MainTracker, SavingsTrackerLink, User, Wallet
from app.schemas import TrackerCreateRequest, TrackerResponse, TrackerUpdateRequest

router = APIRouter(prefix="/trackers", tags=["trackers"])


def _to_response(tracker: MainTracker) -> TrackerResponse:
    return TrackerResponse(
        id=tracker.id,
        name=tracker.name,
        created_at=tracker.created_at,
    )


def _get_owned_tracker(tracker_id: int, user: User, db: Session) -> MainTracker:
    """Fetch a tracker belonging to this user, or raise 404.

    The owner check is part of the same query as the id lookup, not a separate
    step afterwards. That ordering is what makes it safe: there is no moment
    where the row has been loaded but not yet authorised.

    A tracker owned by someone else returns 404 rather than 403, so it is
    indistinguishable from one that does not exist. A 403 would confirm the row
    is real, which is information a stranger has no business receiving.
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


def _reject_duplicate_name(
    name: str,
    user: User,
    db: Session,
    exclude_id: int | None = None,
) -> None:
    query = db.query(MainTracker).filter(
        MainTracker.user_id == user.id,
        MainTracker.name == name,
    )

    if exclude_id is not None:
        query = query.filter(MainTracker.id != exclude_id)

    if query.first() is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="You already have a tracker with this name",
        )


@router.post(
    "",
    response_model=TrackerResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Create a main tracker",
)
def create_tracker(
    payload: TrackerCreateRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> TrackerResponse:
    _reject_duplicate_name(payload.name, user, db)

    tracker = MainTracker(user_id=user.id, name=payload.name)
    db.add(tracker)
    db.commit()
    db.refresh(tracker)

    return _to_response(tracker)


@router.get(
    "",
    response_model=list[TrackerResponse],
    summary="List your main trackers",
)
def list_trackers(
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> list[TrackerResponse]:
    trackers = (
        db.query(MainTracker)
        .filter(MainTracker.user_id == user.id)
        .order_by(MainTracker.created_at)
        .all()
    )

    return [_to_response(tracker) for tracker in trackers]


@router.get(
    "/{tracker_id}",
    response_model=TrackerResponse,
    summary="Get one main tracker",
)
def get_tracker(
    tracker_id: int,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> TrackerResponse:
    return _to_response(_get_owned_tracker(tracker_id, user, db))


@router.patch(
    "/{tracker_id}",
    response_model=TrackerResponse,
    summary="Rename a main tracker",
)
def rename_tracker(
    tracker_id: int,
    payload: TrackerUpdateRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> TrackerResponse:
    tracker = _get_owned_tracker(tracker_id, user, db)
    _reject_duplicate_name(payload.name, user, db, exclude_id=tracker.id)

    tracker.name = payload.name
    db.commit()
    db.refresh(tracker)

    return _to_response(tracker)


@router.delete(
    "/{tracker_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Delete an empty main tracker",
)
def delete_tracker(
    tracker_id: int,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> None:
    tracker = _get_owned_tracker(tracker_id, user, db)

    # Deleting a tracker that still holds wallets would orphan every wallet,
    # transaction and balance inside it. Refusing forces the destructive part
    # to be an explicit, visible act rather than a side effect of one tap.
    has_wallets = (
        db.query(Wallet).filter(Wallet.main_tracker_id == tracker.id).first() is not None
    )
    if has_wallets:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Remove this tracker's wallets before deleting it",
        )

    is_funding_savings = (
        db.query(SavingsTrackerLink)
        .filter(SavingsTrackerLink.main_tracker_id == tracker.id)
        .first()
        is not None
    )
    if is_funding_savings:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This tracker is linked to a savings tracker and cannot be deleted",
        )

    db.delete(tracker)
    db.commit()