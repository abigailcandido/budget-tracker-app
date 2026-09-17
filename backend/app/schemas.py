from datetime import datetime
from decimal import Decimal

from pydantic import BaseModel, ConfigDict, EmailStr, Field, field_validator

BCRYPT_MAX_PASSWORD_BYTES = 72
PIN_PATTERN = r"^\d{4,6}$"
CURRENCY_PATTERN = r"^[A-Za-z]{3}$"
MONEY_MAX_DIGITS = 14
MONEY_DECIMAL_PLACES = 2


def format_money(value: Decimal) -> str:
    """Render a stored amount as a fixed two-decimal string for the API."""
    return f"{value:.2f}"


class RegisterRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=8, max_length=BCRYPT_MAX_PASSWORD_BYTES)

    @field_validator("password")
    @classmethod
    def password_must_fit_bcrypt(cls, value: str) -> str:
        if len(value.encode("utf-8")) > BCRYPT_MAX_PASSWORD_BYTES:
            raise ValueError(
                f"Password must be at most {BCRYPT_MAX_PASSWORD_BYTES} bytes long"
            )
        return value


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
    pass


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
        max_digits=MONEY_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
    )

    @field_validator("currency")
    @classmethod
    def normalise_currency(cls, value: str) -> str:
        return value.strip().upper()


class WalletUpdateRequest(_NamedRequest):
    """Only the name can change.

    Currency is deliberately absent. Changing it would silently reinterpret
    every amount already recorded in that wallet — a balance of 5,000 pesos
    becoming 5,000 dollars without a single transaction being edited. If a
    wallet is in the wrong currency, the honest fix is a new wallet.
    """


class WalletResponse(BaseModel):
    id: int
    main_tracker_id: int
    name: str
    currency: str
    balance: str
    created_at: datetime


class CategoryCreateRequest(_NamedRequest):
    main_tracker_id: int | None = Field(
        default=None,
        ge=1,
        description=(
            "Leave this out entirely for a category available across every "
            "tracker. Supply a tracker id to make it private to that tracker."
        ),
    )

    model_config = ConfigDict(
        json_schema_extra={"example": {"name": "Groceries"}},
    )


class CategoryUpdateRequest(_NamedRequest):
    """Only the name can change.

    A category's scope is fixed once created. Promoting a private category to
    global, or demoting the reverse, would change which trackers can see
    transactions already filed under it — so the honest fix is a new category.
    """


class CategoryResponse(BaseModel):
    id: int
    name: str
    main_tracker_id: int | None
    is_global: bool
    created_at: datetime


class TrackerCategoryResponse(BaseModel):
    """A category as it appears from inside one particular tracker."""

    id: int
    name: str
    is_global: bool
    is_enabled: bool