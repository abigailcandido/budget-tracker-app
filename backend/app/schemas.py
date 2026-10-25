from datetime import date, datetime, timedelta
from decimal import Decimal
from typing import Literal

from pydantic import BaseModel, ConfigDict, EmailStr, Field, field_validator

BCRYPT_MAX_PASSWORD_BYTES = 72
PIN_PATTERN = r"^\d{6}$"
CURRENCY_PATTERN = r"^[A-Za-z]{3}$"
MONEY_DECIMAL_PLACES = 2
# Matches the transactions table's amount column: up to 9,999,999,999.99.
# Wallet balances are stored with two more digits, since they are sums.
AMOUNT_MAX_DIGITS = 12

CategoryKind = Literal["expense", "income"]
TransactionKind = Literal["expense", "income"]


def format_money(value: Decimal) -> str:
    """Render a stored amount as a fixed two-decimal string for the API."""
    return f"{value:.2f}"


def _reject_future_date(value: date | None) -> date | None:
    """Allow today and earlier, with one day of slack for time zones.

    The phone sends its own local date. The Philippines is eight hours ahead of
    the server's clock, so "today" on the phone can already be tomorrow on the
    server; one day of slack keeps that from being refused. Anything further
    ahead is a mistake, and planned future spending belongs to recurring rules.
    """
    if value is not None and value > date.today() + timedelta(days=1):
        raise ValueError("The date can't be in the future")
    return value


def _clean_description(value: str | None) -> str | None:
    if value is None:
        return None
    cleaned = value.strip()
    return cleaned or None

class _NewPasswordRequest(BaseModel):
    """Shared rules for any request that chooses a new password."""

    password: str = Field(min_length=8, max_length=BCRYPT_MAX_PASSWORD_BYTES)

    @field_validator("password")
    @classmethod
    def password_must_fit_bcrypt(cls, value: str) -> str:
        if len(value.encode("utf-8")) > BCRYPT_MAX_PASSWORD_BYTES:
            raise ValueError(
                f"Password must be at most {BCRYPT_MAX_PASSWORD_BYTES} bytes long"
            )
        return value


class RegisterRequest(_NewPasswordRequest):
    email: EmailStr


class LoginRequest(BaseModel):
    email: EmailStr
    password: str


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"


class UserResponse(BaseModel):
    id: int
    email: EmailStr
    has_pin: bool
    created_at: datetime


class SetPinRequest(BaseModel):
    password: str
    pin: str = Field(pattern=PIN_PATTERN)


class VerifyPinRequest(BaseModel):
    pin: str = Field(pattern=PIN_PATTERN)


class PasswordResetEmailRequest(BaseModel):
    email: EmailStr


class PinResetRequest(BaseModel):
    token: str = Field(min_length=1, max_length=200)
    pin: str = Field(pattern=PIN_PATTERN)


class PasswordResetRequest(_NewPasswordRequest):
    token: str = Field(min_length=1, max_length=200)


class MessageResponse(BaseModel):
    detail: str


class _NamedRequest(BaseModel):
    """Shared name handling for anything the user names.

    A length constraint alone would accept a name of three spaces, which is
    blank to a reader but not to a validator. Stripping first, then rejecting
    what is left if it is empty, is what actually enforces "must have a name".
    """

    name: str = Field(min_length=1, max_length=255)

    @field_validator("name")
    @classmethod
    def strip_and_require_name(cls, value: str) -> str:
        cleaned = value.strip()
        if not cleaned:
            raise ValueError("Name must not be blank")
        return cleaned


class TrackerCreateRequest(_NamedRequest):
    category_ids: list[int] | None = Field(
        default=None,
        description=(
            "Which global categories this tracker starts with. Leave it out "
            "entirely to start with all of them. Send an empty list to start "
            "with none."
        ),
    )

    model_config = ConfigDict(
        json_schema_extra={"example": {"name": "My money"}},
    )


class TrackerUpdateRequest(_NamedRequest):
    pass


class TrackerResponse(BaseModel):
    id: int
    name: str
    created_at: datetime


class WalletCreateRequest(_NamedRequest):
    currency: str = Field(pattern=CURRENCY_PATTERN)
    opening_balance: Decimal = Field(
        default=Decimal("0"),
        max_digits=AMOUNT_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
    )
    opening_date: date | None = Field(
        default=None,
        description=(
            "The date to record the opening balance on, normally today on the "
            "phone. Leave it out to use today's date on the server."
        ),
    )

    @field_validator("currency")
    @classmethod
    def normalise_currency(cls, value: str) -> str:
        return value.strip().upper()

    @field_validator("opening_date")
    @classmethod
    def opening_date_not_in_future(cls, value: date | None) -> date | None:
        return _reject_future_date(value)


class WalletUpdateRequest(_NamedRequest):
    """Only the name can change.

    Currency is deliberately absent. Changing it would silently reinterpret
    every amount already recorded in that wallet — a balance of 5,000 pesos
    becoming 5,000 dollars without a single transaction being edited. If a
    wallet is in the wrong currency, the honest fix is a new wallet.
    """


class WalletResponse(BaseModel):
    id: int
    main_tracker_id: int | None
    name: str
    currency: str
    balance: str
    created_at: datetime


