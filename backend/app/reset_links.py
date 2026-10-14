"""Single-use links for resetting a PIN or a password by email."""

import os
from datetime import datetime, timedelta, timezone
from typing import Optional

from sqlalchemy.orm import Session

from app.models import AuthToken, User
from app.security import generate_session_token, hash_token

PIN_RESET = "pin_reset"
PASSWORD_RESET = "password_reset"

LINK_LIFETIME = timedelta(minutes=30)
MIN_GAP_BETWEEN_LINKS = timedelta(minutes=1)
MAX_LINKS_PER_HOUR = 5

PAGE_PATHS = {PIN_RESET: "/reset/pin", PASSWORD_RESET: "/reset/password"}


def _now() -> datetime:
    return datetime.now(timezone.utc)


def is_rate_limited(db: Session, user: User, purpose: str) -> bool:
    """Whether this account has asked for this kind of link too often lately.

    Without a limit, anyone who knows an address could fill that inbox with
    reset emails, and use up the whole daily sending allowance while doing it.
    """
    recent = (
        db.query(AuthToken)
        .filter(
            AuthToken.user_id == user.id,
            AuthToken.purpose == purpose,
            AuthToken.created_at > _now() - timedelta(hours=1),
        )
        .order_by(AuthToken.created_at.desc())
        .all()
    )

    if len(recent) >= MAX_LINKS_PER_HOUR:
        return True
    return bool(recent) and recent[0].created_at > _now() - MIN_GAP_BETWEEN_LINKS


def issue_link(db: Session, user: User, purpose: str) -> str:
    """Create a fresh link and return its full URL.

    Any earlier link of the same kind stops working, so only the newest email
    in the inbox is ever usable.
    """
    _retire_outstanding(db, user, purpose)

    token = generate_session_token()
    db.add(
        AuthToken(
            user_id=user.id,
            purpose=purpose,
            token_hash=hash_token(token),
            expires_at=_now() + LINK_LIFETIME,
        )
    )
    db.commit()

    base_url = (os.getenv("APP_BASE_URL") or "http://127.0.0.1:8000").rstrip("/")
    return f"{base_url}{PAGE_PATHS[purpose]}?token={token}"


def redeem_link(db: Session, token: str, purpose: str) -> Optional[User]:
    """Return the account a link belongs to, and use it up.

    Returns None for a link that is unknown, meant for something else, already
    used, or expired. Nothing is committed here: the caller commits once the
    new PIN or password has been saved, so the link and the change succeed or
    fail together.
    """
    record = (
        db.query(AuthToken)
        .filter(
            AuthToken.token_hash == hash_token(token),
            AuthToken.purpose == purpose,
            AuthToken.used_at.is_(None),
            AuthToken.expires_at > _now(),
        )
        .first()
    )
    if record is None:
        return None

    record.used_at = _now()
    return db.get(User, record.user_id)


def _retire_outstanding(db: Session, user: User, purpose: str) -> None:
    db.query(AuthToken).filter(
        AuthToken.user_id == user.id,
        AuthToken.purpose == purpose,
        AuthToken.used_at.is_(None),
    ).update({AuthToken.used_at: _now()}, synchronize_session=False)