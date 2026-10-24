from datetime import datetime, date
from decimal import Decimal
from typing import Optional

from sqlalchemy import (
    ForeignKey, String, Numeric, Boolean, DateTime, Date, Text, UniqueConstraint
)
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.sql import func

from app.database import Base

# What a category is used for. Income and expense categories are kept apart,
# so Salary never appears in the picker for groceries and vice versa.
CATEGORY_EXPENSE = "expense"
CATEGORY_INCOME = "income"

# What a transaction does to its wallet. Income adds, expense subtracts, and
# an opening balance sets the starting point when a wallet is created. Income
# and expense amounts are always positive; an opening balance may be negative,
# for a wallet such as a credit card that starts out owing money.
TRANSACTION_INCOME = "income"
TRANSACTION_EXPENSE = "expense"
TRANSACTION_OPENING = "opening"

class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(primary_key=True)
    email: Mapped[str] = mapped_column(String(255), unique=True, index=True)
    hashed_password: Mapped[str] = mapped_column(String(255))
    hashed_pin: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    google_access_token: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    google_refresh_token: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    google_sheet_id: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    current_session_token: Mapped[Optional[str]] = mapped_column(String(500), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class MainTracker(Base):
    __tablename__ = "main_trackers"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    name: Mapped[str] = mapped_column(String(255))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class SavingsTracker(Base):
    __tablename__ = "savings_trackers"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    name: Mapped[str] = mapped_column(String(255))
    target_amount: Mapped[Optional[Decimal]] = mapped_column(Numeric(12, 2), nullable=True)
    target_date: Mapped[Optional[date]] = mapped_column(Date, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class SavingsTrackerLink(Base):
    __tablename__ = "savings_tracker_links"

    id: Mapped[int] = mapped_column(primary_key=True)
    savings_tracker_id: Mapped[int] = mapped_column(ForeignKey("savings_trackers.id"), index=True)
    main_tracker_id: Mapped[int] = mapped_column(ForeignKey("main_trackers.id"), index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    __table_args__ = (
        UniqueConstraint("savings_tracker_id", "main_tracker_id", name="uq_savings_main_link"),
    )


class Wallet(Base):
    __tablename__ = "wallets"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    main_tracker_id: Mapped[Optional[int]] = mapped_column(ForeignKey("main_trackers.id"), nullable=True, index=True)
    savings_tracker_id: Mapped[Optional[int]] = mapped_column(ForeignKey("savings_trackers.id"), nullable=True, index=True)
    name: Mapped[str] = mapped_column(String(255))
    currency: Mapped[str] = mapped_column(String(3))
    balance: Mapped[Decimal] = mapped_column(Numeric(14, 2), default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class Category(Base):
    __tablename__ = "categories"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    name: Mapped[str] = mapped_column(String(255))
    kind: Mapped[str] = mapped_column(String(10), server_default=CATEGORY_EXPENSE)
    parent_id: Mapped[Optional[int]] = mapped_column(ForeignKey("categories.id"), nullable=True, index=True)
    main_tracker_id: Mapped[Optional[int]] = mapped_column(ForeignKey("main_trackers.id"), nullable=True, index=True)
    savings_tracker_id: Mapped[Optional[int]] = mapped_column(ForeignKey("savings_trackers.id"), nullable=True, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class TrackerCategory(Base):
    __tablename__ = "tracker_categories"

    id: Mapped[int] = mapped_column(primary_key=True)
    category_id: Mapped[int] = mapped_column(ForeignKey("categories.id"), index=True)
    main_tracker_id: Mapped[Optional[int]] = mapped_column(ForeignKey("main_trackers.id"), nullable=True, index=True)
    savings_tracker_id: Mapped[Optional[int]] = mapped_column(ForeignKey("savings_trackers.id"), nullable=True, index=True)


class RecurringRule(Base):
    __tablename__ = "recurring_rules"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    wallet_id: Mapped[int] = mapped_column(ForeignKey("wallets.id"), index=True)
    category_id: Mapped[Optional[int]] = mapped_column(ForeignKey("categories.id"), nullable=True)
    amount: Mapped[Decimal] = mapped_column(Numeric(12, 2))
    type: Mapped[str] = mapped_column(String(10))
    description: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    day_of_month: Mapped[int] = mapped_column()
    start_date: Mapped[date] = mapped_column(Date)
    end_date: Mapped[Optional[date]] = mapped_column(Date, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class Transaction(Base):
    __tablename__ = "transactions"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    wallet_id: Mapped[int] = mapped_column(ForeignKey("wallets.id"), index=True)
    category_id: Mapped[Optional[int]] = mapped_column(ForeignKey("categories.id"), nullable=True, index=True)
    recurring_rule_id: Mapped[Optional[int]] = mapped_column(ForeignKey("recurring_rules.id"), nullable=True, index=True)
    source_main_tracker_id: Mapped[Optional[int]] = mapped_column(ForeignKey("main_trackers.id"), nullable=True)
    amount: Mapped[Decimal] = mapped_column(Numeric(12, 2))
    type: Mapped[str] = mapped_column(String(10))
    description: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    transaction_date: Mapped[date] = mapped_column(Date)
    is_edited_instance: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class BudgetLimit(Base):
    __tablename__ = "budget_limits"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    category_id: Mapped[int] = mapped_column(ForeignKey("categories.id"), index=True)
    monthly_limit: Mapped[Decimal] = mapped_column(Numeric(12, 2))
    warning_threshold_percent: Mapped[int] = mapped_column(default=80)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class ExchangeRate(Base):
    __tablename__ = "exchange_rates"

    id: Mapped[int] = mapped_column(primary_key=True)
    target_currency: Mapped[str] = mapped_column(String(3), unique=True)
    rate_to_php: Mapped[Decimal] = mapped_column(Numeric(12, 6))
    fetched_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class AuthToken(Base):
    """A single-use link sent by email, such as a PIN or password reset.

    Only the SHA-256 hash of the token is stored, the same way session tokens
    are, so someone reading this table cannot use what they find to reset
    anybody's PIN or password.
    """

    __tablename__ = "auth_tokens"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    purpose: Mapped[str] = mapped_column(String(30))
    token_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    used_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())