class CategoryCreateRequest(_NamedRequest):
    kind: CategoryKind | None = Field(
        default=None,
        description=(
            "Whether this is an expense or an income category. Leave it out for "
            "an expense category. A subcategory always takes its parent's kind, "
            "so this can be left out when parent_id is set."
        ),
    )
    parent_id: int | None = Field(
        default=None,
        ge=1,
        description=(
            "Leave this out for a top-level category. Supply a category id to "
            "create a subcategory underneath it. A subcategory always takes "
            "its parent's scope, so main_tracker_id is ignored when this is set."
        ),
    )
    main_tracker_id: int | None = Field(
        default=None,
        ge=1,
        description=(
            "Leave this out entirely for a category available across every "
            "tracker. Supply a tracker id to make it private to that tracker."
        ),
    )
    tracker_ids: list[int] | None = Field(
        default=None,
        description=(
            "Which trackers this new global category starts switched on in. "
            "Leave it out to switch it on everywhere; send an empty list to "
            "switch it on nowhere. Only meaningful for a top-level global "
            "category — a private one already belongs to a single tracker, "
            "and a subcategory follows its parent."
        ),
    )
    subcategory_names: list[str] | None = Field(
        default=None,
        description=(
            "Subcategories to create underneath this new category in the same "
            "request, so the parent and its children appear together or not "
            "at all. Only valid for a new top-level category."
        ),
    )

    model_config = ConfigDict(
        json_schema_extra={"example": {"name": "Groceries"}},
    )

    @field_validator("subcategory_names")
    @classmethod
    def clean_subcategory_names(cls, value: list[str] | None) -> list[str] | None:
        if value is None:
            return None

        cleaned = [name.strip() for name in value]

        if any(not name for name in cleaned):
            raise ValueError("Subcategory names must not be blank")

        if any(len(name) > 255 for name in cleaned):
            raise ValueError("Subcategory names must be at most 255 characters")

        lowered = [name.lower() for name in cleaned]
        if len(set(lowered)) != len(lowered):
            raise ValueError("Each subcategory needs a different name")

        return cleaned


class CategoryUpdateRequest(_NamedRequest):
    """Only the name can change.

    Neither a category's scope nor its place in the hierarchy can be edited.
    Moving a category between parents would silently change which totals every
    past transaction filed under it contributes to, and reports covering
    months already closed would quietly start disagreeing with what the user
    saw at the time.
    """


class CategoryResponse(BaseModel):
    id: int
    name: str
    kind: str
    parent_id: int | None
    main_tracker_id: int | None
    is_global: bool
    # Whether anything sits underneath this category. The client needs it to
    # know that this row is a heading rather than something choosable: once a
    # category has children, transactions must go to one of them.
    has_children: bool
    created_at: datetime


class TrackerCategoryResponse(BaseModel):
    """A category as it appears from inside one particular tracker."""

    id: int
    name: str
    kind: str
    parent_id: int | None
    is_global: bool
    is_enabled: bool
    has_children: bool


class TransactionCreateRequest(BaseModel):
    wallet_id: int = Field(ge=1)
    type: TransactionKind
    amount: Decimal = Field(
        gt=0,
        max_digits=AMOUNT_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
        description="Always positive. Whether it adds or subtracts comes from type.",
    )
    category_id: int = Field(ge=1)
    transaction_date: date
    description: str | None = Field(default=None, max_length=255)

    model_config = ConfigDict(
        json_schema_extra={
            "example": {
                "wallet_id": 1,
                "type": "expense",
                "amount": "250.00",
                "category_id": 1,
                "transaction_date": "2026-10-24",
                "description": "Weekly groceries",
            }
        },
    )

    @field_validator("transaction_date")
    @classmethod
    def not_in_future(cls, value: date) -> date:
        return _reject_future_date(value)

    @field_validator("description")
    @classmethod
    def tidy_description(cls, value: str | None) -> str | None:
        return _clean_description(value)


class TransactionUpdateRequest(BaseModel):
    """Every field is optional; anything left out stays as it is.

    Sending description as null clears it. For an opening balance only the
    amount, date and description can change, and its amount may be negative.
    """

    wallet_id: int | None = Field(default=None, ge=1)
    type: TransactionKind | None = None
    amount: Decimal | None = Field(
        default=None,
        max_digits=AMOUNT_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
    )
    category_id: int | None = Field(default=None, ge=1)
    transaction_date: date | None = None
    description: str | None = Field(default=None, max_length=255)

    @field_validator("transaction_date")
    @classmethod
    def not_in_future(cls, value: date | None) -> date | None:
        return _reject_future_date(value)

    @field_validator("description")
    @classmethod
    def tidy_description(cls, value: str | None) -> str | None:
        return _clean_description(value)


class TransactionResponse(BaseModel):
    id: int
    wallet_id: int
    wallet_name: str
    currency: str
    type: str
    amount: str
    category_id: int | None
    category_name: str | None
    # Filled in for a subcategory, so the client can show "Bills › Electricity"
    # even when the parent is no longer switched on in this tracker.
    parent_category_name: str | None
    description: str | None
    transaction_date: date
    created_at: datetime
    

class WalletSummaryResponse(BaseModel):
    """One wallet's movement over a date range.

    opening + income - expenses always equals ending, so a table built from
    these rows always adds up across.
    """

    wallet_id: int
    wallet_name: str
    currency: str
    opening: str
    income: str
    expenses: str
    ending: str


class CurrencyTotalResponse(BaseModel):
    """The same four figures added up across every wallet in one currency.

    Amounts in different currencies are never added together, so a tracker
    with both peso and dollar wallets gets one total per currency.
    """

    currency: str
    opening: str
    income: str
    expenses: str
    ending: str


class TrackerSummaryResponse(BaseModel):
    start_date: date
    end_date: date
    wallets: list[WalletSummaryResponse]
    totals: list[CurrencyTotalResponse]