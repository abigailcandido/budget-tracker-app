from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import func, or_
from sqlalchemy.orm import Session

from app.deps import get_current_user, get_db
from app.models import (
    BudgetLimit,
    Category,
    MainTracker,
    RecurringRule,
    Transaction,
    TrackerCategory,
    User,
)
from app.ownership import get_owned_category, get_owned_tracker
from app.schemas import (
    CategoryCreateRequest,
    CategoryResponse,
    CategoryUpdateRequest,
    TrackerCategoryResponse,
)

router = APIRouter(tags=["categories"])


def _to_response(category: Category) -> CategoryResponse:
    return CategoryResponse(
        id=category.id,
        name=category.name,
        main_tracker_id=category.main_tracker_id,
        is_global=category.main_tracker_id is None,
        created_at=category.created_at,
    )


def _reject_duplicate_name(
    name: str,
    user: User,
    db: Session,
    exclude_id: int | None = None,
) -> None:
    """Category names are unique across the whole account.

    A stricter rule than wallets, and deliberately so. A global "Food" and a
    tracker-private "Food" would both appear in the same picker, and no user
    could tell which was which at the moment of choosing.
    """
    query = db.query(Category).filter(
        Category.user_id == user.id,
        func.lower(Category.name) == name.lower(),
    )

    if exclude_id is not None:
        query = query.filter(Category.id != exclude_id)

    if query.first() is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="You already have a category with this name",
        )


@router.post(
    "/categories",
    response_model=CategoryResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Create a category",
)
def create_category(
    payload: CategoryCreateRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> CategoryResponse:
    _reject_duplicate_name(payload.name, user, db)

    tracker: MainTracker | None = None
    if payload.main_tracker_id is not None:
        tracker = get_owned_tracker(payload.main_tracker_id, user, db)

    category = Category(
        user_id=user.id,
        name=payload.name,
        main_tracker_id=tracker.id if tracker is not None else None,
    )
    db.add(category)
    db.flush()

    # Switch the new category on where it belongs: in its own tracker if it is
    # private, in every tracker if it is global. Without this a new category
    # would exist but appear nowhere, which reads as a bug rather than a choice.
    if tracker is not None:
        db.add(TrackerCategory(category_id=category.id, main_tracker_id=tracker.id))
    else:
        trackers = db.query(MainTracker).filter(MainTracker.user_id == user.id).all()
        for existing_tracker in trackers:
            db.add(
                TrackerCategory(
                    category_id=category.id,
                    main_tracker_id=existing_tracker.id,
                )
            )

    db.commit()
    db.refresh(category)

    return _to_response(category)


@router.get(
    "/categories",
    response_model=list[CategoryResponse],
    summary="List every category on your account",
)
def list_categories(
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> list[CategoryResponse]:
    categories = (
        db.query(Category)
        .filter(Category.user_id == user.id)
        .order_by(Category.name)
        .all()
    )

    return [_to_response(category) for category in categories]


@router.patch(
    "/categories/{category_id}",
    response_model=CategoryResponse,
    summary="Rename a category",
)
def rename_category(
    category_id: int,
    payload: CategoryUpdateRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> CategoryResponse:
    category = get_owned_category(category_id, user, db)
    _reject_duplicate_name(payload.name, user, db, exclude_id=category.id)

    category.name = payload.name
    db.commit()
    db.refresh(category)

    return _to_response(category)


@router.delete(
    "/categories/{category_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Delete an unused category",
)
def delete_category(
    category_id: int,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> None:
    category = get_owned_category(category_id, user, db)

    # Deleting a category that transactions already reference would either
    # destroy those records or leave them pointing at nothing. Turning the
    # category off in each tracker is the non-destructive alternative, and it
    # is what people almost always actually want.
    if db.query(Transaction).filter(Transaction.category_id == category.id).first():
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Transactions already use this category, so it cannot be deleted",
        )

    if db.query(RecurringRule).filter(RecurringRule.category_id == category.id).first():
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="A recurring rule uses this category, so it cannot be deleted",
        )

    if db.query(BudgetLimit).filter(BudgetLimit.category_id == category.id).first():
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Remove this category's budget limit before deleting it",
        )

    db.query(TrackerCategory).filter(
        TrackerCategory.category_id == category.id
    ).delete()
    db.delete(category)
    db.commit()


@router.get(
    "/trackers/{tracker_id}/categories",
    response_model=list[TrackerCategoryResponse],
    summary="List the categories a tracker can use",
)
def list_tracker_categories(
    tracker_id: int,
    only_enabled: bool = False,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> list[TrackerCategoryResponse]:
    tracker = get_owned_tracker(tracker_id, user, db)

    applicable = (
        db.query(Category)
        .filter(
            Category.user_id == user.id,
            or_(
                Category.main_tracker_id.is_(None),
                Category.main_tracker_id == tracker.id,
            ),
        )
        .order_by(Category.name)
        .all()
    )

    enabled_ids = {
        link.category_id
        for link in db.query(TrackerCategory).filter(
            TrackerCategory.main_tracker_id == tracker.id
        )
    }

    results = [
        TrackerCategoryResponse(
            id=category.id,
            name=category.name,
            is_global=category.main_tracker_id is None,
            is_enabled=category.id in enabled_ids,
        )
        for category in applicable
    ]

    if only_enabled:
        results = [result for result in results if result.is_enabled]

    return results


@router.put(
    "/trackers/{tracker_id}/categories/{category_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Switch a category on for a tracker",
)
def enable_category(
    tracker_id: int,
    category_id: int,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> None:
    tracker = get_owned_tracker(tracker_id, user, db)
    category = get_owned_category(category_id, user, db)

    if category.main_tracker_id is not None and category.main_tracker_id != tracker.id:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This category is private to a different tracker",
        )

    existing = (
        db.query(TrackerCategory)
        .filter(
            TrackerCategory.main_tracker_id == tracker.id,
            TrackerCategory.category_id == category.id,
        )
        .first()
    )

    if existing is None:
        db.add(TrackerCategory(category_id=category.id, main_tracker_id=tracker.id))
        db.commit()


@router.delete(
    "/trackers/{tracker_id}/categories/{category_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Switch a category off for a tracker",
)
def disable_category(
    tracker_id: int,
    category_id: int,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> None:
    tracker = get_owned_tracker(tracker_id, user, db)
    category = get_owned_category(category_id, user, db)

    link = (
        db.query(TrackerCategory)
        .filter(
            TrackerCategory.main_tracker_id == tracker.id,
            TrackerCategory.category_id == category.id,
        )
        .first()
    )

    if link is not None:
        db.delete(link)
        db.commit()