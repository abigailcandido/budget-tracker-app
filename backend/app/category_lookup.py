"""Finding a category by name, and making it when it does not exist yet.

Used for the Others buckets, which appear on demand the first time an entry
is filed under one, for giving an Others entry a category of its own, and for
a category typed in while an entry is being added.

A category made this way belongs to the tracker it was made in unless the
user asks for it to be global, and so does each tracker's Others. Under a
global category, such a sub-category can still belong to one tracker only.
"""

from fastapi import HTTPException, status
from sqlalchemy import func, or_
from sqlalchemy.orm import Session

from app.models import OTHERS_CATEGORY_NAME, Category, MainTracker, TrackerCategory, User


def is_others(category: Category | None) -> bool:
    """Whether a category is an Others bucket, whatever capitals it was given."""
    return (
        category is not None
        and category.name.strip().lower() == OTHERS_CATEGORY_NAME.lower()
    )


def visible_in(tracker: MainTracker):
    """The condition for a category a tracker can see: global, or its own."""
    return or_(
        Category.main_tracker_id.is_(None),
        Category.main_tracker_id == tracker.id,
    )


def switch_on(category: Category, tracker: MainTracker, db: Session) -> None:
    """Switch a leaf on in a tracker, with its parent, if they are not on already.

    Only the leaf and its parent: the parent's other subcategories keep the
    switches the user gave them.
    """
    ids = {category.id}
    if category.parent_id is not None:
        ids.add(category.parent_id)

    existing = {
        row.category_id
        for row in db.query(TrackerCategory.category_id).filter(
            TrackerCategory.main_tracker_id == tracker.id,
            TrackerCategory.category_id.in_(ids),
        )
    }
    for category_id in ids - existing:
        db.add(TrackerCategory(category_id=category_id, main_tracker_id=tracker.id))


def _refuse_name_kept_elsewhere(
    name: str,
    kind: str,
    parent: Category | None,
    tracker: MainTracker,
    user: User,
    db: Session,
) -> None:
    """A global category appears in every tracker, so it cannot share a name
    with one that another tracker keeps to itself: there the two would sit
    side by side."""
    query = db.query(Category.id).filter(
        Category.user_id == user.id,
        func.lower(Category.name) == name.lower(),
        Category.main_tracker_id.is_not(None),
        Category.main_tracker_id != tracker.id,
    )
    if parent is None:
        query = query.filter(Category.parent_id.is_(None), Category.kind == kind)
    else:
        query = query.filter(Category.parent_id == parent.id)

    if query.first() is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"Another tracker already has its own {name}. Keep this one to this "
                "tracker, or choose another name"
            ),
        )


def find_or_create_leaf(
    name: str,
    kind: str,
    parent: Category | None,
    tracker: MainTracker,
    user: User,
    db: Session,
    is_global: bool = False,
) -> tuple[Category, bool]:
    """The category with this name at this level, switched on in the tracker.

    Names are compared ignoring capitals and surrounding spaces, the same way
    every other category name is, so "pet food" finds "Pet Food". Only
    categories this tracker can see are looked at: global ones, and its own.

    When nothing matches, one is created, belonging to this tracker unless
    is_global is set. A global one is offered to every tracker but starts
    switched on only in this one; a global sub-category is switched on
    wherever its parent is, like any sub-category. Under a parent that belongs
    to one tracker, the sub-category always belongs to the same tracker.

    Returns the category, and whether it was created just now.
    """
    clean_name = name.strip()

    query = db.query(Category).filter(
        Category.user_id == user.id,
        func.lower(Category.name) == clean_name.lower(),
        visible_in(tracker),
    )
    if parent is None:
        query = query.filter(Category.parent_id.is_(None), Category.kind == kind)
    else:
        query = query.filter(Category.parent_id == parent.id)

    category = query.first()
    created = category is None

    if category is None:
        if parent is not None and parent.main_tracker_id is not None:
            scope = parent.main_tracker_id
        else:
            scope = None if is_global else tracker.id

        if scope is None:
            _refuse_name_kept_elsewhere(clean_name, kind, parent, tracker, user, db)

        category = Category(
            user_id=user.id,
            name=clean_name,
            kind=parent.kind if parent is not None else kind,
            parent_id=parent.id if parent is not None else None,
            main_tracker_id=scope,
        )
        db.add(category)
        db.flush()

        if parent is not None and scope is None:
            for link in (
                db.query(TrackerCategory).filter(TrackerCategory.category_id == parent.id).all()
            ):
                db.add(
                    TrackerCategory(
                        category_id=category.id,
                        main_tracker_id=link.main_tracker_id,
                        savings_tracker_id=link.savings_tracker_id,
                    )
                )
            db.flush()
    else:
        has_children = (
            db.query(Category.id).filter(Category.parent_id == category.id).first()
            is not None
        )
        if has_children:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=(
                    f"{category.name} has subcategories, so an entry has to be "
                    "filed under one of them"
                ),
            )

    switch_on(category, tracker, db)
    return category, created


def find_or_create_path(
    name: str,
    kind: str,
    parent: Category | None,
    subcategory_name: str | None,
    tracker: MainTracker,
    user: User,
    db: Session,
    is_global: bool = False,
) -> tuple[Category, bool]:
    """The category to file an entry under, given by name, made if need be.

    Without a sub-category this is find_or_create_leaf. With one, the name is
    a top-level category holding that sub-category, as in "Pets › Food": both
    are found or made, and the sub-category is what comes back. is_global
    applies to whichever of the two is made.

    An existing top-level category with no sub-categories of its own is
    refused here. Giving it one would move everything already filed under it,
    which belongs on the Categories screen, where the user can see it happen.

    Returns the category, and whether anything was created just now.
    """
    if subcategory_name is None:
        return find_or_create_leaf(name, kind, parent, tracker, user, db, is_global)

    if parent is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="A sub-category cannot have sub-categories of its own",
        )

    clean_name = name.strip()
    top = (
        db.query(Category)
        .filter(
            Category.user_id == user.id,
            func.lower(Category.name) == clean_name.lower(),
            Category.parent_id.is_(None),
            Category.kind == kind,
            visible_in(tracker),
        )
        .first()
    )
    top_created = top is None

    if top is None:
        if is_global:
            _refuse_name_kept_elsewhere(clean_name, kind, None, tracker, user, db)
        # Switched on only in this tracker, whether it is global or not.
        top = Category(
            user_id=user.id,
            name=clean_name,
            kind=kind,
            main_tracker_id=None if is_global else tracker.id,
        )
        db.add(top)
        db.flush()
        db.add(TrackerCategory(category_id=top.id, main_tracker_id=tracker.id))
        db.flush()
    else:
        has_children = (
            db.query(Category.id).filter(Category.parent_id == top.id).first() is not None
        )
        if not has_children:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=(
                    f"{top.name} is already a category without sub-categories. Choose "
                    "it instead, or give it sub-categories from Categories"
                ),
            )

    leaf, leaf_created = find_or_create_leaf(
        subcategory_name, kind, top, tracker, user, db, is_global
    )
    return leaf, top_created or leaf_created