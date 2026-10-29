"""The Budget tab: each category's expected amount against what actually went
out or came in over a range of dates.

An expected amount is per month and belongs to one tracker. Set from a month
on, it carries over to every later month until it is changed again, so a change
never rewrites the months before it. Amounts are kept per currency, since
pesos and dollars are never added together.

Only categories without subcategories, and subcategories, hold amounts of
their own. A parent's expected and actual figures are its subcategories' added
up, the same way its transactions are.
"""

import calendar
from datetime import date, timedelta
from decimal import ROUND_HALF_UP, Decimal

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import func
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.orm import Session

from app.category_lookup import is_others, visible_in
from app.deps import get_current_user, get_db
from app.models import (
    CATEGORY_EXPENSE,
    CATEGORY_INCOME,
    TRANSACTION_EXPENSE,
    TRANSACTION_INCOME,
    BudgetLimit,
    Category,
    MainTracker,
    TrackerCategory,
    Transaction,
    User,
    Wallet,
)
from app.ownership import get_owned_category, get_owned_tracker
from app.schemas import (
    BudgetResponse,
    BudgetRowResponse,
    BudgetTableResponse,
    BudgetTotalResponse,
    ExpectedAmountRequest,
    ExpectedAmountResponse,
    format_money,
)

router = APIRouter(tags=["budget"])

CENT = Decimal("0.01")
ZERO = Decimal("0")


# --------------------------------------------------------------------------
# Working out the figures
# --------------------------------------------------------------------------


def _months(start: date, end: date):
    """The first day of every month the dates touch, in order."""
    month = start.replace(day=1)
    while month <= end:
        yield month
        month = (month.replace(day=28) + timedelta(days=4)).replace(day=1)


def _amount_in(changes: list[BudgetLimit], month: date) -> Decimal | None:
    """The monthly amount in force in a month: the latest change made from
    that month or earlier. The changes are oldest first."""
    amount = None
    for change in changes:
        if change.starts_on > month:
            break
        amount = change.monthly_limit
    return amount


def expected_over(changes: list[BudgetLimit], start: date, end: date) -> Decimal | None:
    """The expected amount for a range of dates.

    Every month the dates touch adds its monthly amount, shared out by the
    days of that month they cover: a week of a 31-day month adds 7/31 of it,
    and a whole month all of it. A month without an amount adds nothing, and
    with no amount in any month there is no expected amount at all.
    """
    total = None
    for month in _months(start, end):
        amount = _amount_in(changes, month)
        if amount is None:
            continue
        days_in_month = calendar.monthrange(month.year, month.month)[1]
        last_day = month.replace(day=days_in_month)
        days_covered = (min(end, last_day) - max(start, month)).days + 1
        total = (total or ZERO) + amount * days_covered / days_in_month
    return None if total is None else total.quantize(CENT, rounding=ROUND_HALF_UP)


def _difference(kind: str, expected: Decimal | None, actual: Decimal) -> Decimal | None:
    """Below zero is always the bad side: spent more than expected, or
    received less than expected."""
    if expected is None:
        return None
    return expected - actual if kind == CATEGORY_EXPENSE else actual - expected


def _money_or_none(value: Decimal | None) -> str | None:
    return None if value is None else format_money(value)


def _sum_expected(values: list[Decimal | None]) -> Decimal | None:
    """Added up from figures already rounded, so every table adds up exactly."""
    present = [value for value in values if value is not None]
    return sum(present, ZERO) if present else None


def _currencies(tracker: MainTracker, db: Session) -> list[str]:
    """The currencies the tracker's wallets use, the most used first, then
    alphabetically."""
    rows = (
        db.query(Wallet.currency, func.count(Wallet.id))
        .filter(Wallet.main_tracker_id == tracker.id)
        .group_by(Wallet.currency)
        .all()
    )
    return [currency for currency, _ in sorted(rows, key=lambda row: (-row[1], row[0]))]


def _sort_key(category: Category):
    """By name, with Others last, the same order as the pickers."""
    return (is_others(category), category.name.lower())


