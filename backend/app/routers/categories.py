from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import func, or_
from sqlalchemy.orm import Session

from app.category_lookup import find_or_create_leaf, find_or_create_path, visible_in
from app.deps import get_current_user, get_db
from app.models import (
    CATEGORY_EXPENSE,
    OTHERS_CATEGORY_NAME,
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
    CategoryFindOrCreateRequest,
    CategoryKind,
    CategoryResponse,
    CategoryUpdateRequest,
    OthersCategoryRequest,
    TrackerCategoryResponse,
)

router = APIRouter(tags=["categories"])

GENERAL_SUBCATEGORY_NAME = "General"


# --------------------------------------------------------------------------
# Shared helpers
# --------------------------------------------------------------------------


def _parents_with_children(user: User, db: Session) -> set[int]:
    """Every category id that has at least one subcategory.

    Fetched once per request rather than asked per row. Building a response
    list would otherwise fire one extra query for every category the user
    owns, which is the classic way a list endpoint gets slow without anyone
    noticing until there is real data in it.
    """
    return {
        row.parent_id
        for row in db.query(Category.parent_id).filter(
            Category.user_id == user.id,
            Category.parent_id.is_not(None),
        )
    }


def _child_ids(
    category_id: int,
    user: User,
    db: Session,
    tracker: MainTracker | None = None,
) -> set[int]:
    """A category's subcategories; with a tracker, only the ones it can see.

    A subcategory made from an Others entry can belong to one tracker even
    under a global parent, so another tracker must never switch it on.
    """
    query = db.query(Category.id).filter(
        Category.user_id == user.id,
        Category.parent_id == category_id,
    )
    if tracker is not None:
        query = query.filter(visible_in(tracker))
    return {row.id for row in query}


def _to_response(category: Category, parents: set[int]) -> CategoryResponse:
    return CategoryResponse(
        id=category.id,
        name=category.name,
        kind=category.kind,
        parent_id=category.parent_id,
        main_tracker_id=category.main_tracker_id,
        is_global=category.main_tracker_id is None,
        has_children=category.id in parents,
        created_at=category.created_at,
    )


def _reject_duplicate_name(
    name: str,
    kind: str,
    parent_id: int | None,
    scope_tracker_id: int | None,
    user: User,
    db: Session,
    exclude_id: int | None = None,
) -> None:
    """Names must differ only from the names they could sit beside.

    Two categories clash when a user could see both at once and not tell them
    apart. A global category appears in every tracker, so it must differ from
    everything. Two categories private to different trackers never appear
    together, so they may share a name — and must be allowed to, because
    otherwise a name is refused by a category the user cannot see from where
    they are standing, with no way to find out why.

    Subcategories are compared against their siblings only, since the picker
    shows the full path and "Bills > Electricity" is never mistaken for
    "Office > Electricity".

    Income and expense categories never share a picker either, so an income
    "Other" and an expense "Other" can both exist.
    """
    query = db.query(Category).filter(
        Category.user_id == user.id,
        func.lower(Category.name) == name.lower(),
    )

    if parent_id is None:
        query = query.filter(Category.parent_id.is_(None), Category.kind == kind)
        if scope_tracker_id is not None:
            query = query.filter(
                or_(
                    Category.main_tracker_id.is_(None),
                    Category.main_tracker_id == scope_tracker_id,
                )
            )
    else:
        query = query.filter(Category.parent_id == parent_id)
        if scope_tracker_id is not None:
            query = query.filter(
                or_(
                    Category.main_tracker_id.is_(None),
                    Category.main_tracker_id == scope_tracker_id,
                )
            )

    if exclude_id is not None:
        query = query.filter(Category.id != exclude_id)

    clash = query.first()
    if clash is not None:
        if clash.main_tracker_id is not None and clash.main_tracker_id != scope_tracker_id:
            detail = "One of your trackers already has its own category with this name"
        elif parent_id is not None:
            detail = "You already have a subcategory with this name here"
        else:
            detail = "You already have a category with this name"
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=detail)


