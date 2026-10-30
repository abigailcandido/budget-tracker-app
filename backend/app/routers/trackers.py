from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.deps import get_current_user, get_db
from app.models import (
    BudgetLimit,
    Category,
    MainTracker,
    RecurringRule,
    SavingsTrackerLink,
    TrackerCategory,
    Transaction,
    User,
    Wallet,
)
from app.ownership import get_owned_tracker
from app.schemas import TrackerCreateRequest, TrackerResponse, TrackerUpdateRequest

router = APIRouter(prefix="/trackers", tags=["trackers"])


def _to_response(tracker: MainTracker) -> TrackerResponse:
    return TrackerResponse(
        id=tracker.id,
        name=tracker.name,
        created_at=tracker.created_at,
    )


def _reject_duplicate_name(
    name: str,
    user: User,
    db: Session,
    exclude_id: int | None = None,
) -> None:
    query = db.query(MainTracker).filter(
        MainTracker.user_id == user.id,
        func.lower(MainTracker.name) == name.lower(),
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

    # Resolve the chosen categories BEFORE creating anything. A bad id should
    # fail the whole request, not leave a half-configured tracker behind.
    if payload.category_ids is None:
        chosen = (
            db.query(Category)
            .filter(Category.user_id == user.id, Category.main_tracker_id.is_(None))
            .all()
        )
    elif payload.category_ids:
        requested_ids = set(payload.category_ids)
        chosen = (
            db.query(Category)
            .filter(
                Category.user_id == user.id,
                Category.main_tracker_id.is_(None),
                Category.id.in_(requested_ids),
            )
            .all()
        )
        # Catches three problems with one comparison: an id that doesn't exist,
        # one belonging to another account, and one that is private to a
        # different tracker. All three are "not yours to use here", and all
        # three get the same answer so none of them reveals anything.
        if len(chosen) != len(requested_ids):
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="One or more of those categories could not be found",
            )

        # A subcategory cannot stand on its own. If one was picked, its parent
        # comes along, otherwise the new tracker gets a child with no heading
        # above it — reachable by nothing in the picker.
        parent_ids = {item.parent_id for item in chosen if item.parent_id is not None}
        if parent_ids:
            parents = (
                db.query(Category)
                .filter(Category.user_id == user.id, Category.id.in_(parent_ids))
                .all()
            )
            chosen = list({item.id: item for item in chosen + parents}.values())
    else:
        chosen = []

    tracker = MainTracker(user_id=user.id, name=payload.name)
    db.add(tracker)
    db.flush()

    for category in chosen:
        db.add(TrackerCategory(category_id=category.id, main_tracker_id=tracker.id))

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
    return _to_response(get_owned_tracker(tracker_id, user, db))


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
    tracker = get_owned_tracker(tracker_id, user, db)
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
    tracker = get_owned_tracker(tracker_id, user, db)

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

    # The tracker's own categories, such as its Others and its Transfer Fee,
    # were only ever offered here, so they go with it. Once its wallets are
    # gone nothing should still use them, but if something does, the delete
    # is refused rather than taking those records with it.
    own_categories = select(Category.id).where(Category.main_tracker_id == tracker.id)
    own_categories_in_use = (
        db.query(Transaction.id).filter(Transaction.category_id.in_(own_categories)).first()
        is not None
        or db.query(RecurringRule.id)
        .filter(RecurringRule.category_id.in_(own_categories))
        .first()
        is not None
    )
    if own_categories_in_use:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Entries still use this tracker's own categories, so it cannot be deleted",
        )

    # Category switches are bookkeeping rather than user data, so they are
    # cleared automatically rather than blocking the delete. Expected amounts
    # belong to this tracker alone, so they go with it too.
    db.query(TrackerCategory).filter(
        TrackerCategory.main_tracker_id == tracker.id
    ).delete()
    db.query(BudgetLimit).filter(BudgetLimit.main_tracker_id == tracker.id).delete()
    db.query(TrackerCategory).filter(
        TrackerCategory.category_id.in_(own_categories)
    ).delete(synchronize_session=False)
    db.query(BudgetLimit).filter(
        BudgetLimit.category_id.in_(own_categories)
    ).delete(synchronize_session=False)
    # Sub-categories first, since they point at their parents.
    db.query(Category).filter(
        Category.main_tracker_id == tracker.id,
        Category.parent_id.is_not(None),
    ).delete(synchronize_session=False)
    db.query(Category).filter(Category.main_tracker_id == tracker.id).delete(
        synchronize_session=False
    )

    db.delete(tracker)
    db.commit()