class _Figures:
    """What the tables are built from: one tracker, one currency, one range."""

    def __init__(
        self,
        kind: str,
        categories: list[Category],
        parent_ids: set[int],
        enabled: set[int],
        actuals: dict[int, Decimal],
        changes: dict[int, list[BudgetLimit]],
        start: date,
        end: date,
    ):
        self.kind = kind
        self.categories = categories
        self.parent_ids = parent_ids
        self.enabled = enabled
        self.actuals = actuals
        self.changes = changes
        self.start = start
        self.end = end

    def leaf_row(self, category: Category) -> BudgetRowResponse | None:
        """A category that holds entries. Left out when it is switched off and
        nothing was filed under it in the dates, so the tab shows what the
        tracker uses without losing money that was actually spent."""
        actual = Decimal(self.actuals.get(category.id, ZERO))
        if category.id not in self.enabled and actual == 0:
            return None

        changes = self.changes.get(category.id, [])
        expected = expected_over(changes, self.start, self.end)
        return BudgetRowResponse(
            category_id=category.id,
            name=category.name,
            is_enabled=category.id in self.enabled,
            expected=_money_or_none(expected),
            actual=format_money(actual),
            difference=_money_or_none(_difference(self.kind, expected, actual)),
            amounts=[
                ExpectedAmountResponse(
                    category_id=category.id,
                    currency=change.currency,
                    starts_on=change.starts_on,
                    amount=_money_or_none(change.monthly_limit),
                )
                for change in changes
            ],
        )

    def parent_row(self, parent: Category) -> BudgetRowResponse | None:
        """A category with subcategories, its figures theirs added up. Left
        out when none of its subcategories is shown."""
        children = sorted(
            (category for category in self.categories if category.parent_id == parent.id),
            key=_sort_key,
        )
        rows = [row for row in (self.leaf_row(child) for child in children) if row is not None]
        if not rows:
            return None

        expected = _sum_expected([_decimal(row.expected) for row in rows])
        actual = sum((Decimal(row.actual) for row in rows), ZERO)
        return BudgetRowResponse(
            category_id=parent.id,
            name=parent.name,
            is_enabled=parent.id in self.enabled,
            expected=_money_or_none(expected),
            actual=format_money(actual),
            difference=_money_or_none(_difference(self.kind, expected, actual)),
            subcategories=rows,
        )

    def table(self) -> BudgetTableResponse:
        tops = sorted(
            (
                category
                for category in self.categories
                if category.parent_id is None and category.kind == self.kind
            ),
            key=_sort_key,
        )
        rows = []
        for top in tops:
            row = self.parent_row(top) if top.id in self.parent_ids else self.leaf_row(top)
            if row is not None:
                rows.append(row)

        expected = _sum_expected([_decimal(row.expected) for row in rows])
        actual = sum((Decimal(row.actual) for row in rows), ZERO)
        return BudgetTableResponse(
            kind=self.kind,
            rows=rows,
            total=BudgetTotalResponse(
                expected=_money_or_none(expected),
                actual=format_money(actual),
                difference=_money_or_none(_difference(self.kind, expected, actual)),
            ),
        )


def _decimal(value: str | None) -> Decimal | None:
    return None if value is None else Decimal(value)


# --------------------------------------------------------------------------
# Endpoints
# --------------------------------------------------------------------------


