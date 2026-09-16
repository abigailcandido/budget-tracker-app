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