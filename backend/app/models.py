from datetime import datetime, date
from decimal import Decimal
from typing import Optional

from sqlalchemy import (
    ForeignKey, String, Numeric, Boolean, DateTime, Date, Text, UniqueConstraint, false
)
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.sql import func

from app.database import Base

# What a category is used for. Income and expense categories are kept apart,
# so Salary never appears in the picker for groceries and vice versa.
CATEGORY_EXPENSE = "expense"
CATEGORY_INCOME = "income"

# The catch-all for something that has no category yet. There is one at the
# top level of each kind, and one under any category with subcategories, each
# made the first time an entry is filed there. An entry under Others carries a
# name of its own, such as "Pet food", until it is given a category.
OTHERS_CATEGORY_NAME = "Others"

# What a transaction does to its wallet. Income adds, expense subtracts, and
# an opening balance sets the starting point when a wallet is created. Income
# and expense amounts are always positive; an opening balance may be negative,
# for a wallet such as a credit card that starts out owing money.
TRANSACTION_INCOME = "income"
TRANSACTION_EXPENSE = "expense"
TRANSACTION_OPENING = "opening"
# A transfer between two wallets of one tracker is two rows: what left one
# wallet, and what arrived in the other. Neither is income or an expense, so
# they count towards balances but never towards income, spending or budgets.
TRANSACTION_TRANSFER_OUT = "transfer_out"
TRANSACTION_TRANSFER_IN = "transfer_in"
# Every transfer fee is filed under this expense category, made in a tracker
# the first time one of its transfers has a fee.
TRANSFER_FEE_CATEGORY_NAME = "Transfer Fee"
# A fee's note names the wallet its transfer went to. Renaming that wallet
# renames it in the note too.
TRANSFER_FEE_NOTE = "Fee for a transfer to {}"

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
    # Up to 20 characters, so "transfer_out" fits.
    type: Mapped[str] = mapped_column(String(20))
    description: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    transaction_date: Mapped[date] = mapped_column(Date)
    is_edited_instance: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    # An entry is never changed once recorded, so its history can always be
    # audited. Correcting it adds a reversal that cancels it plus the corrected
    # entry; deleting it adds only the reversal. These three columns tie the
    # rows of one entry's history together.
    #
    # reverses_id: on a reversal, the entry it cancels. Unique, because an
    # entry can only ever be cancelled once.
    reverses_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("transactions.id", ondelete="SET NULL"), nullable=True, unique=True, index=True
    )
    # replaces_id: on a corrected entry, the version it replaced.
    replaces_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("transactions.id", ondelete="SET NULL"), nullable=True, index=True
    )
    # origin_id: on every reversal and correction, the first version of the
    # entry, so a whole history can be fetched at once. Empty on an original.
    origin_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("transactions.id", ondelete="SET NULL"), nullable=True, index=True
    )
    
    # Under an Others category: what the entry actually was, such as "Pet
    # food", until it is given a category of its own. Empty everywhere else.
    other_name: Mapped[Optional[str]] = mapped_column(String(100), nullable=True)
    # On a reversal: why the entry was corrected or deleted, in the user's words.
    # On a restored entry: why it was restored, if a reason was given.
    reason: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    # On the copy that brought a deleted entry back. It replaces the deleted
    # version the same way a correction does, so this is what tells the two apart.
    is_restoration: Mapped[bool] = mapped_column(Boolean, default=False, server_default=false())
    # On the row where a transfer arrived: the row where it left the other
    # wallet. The leaving row stands for the whole transfer in lists and
    # history; this one is corrected, deleted and restored along with it.
    transfer_out_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("transactions.id", ondelete="SET NULL"), nullable=True, unique=True, index=True
    )
    # On a transfer's fee: the row where that transfer left its wallet. The fee
    # is an ordinary expense under Transfer Fee, but it is corrected, deleted
    # and restored only along with its transfer.
    fee_for_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("transactions.id", ondelete="SET NULL"), nullable=True, unique=True, index=True
    )


class BudgetLimit(Base):
    """A category's expected amount per month in one tracker, from a month on.

    Set once, an amount carries over to every later month until the next row
    for the same tracker, category and currency starts. An empty amount from a
    month on means the expected amount was cleared then. Only categories
    without subcategories, and subcategories, have rows; a parent's figure is
    the sum of its subcategories'.
    """

    __tablename__ = "budget_limits"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    main_tracker_id: Mapped[int] = mapped_column(ForeignKey("main_trackers.id"), index=True)
    category_id: Mapped[int] = mapped_column(ForeignKey("categories.id"), index=True)
    # Amounts in different currencies are never added together, so a tracker
    # with peso and dollar wallets keeps a separate expected amount for each.
    currency: Mapped[str] = mapped_column(String(3))
    # Always the first day of a month.
    starts_on: Mapped[date] = mapped_column(Date)
    monthly_limit: Mapped[Optional[Decimal]] = mapped_column(Numeric(12, 2), nullable=True)
    warning_threshold_percent: Mapped[int] = mapped_column(default=80)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    __table_args__ = (
        UniqueConstraint(
            "main_tracker_id",
            "category_id",
            "currency",
            "starts_on",
            name="uq_budget_limits_tracker_category_currency_month",
        ),
    )


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
    

class EmailCode(Base):
    """A short code sent by email to confirm one action, such as a deletion.

    Each code belongs to one action on one record, works once, expires after a
    few minutes and allows only a few wrong tries. Only a bcrypt hash of it is
    stored, the same way PINs are, so reading this table reveals no codes.
    """

    __tablename__ = "email_codes"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    purpose: Mapped[str] = mapped_column(String(30))
    # The record the code is for, such as the transaction it deletes.
    target_id: Mapped[Optional[int]] = mapped_column(nullable=True)
    code_hash: Mapped[str] = mapped_column(String(255))
    attempts: Mapped[int] = mapped_column(default=0, server_default="0")
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    used_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())