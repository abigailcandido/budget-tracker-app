from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.deps import get_current_user, get_db
from app.mailer import send_email
from app.models import User
from app.reset_links import (
    PASSWORD_RESET,
    PIN_RESET,
    is_rate_limited,
    issue_link,
    redeem_link,
)
from app.schemas import (
    LoginRequest,
    MessageResponse,
    PasswordResetEmailRequest,
    PasswordResetRequest,
    PinResetRequest,
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

INVALID_LINK = "This link is invalid, has expired or has already been used. Ask for a new one from the app."
PASSWORD_LINK_SENT = "If an account uses that email, a link to reset its password is on its way."


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
    background: BackgroundTasks,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> None:
    # The account password is required even though the caller already holds a
    # valid session, so that someone with a logged-in phone in their hands
    # cannot simply overwrite the PIN that is meant to keep them out.
    if not verify_password(payload.password, user.hashed_password):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Incorrect password",
        )

    had_pin = user.hashed_pin is not None
    user.hashed_pin = hash_password(payload.pin)
    db.commit()

    # Choosing a PIN for the first time is not worth an email; replacing one is.
    if had_pin:
        background.add_task(_send_change_notice, user.email, "PIN")


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
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Incorrect PIN",
        )


@router.post(
    "/pin/reset-request",
    response_model=MessageResponse,
    status_code=status.HTTP_202_ACCEPTED,
    summary="Email a link for choosing a new PIN",
)
def request_pin_reset(
    background: BackgroundTasks,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> MessageResponse:
    # The phone must still hold a valid session, so the link can only ever go
    # to the address of the account that is signed in on it.
    if is_rate_limited(db, user, PIN_RESET):
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="A link was sent a moment ago. Check your inbox, or wait a minute and try again.",
        )

    link = issue_link(db, user, PIN_RESET)
    background.add_task(
        send_email,
        user.email,
        "Reset your Budget Tracker PIN",
        _link_email_text("PIN", link),
    )
    return MessageResponse(detail=f"A link is on its way to {user.email}. It expires in 30 minutes.")


@router.post(
    "/password/reset-request",
    response_model=MessageResponse,
    status_code=status.HTTP_202_ACCEPTED,
    summary="Email a link for choosing a new password",
)
def request_password_reset(
    payload: PasswordResetEmailRequest,
    background: BackgroundTasks,
    db: Session = Depends(get_db),
) -> MessageResponse:
    email = payload.email.strip().lower()
    user = db.query(User).filter(User.email == email).first()

    # Every outcome gives the same answer, and the email itself is sent after
    # the response has gone, so neither the reply nor its timing reveals
    # whether an account uses this address.
    if user is not None and not is_rate_limited(db, user, PASSWORD_RESET):
        link = issue_link(db, user, PASSWORD_RESET)
        background.add_task(
            send_email,
            user.email,
            "Reset your Budget Tracker password",
            _link_email_text("password", link),
        )

    return MessageResponse(detail=PASSWORD_LINK_SENT)


@router.post(
    "/pin/reset",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Choose a new PIN using an emailed link",
)
def reset_pin(
    payload: PinResetRequest,
    background: BackgroundTasks,
    db: Session = Depends(get_db),
) -> None:
    user = redeem_link(db, payload.token, PIN_RESET)
    if user is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=INVALID_LINK)

    user.hashed_pin = hash_password(payload.pin)
    db.commit()
    background.add_task(_send_change_notice, user.email, "PIN")


@router.post(
    "/password/reset",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Choose a new password using an emailed link",
)
def reset_password(
    payload: PasswordResetRequest,
    background: BackgroundTasks,
    db: Session = Depends(get_db),
) -> None:
    user = redeem_link(db, payload.token, PASSWORD_RESET)
    if user is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=INVALID_LINK)

    user.hashed_password = hash_password(payload.password)
    # Whoever held the old password may be signed in on a phone right now.
    # Ending the session means the new password is needed to get back in.
    user.current_session_token = None
    db.commit()
    background.add_task(_send_change_notice, user.email, "password")


def _link_email_text(what: str, link: str) -> str:
    return (
        f"Someone, hopefully you, asked to reset the {what} for your Budget Tracker account.\n\n"
        f"Open this link to choose a new {what}. It works once and expires in 30 minutes:\n\n"
        f"{link}\n\n"
        f"If you did not ask for this, ignore this email. Your {what} stays the same."
    )


def _send_change_notice(email: str, what: str) -> None:
    send_email(
        email,
        f"Your Budget Tracker {what} was changed",
        (
            f"The {what} for your Budget Tracker account was just changed.\n\n"
            "If this was you, there is nothing more to do.\n\n"
            "If it was not, someone may have access to this email account. "
            "Change your email password first, then reset your Budget Tracker "
            "password from the app's sign-in screen."
        ),
    )