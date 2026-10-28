"""Six-digit codes sent by email to confirm an action, such as a deletion.

A code belongs to one action on one record: a code sent for deleting one
transaction cannot delete another. It works once, stops working after a few
minutes, and allows only a few wrong tries, so guessing one is hopeless.
"""

import secrets
from datetime import datetime, timedelta, timezone

from fastapi import HTTPException, status
from sqlalchemy.orm import Session

from app.models import EmailCode, User
from app.security import hash_password, verify_password

DELETE_TRANSACTION = "delete_transaction"

CODE_LIFETIME = timedelta(minutes=10)
# Asking again for the same record within this gap is refused while the last
# code still works, so a double tap never sends two emails. A code used up by
# wrong tries can be replaced at once, and so can one for a different record.
MIN_GAP_FOR_SAME_RECORD = timedelta(seconds=30)
# Across all records, so nobody can fill the inbox or use up the daily
# sending allowance.
MAX_CODES_PER_HOUR = 20
MAX_WRONG_TRIES = 5


def _now() -> datetime:
    return datetime.now(timezone.utc)


def refuse_if_too_soon(db: Session, user: User, purpose: str, target_id: int) -> None:
    """Refuse a new code when one was sent moments ago, or too many lately."""
    recent = (
        db.query(EmailCode)
        .filter(
            EmailCode.user_id == user.id,
            EmailCode.purpose == purpose,
            EmailCode.created_at > _now() - timedelta(hours=1),
        )
        .order_by(EmailCode.created_at.desc())
        .all()
    )

    if len(recent) >= MAX_CODES_PER_HOUR:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many codes were sent in the last hour. Try again later",
        )

    last = next((code for code in recent if code.target_id == target_id), None)
    still_works = (
        last is not None
        and last.used_at is None
        and last.attempts < MAX_WRONG_TRIES
        and last.expires_at > _now()
    )
    if still_works and last.created_at > _now() - MIN_GAP_FOR_SAME_RECORD:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="A code was sent a moment ago. Check your email, or wait a little before asking again",
        )


def issue_code(db: Session, user: User, purpose: str, target_id: int) -> str:
    """Make a fresh code for this action on this record, and return it.

    Any earlier code for the same action on the same record stops working, so
    only the newest email is ever usable.
    """
    db.query(EmailCode).filter(
        EmailCode.user_id == user.id,
        EmailCode.purpose == purpose,
        EmailCode.target_id == target_id,
        EmailCode.used_at.is_(None),
    ).update({EmailCode.used_at: _now()}, synchronize_session=False)

    code = f"{secrets.randbelow(1_000_000):06d}"
    db.add(
        EmailCode(
            user_id=user.id,
            purpose=purpose,
            target_id=target_id,
            code_hash=hash_password(code),
            expires_at=_now() + CODE_LIFETIME,
        )
    )
    db.commit()
    return code


def use_code(db: Session, user: User, purpose: str, target_id: int, code: str) -> None:
    """Accept the code, or refuse it with a reason the user can act on.

    A wrong code is counted straight away, even though the request fails, so
    the limit on tries cannot be dodged. A right one is marked used but not
    committed here: the caller commits once the action itself is saved, so the
    code and the action succeed or fail together.
    """
    record = (
        db.query(EmailCode)
        .filter(
            EmailCode.user_id == user.id,
            EmailCode.purpose == purpose,
            EmailCode.target_id == target_id,
            EmailCode.used_at.is_(None),
        )
        .order_by(EmailCode.created_at.desc())
        .with_for_update()
        .first()
    )

    if record is None:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Send a code to your email first",
        )
    if record.expires_at <= _now():
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="This code has expired. Send a new one",
        )
    if record.attempts >= MAX_WRONG_TRIES:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Too many wrong tries. Send a new code",
        )

    if not verify_password(code, record.code_hash):
        record.attempts += 1
        db.commit()
        tries_left = MAX_WRONG_TRIES - record.attempts
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                f"Incorrect code. {tries_left} {'try' if tries_left == 1 else 'tries'} left"
                if tries_left > 0
                else "Incorrect code. Send a new one"
            ),
        )

    record.used_at = _now()


def masked_email(address: str) -> str:
    """Shows where a code went without spelling out the address: "so•••@gmail.com"."""
    local, _, domain = address.partition("@")
    return f"{local[:2]}•••@{domain}"