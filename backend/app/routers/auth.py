from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.deps import get_current_user, get_db
from app.models import User
from app.schemas import (
    LoginRequest,
    RegisterRequest,
    SetPinRequest,
    TokenResponse,
    UserResponse,
    VerifyPinRequest,
)
from app.security import (
    generate_session_token,
    hash_password,
    hash_token,
    verify_password,
)

router = APIRouter(prefix="/auth", tags=["auth"])


def _start_session(db: Session, user: User) -> str:
    """Issue a new session token, replacing any session already on the account.

    Only the SHA-256 hash of the token is stored, so a leaked database row
    cannot be replayed as a valid session. Because a single column holds the
    active token, logging in on a new device silently ends the old device's
    session, which is the one-active-device rule for v1.
    """
    token = generate_session_token()
    user.current_session_token = hash_token(token)
    db.commit()
    return token


@router.post(
    "/register",
    response_model=TokenResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Create an account and start a session",
)
def register(payload: RegisterRequest, db: Session = Depends(get_db)) -> TokenResponse:
    email = payload.email.strip().lower()

    if db.query(User).filter(User.email == email).first() is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="An account with this email already exists",
        )

    user = User(email=email, hashed_password=hash_password(payload.password))
    db.add(user)
    db.flush()

    return TokenResponse(access_token=_start_session(db, user))


@router.post(
    "/login",
    response_model=TokenResponse,
    summary="Log in and start a session",
)
def login(payload: LoginRequest, db: Session = Depends(get_db)) -> TokenResponse:
    email = payload.email.strip().lower()
    user = db.query(User).filter(User.email == email).first()

    if user is None or not verify_password(payload.password, user.hashed_password):
        # Deliberately identical for unknown email and wrong password, so the
        # response cannot be used to discover which emails have accounts.
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect email or password",
        )

    return TokenResponse(access_token=_start_session(db, user))


@router.post(
    "/logout",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="End the current session",
)
def logout(
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> None:
    user.current_session_token = None
    db.commit()


@router.get(
    "/me",
    response_model=UserResponse,
    summary="Return the signed-in account",
)
def read_current_user(user: User = Depends(get_current_user)) -> UserResponse:
    return UserResponse(
        id=user.id,
        email=user.email,
        has_pin=user.hashed_pin is not None,
        created_at=user.created_at,
    )


@router.post(
    "/pin",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Set or change the app PIN",
)
def set_pin(
    payload: SetPinRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> None:
    # The account password is required even though the caller already holds a
    # valid session, so that someone with a logged-in phone in their hands
    # cannot simply overwrite the PIN that is meant to keep them out.
    if not verify_password(payload.password, user.hashed_password):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect password",
        )

    user.hashed_pin = hash_password(payload.pin)
    db.commit()


@router.post(
    "/pin/verify",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Verify the app PIN",
)
def verify_pin(
    payload: VerifyPinRequest,
    user: User = Depends(get_current_user),
) -> None:
    if user.hashed_pin is None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="No PIN has been set for this account",
        )

    if not verify_password(payload.pin, user.hashed_pin):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect PIN",
        )