def _reject_wrong_scope(category: Category, tracker: MainTracker) -> None:
    if category.main_tracker_id is not None and category.main_tracker_id != tracker.id:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This category is private to a different tracker",
        )


def _enabled_ids(tracker: MainTracker, db: Session) -> set[int]:
    return {
        row.category_id
        for row in db.query(TrackerCategory.category_id).filter(
            TrackerCategory.main_tracker_id == tracker.id
        )
    }


def _set_links(
    category_ids: set[int],
    tracker: MainTracker,
    db: Session,
    enabled: bool,
) -> None:
    """Switch a set of categories on or off for one tracker.

    Both directions are idempotent: switching on what is already on, or off
    what is already off, changes nothing and still succeeds. A double tap on a
    slow connection should never produce an error the user has to interpret.
    """
    if not category_ids:
        return

    existing = {
        link.category_id: link
        for link in db.query(TrackerCategory)
        .filter(
            TrackerCategory.main_tracker_id == tracker.id,
            TrackerCategory.category_id.in_(category_ids),
        )
        .all()
    }

    if enabled:
        for category_id in category_ids:
            if category_id not in existing:
                db.add(
                    TrackerCategory(
                        category_id=category_id,
                        main_tracker_id=tracker.id,
                    )
                )
    else:
        for link in existing.values():
            db.delete(link)


def _rehome_direct_records(parent: Category, user: User, db: Session) -> None:
    """Move anything filed directly on a category about to become a parent.

    Transactions attach to leaves only. The moment a category gains its first
    subcategory it stops being a leaf, and anything already filed directly on
    it would become unreachable — visible in no subcategory, yet counted in
    the parent's total, so the parent would no longer equal the sum of its
    children and reports would quietly disagree with themselves.

    Creating a "General" child and moving those records into it keeps every
    record filed, every total identical, and needs nothing from the user.
    """
    already_has_children = (
        db.query(Category.id).filter(Category.parent_id == parent.id).first() is not None
    )
    if already_has_children:
        return

    has_transactions = (
        db.query(Transaction.id)
        .filter(Transaction.category_id == parent.id)
        .first()
        is not None
    )
    has_rules = (
        db.query(RecurringRule.id)
        .filter(RecurringRule.category_id == parent.id)
        .first()
        is not None
    )
    if not has_transactions and not has_rules:
        return

    general = Category(
        user_id=user.id,
        name=GENERAL_SUBCATEGORY_NAME,
        kind=parent.kind,
        parent_id=parent.id,
        main_tracker_id=parent.main_tracker_id,
    )
    db.add(general)
    db.flush()

    # Switched on wherever the parent already was, so the moved records stay
    # visible in exactly the trackers they were visible in before.
    parent_links = (
        db.query(TrackerCategory)
        .filter(TrackerCategory.category_id == parent.id)
        .all()
    )
    for link in parent_links:
        db.add(
            TrackerCategory(
                category_id=general.id,
                main_tracker_id=link.main_tracker_id,
                savings_tracker_id=link.savings_tracker_id,
            )
        )

    db.query(Transaction).filter(Transaction.category_id == parent.id).update(
        {Transaction.category_id: general.id}, synchronize_session=False
    )
    db.query(RecurringRule).filter(RecurringRule.category_id == parent.id).update(
        {RecurringRule.category_id: general.id}, synchronize_session=False
    )

    # A budget limit on the parent deliberately stays where it is: a limit on
    # "Bills" is meant to cover the whole family, not follow the records down
    # into one child.