@router.get(
    "/trackers/{tracker_id}/budget",
    response_model=BudgetResponse,
    summary="Expected and actual amounts per category over a range of dates",
    description=(
        "One table for expense categories and one for income categories, in one "
        "currency. Leave the currency out, or name one the tracker's wallets do "
        "not use, to get the one most of its wallets use. Actual amounts include "
        "every correction, so deleted entries count for nothing."
    ),
)
def tracker_budget(
    tracker_id: int,
    start_date: date,
    end_date: date,
    currency: str | None = None,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> BudgetResponse:
    if start_date > end_date:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="The start date must be on or before the end date",
        )
    tracker = get_owned_tracker(tracker_id, user, db)

    currencies = _currencies(tracker, db)
    wanted = currency.upper() if currency else None
    chosen = wanted if wanted in currencies else (currencies[0] if currencies else None)

    categories = (
        db.query(Category)
        .filter(Category.user_id == user.id, visible_in(tracker))
        .all()
    )
    # Every category with children, including children this tracker cannot
    # see: such a parent is a heading, never a place to file entries.
    parent_ids = {
        row.parent_id
        for row in db.query(Category.parent_id)
        .filter(Category.user_id == user.id, Category.parent_id.is_not(None))
        .distinct()
    }
    enabled = {
        row.category_id
        for row in db.query(TrackerCategory.category_id).filter(
            TrackerCategory.main_tracker_id == tracker.id
        )
    }

    actuals: dict[int, Decimal] = {}
    changes: dict[int, list[BudgetLimit]] = {}
    if chosen is not None:
        # Reversals carry the amount negated, so corrected and deleted entries
        # net out by themselves.
        actuals = {
            category_id: Decimal(total)
            for category_id, total in db.query(Transaction.category_id, func.sum(Transaction.amount))
            .join(Wallet, Transaction.wallet_id == Wallet.id)
            .filter(
                Wallet.main_tracker_id == tracker.id,
                Wallet.currency == chosen,
                Transaction.type.in_((TRANSACTION_EXPENSE, TRANSACTION_INCOME)),
                Transaction.category_id.is_not(None),
                Transaction.transaction_date >= start_date,
                Transaction.transaction_date <= end_date,
            )
            .group_by(Transaction.category_id)
        }
        for change in (
            db.query(BudgetLimit)
            .filter(BudgetLimit.main_tracker_id == tracker.id, BudgetLimit.currency == chosen)
            .order_by(BudgetLimit.starts_on)
        ):
            changes.setdefault(change.category_id, []).append(change)

    def table(kind: str) -> BudgetTableResponse:
        return _Figures(
            kind, categories, parent_ids, enabled, actuals, changes, start_date, end_date
        ).table()

    return BudgetResponse(
        start_date=start_date,
        end_date=end_date,
        currency=chosen,
        currencies=currencies,
        expense=table(CATEGORY_EXPENSE),
        income=table(CATEGORY_INCOME),
    )


@router.put(
    "/trackers/{tracker_id}/budget/{category_id}",
    response_model=ExpectedAmountResponse,
    summary="Set a category's expected amount per month, from a month on",
    description=(
        "The amount applies from the month of starts_on until a later change, "
        "so earlier months keep theirs. Leave the amount out to clear it from "
        "that month on. Only a category without subcategories, or a "
        "subcategory, can have one."
    ),
)
def set_expected_amount(
    tracker_id: int,
    category_id: int,
    payload: ExpectedAmountRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> ExpectedAmountResponse:
    tracker = get_owned_tracker(tracker_id, user, db)
    category = get_owned_category(category_id, user, db)

    if category.main_tracker_id is not None and category.main_tracker_id != tracker.id:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This category is private to a different tracker",
        )
    if db.query(Category.id).filter(Category.parent_id == category.id).first():
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"{category.name} has subcategories, so its expected amount is "
                "theirs added up. Set one for each subcategory instead"
            ),
        )
    if payload.currency not in _currencies(tracker, db):
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"None of this tracker's wallets is in {payload.currency}",
        )

    # One row per tracker, category, currency and month: setting the same
    # month again replaces its amount, even when two requests arrive at once.
    statement = insert(BudgetLimit).values(
        user_id=user.id,
        main_tracker_id=tracker.id,
        category_id=category.id,
        currency=payload.currency,
        starts_on=payload.starts_on,
        monthly_limit=payload.amount,
    )
    db.execute(
        statement.on_conflict_do_update(
            constraint="uq_budget_limits_tracker_category_currency_month",
            set_={"monthly_limit": statement.excluded.monthly_limit},
        )
    )
    db.commit()

    return ExpectedAmountResponse(
        category_id=category.id,
        currency=payload.currency,
        starts_on=payload.starts_on,
        amount=_money_or_none(payload.amount),
    )