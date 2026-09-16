from datetime import datetime

from pydantic import BaseModel, EmailStr, Field, field_validator

BCRYPT_MAX_PASSWORD_BYTES = 72
PIN_PATTERN = r"^\d{4,6}$"


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


class _TrackerNameModel(BaseModel):
    """Shared name handling for tracker requests.

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


class TrackerCreateRequest(_TrackerNameModel):
    pass


class TrackerUpdateRequest(_TrackerNameModel):
    pass


class TrackerResponse(BaseModel):
    id: int
    name: str
    created_at: datetime