def _resolve_link_targets(
    tracker_ids: list[int] | None,
    user: User,
    db: Session,
) -> list[MainTracker]:
    """Work out which trackers a new global category switches on in.

    Three distinct meanings, deliberately kept apart. None is "the client
    didn't say", and falls back to every tracker — the original behaviour, so
    anything already calling this endpoint keeps working. An empty list is "the
    user looked at the trackers and picked none", which is a real choice and is
    honoured. Collapsing those two together would mean a failed tracker load
    silently produced a category switched on nowhere.
    """
    if tracker_ids is None:
        return db.query(MainTracker).filter(MainTracker.user_id == user.id).all()

    if not tracker_ids:
        return []

    requested = set(tracker_ids)
    targets = (
        db.query(MainTracker)
        .filter(MainTracker.user_id == user.id, MainTracker.id.in_(requested))
        .all()
    )

    if len(targets) != len(requested):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="One or more of those trackers could not be found",
        )

    return targets

def _link_new_category(
    category: Category,
    parent: Category | None,
    link_targets: list[MainTracker],
    db: Session,
) -> None:
    """Switch a newly created category on wherever it belongs.

    A subcategory ignores link_targets entirely and mirrors its parent. Its
    switches are not its own to set: a child visible where its parent is not
    would appear in a picker with no heading above it.
    """
    if parent is not None:
        parent_links = (
            db.query(TrackerCategory)
            .filter(TrackerCategory.category_id == parent.id)
            .all()
        )
        for link in parent_links:
            db.add(
                TrackerCategory(
                    category_id=category.id,
                    main_tracker_id=link.main_tracker_id,
                    savings_tracker_id=link.savings_tracker_id,
                )
            )
        return

    for target in link_targets:
        db.add(
            TrackerCategory(
                category_id=category.id,
                main_tracker_id=target.id,
            )
        )


# --------------------------------------------------------------------------
# Account-wide category endpoints
# --------------------------------------------------------------------------


@router.post(
    "/categories",
    response_model=CategoryResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Create a category or subcategory",
)
def create_category(
    payload: CategoryCreateRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> CategoryResponse:
    parent: Category | None = None

    if payload.parent_id is not None:
        parent = get_owned_category(payload.parent_id, user, db)

        if parent.parent_id is not None or payload.subcategory_names:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="Subcategories cannot have subcategories of their own",
            )

        if (
            payload.main_tracker_id is not None
            and payload.main_tracker_id != parent.main_tracker_id
        ):
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="A subcategory always has the same scope as its parent",
            )

        if payload.kind is not None and payload.kind != parent.kind:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="A subcategory is always the same kind as its parent",
            )

    kind = parent.kind if parent is not None else (payload.kind or CATEGORY_EXPENSE)

    scope_tracker_id = (
        parent.main_tracker_id if parent is not None else payload.main_tracker_id
    )

    tracker: MainTracker | None = None
    if scope_tracker_id is not None:
        tracker = get_owned_tracker(scope_tracker_id, user, db)

    if payload.tracker_ids is not None:
        if parent is not None:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="A subcategory is switched on wherever its parent is",
            )
        if tracker is not None:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="A private category already belongs to one tracker",
            )

    _reject_duplicate_name(payload.name, kind, payload.parent_id, scope_tracker_id, user, db)

    if parent is not None:
        link_targets: list[MainTracker] = []
    elif tracker is not None:
        link_targets = [tracker]
    else:
        link_targets = _resolve_link_targets(payload.tracker_ids, user, db)

    if parent is not None:
        _rehome_direct_records(parent, user, db)

    category = Category(
        user_id=user.id,
        name=payload.name,
        kind=kind,
        parent_id=parent.id if parent is not None else None,
        main_tracker_id=tracker.id if tracker is not None else None,
    )
    db.add(category)
    db.flush()

    _link_new_category(category, parent, link_targets, db)

    # Children created alongside a brand-new parent share its scope and are
    # switched on in exactly the trackers it is. Nothing is committed until all
    # of them are in, so a failure part-way leaves no half-built family behind.
    for child_name in payload.subcategory_names or []:
        child = Category(
            user_id=user.id,
            name=child_name,
            kind=kind,
            parent_id=category.id,
            main_tracker_id=category.main_tracker_id,
        )
        db.add(child)
        db.flush()

        for target in link_targets:
            db.add(TrackerCategory(category_id=child.id, main_tracker_id=target.id))

    db.commit()
    db.refresh(category)

    return _to_response(category, _parents_with_children(user, db))


@router.get(
    "/categories",
    response_model=list[CategoryResponse],
    summary="List every category on your account",
)
def list_categories(
    kind: CategoryKind | None = None,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> list[CategoryResponse]:
    query = db.query(Category).filter(Category.user_id == user.id)
    if kind is not None:
        query = query.filter(Category.kind == kind)

    categories = query.order_by(Category.name).all()

    parents = _parents_with_children(user, db)

    return [_to_response(category, parents) for category in categories]


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
    _reject_duplicate_name(
        payload.name,
        category.kind,
        category.parent_id,
        category.main_tracker_id,
        user,
        db,
        exclude_id=category.id,
    )

    category.name = payload.name
    db.commit()
    db.refresh(category)

    return _to_response(category, _parents_with_children(user, db))


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

    # Subcategories are not swept away with their parent. Deleting "Bills"
    # should not silently take "Electricity" and "Water" with it — if those
    # are genuinely unwanted, deleting them first makes that explicit.
    if db.query(Category.id).filter(Category.parent_id == category.id).first():
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Delete this category's subcategories before deleting it",
        )

    # Deleting a category that transactions already reference would either
    # destroy those records or leave them pointing at nothing. Turning the
    # category off in each tracker is the non-destructive alternative, and it
    # is what people almost always actually want.
    if db.query(Transaction.id).filter(Transaction.category_id == category.id).first():
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            # Deleted and corrected entries are kept as history, and still use it.
            detail=(
                "Transactions use this category, including deleted or corrected ones "
                "kept as history, so it cannot be deleted. Switch it off instead"
            ),
        )

    if db.query(RecurringRule.id).filter(RecurringRule.category_id == category.id).first():
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="A recurring rule uses this category, so it cannot be deleted",
        )

    if db.query(BudgetLimit.id).filter(BudgetLimit.category_id == category.id).first():
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Remove this category's budget limit before deleting it",
        )

    db.query(TrackerCategory).filter(
        TrackerCategory.category_id == category.id
    ).delete()
    db.delete(category)
    db.commit()


# --------------------------------------------------------------------------
# Per-tracker category endpoints
# --------------------------------------------------------------------------


@router.get(
    "/trackers/{tracker_id}/categories",
    response_model=list[TrackerCategoryResponse],
    summary="List the categories a tracker can use",
)
def list_tracker_categories(
    tracker_id: int,
    only_enabled: bool = False,
    kind: CategoryKind | None = None,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> list[TrackerCategoryResponse]:
    tracker = get_owned_tracker(tracker_id, user, db)

    query = db.query(Category).filter(
        Category.user_id == user.id,
        or_(
            Category.main_tracker_id.is_(None),
            Category.main_tracker_id == tracker.id,
        ),
    )
    if kind is not None:
        query = query.filter(Category.kind == kind)

    applicable = query.order_by(Category.name).all()

    enabled_ids = _enabled_ids(tracker, db)
    parents = _parents_with_children(user, db)

    results = [
        TrackerCategoryResponse(
            id=category.id,
            name=category.name,
            kind=category.kind,
            parent_id=category.parent_id,
            is_global=category.main_tracker_id is None,
            is_enabled=category.id in enabled_ids,
            has_children=category.id in parents,
        )
        for category in applicable
    ]

    if only_enabled:
        results = [result for result in results if result.is_enabled]

    return results

@router.post(
    "/trackers/{tracker_id}/categories/others",
    response_model=TrackerCategoryResponse,
    summary="The tracker's Others category, made the first time it is needed",
)
def others_category(
    tracker_id: int,
    payload: OthersCategoryRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> TrackerCategoryResponse:
    """Others is offered in every picker, at the top level and under every
    category with subcategories, but only made once something is filed there,
    so the category lists never fill up with empty buckets.

    Asking again returns the same category, switched on in this tracker.
    """
    tracker = get_owned_tracker(tracker_id, user, db)

    parent: Category | None = None
    if payload.parent_id is not None:
        parent = get_owned_category(payload.parent_id, user, db)
        _reject_wrong_scope(parent, tracker)

        if parent.parent_id is not None:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="Others goes under a top-level category, not under a subcategory",
            )
        if not _child_ids(parent.id, user, db, tracker):
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="This category has no subcategories. Choose Others at the top level instead",
            )

    others, _ = find_or_create_leaf(OTHERS_CATEGORY_NAME, payload.kind, parent, tracker, user, db)
    db.commit()
    db.refresh(others)

    return TrackerCategoryResponse(
        id=others.id,
        name=others.name,
        kind=others.kind,
        parent_id=others.parent_id,
        is_global=others.main_tracker_id is None,
        is_enabled=True,
        has_children=False,
    )

@router.post(
    "/trackers/{tracker_id}/categories/find-or-create",
    response_model=TrackerCategoryResponse,
    summary="The category to file an entry under, made if it does not exist yet",
)
def find_or_create_category(
    tracker_id: int,
    payload: CategoryFindOrCreateRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> TrackerCategoryResponse:
    """For a category typed in while adding an entry, instead of filing it
    under Others. Asking for a name that already exists, in any capitals,
    returns that category instead of making a second one, switched on in this
    tracker.

    At the top level the name can be given a sub-category, as in "Pets › Food";
    the sub-category is what comes back. With parent_id, the name becomes one
    of that category's sub-categories.
    """
    tracker = get_owned_tracker(tracker_id, user, db)

    parent: Category | None = None
    if payload.parent_id is not None:
        parent = get_owned_category(payload.parent_id, user, db)
        _reject_wrong_scope(parent, tracker)

        if parent.parent_id is not None:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="A sub-category cannot have sub-categories of its own",
            )
        # A first sub-category would move everything filed directly under the
        # parent, which belongs on the Categories screen, not in the middle of
        # adding an entry.
        if not _child_ids(parent.id, user, db, tracker):
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="This category has no sub-categories. Add its first one from Categories",
            )

    category, _ = find_or_create_path(
        payload.name,
        payload.kind,
        parent,
        payload.subcategory_name,
        tracker,
        user,
        db,
        payload.is_global,
        payload.subcategory_is_global,
    )
    db.commit()
    db.refresh(category)

    return TrackerCategoryResponse(
        id=category.id,
        name=category.name,
        kind=category.kind,
        parent_id=category.parent_id,
        is_global=category.main_tracker_id is None,
        is_enabled=True,
        has_children=False,
    )

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
    _reject_wrong_scope(category, tracker)

    # Switching a parent on brings its children with it; switching a child on
    # brings its parent. Either way the tracker ends up with a parent that has
    # at least one choosable subcategory under it. A parent cannot be chosen
    # directly, so one left standing alone would be a dead end in the picker.
    ids = {category.id}
    if category.parent_id is not None:
        ids.add(category.parent_id)
    else:
        ids.update(_child_ids(category.id, user, db, tracker))

    _set_links(ids, tracker, db, enabled=True)
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
    _reject_wrong_scope(category, tracker)

    ids = {category.id}

    if category.parent_id is None:
        ids.update(_child_ids(category.id, user, db, tracker))
    else:
        # Switching off the last subcategory that was still on takes the parent
        # with it, for the same reason as above: a parent with nothing
        # choosable beneath it is a dead end rather than a useful heading.
        siblings = _child_ids(category.parent_id, user, db, tracker)
        still_enabled = _enabled_ids(tracker, db) & siblings
        still_enabled.discard(category.id)
        if not still_enabled:
            ids.add(category.parent_id)

    _set_links(ids, tracker, db, enabled=False)
    db.commit()