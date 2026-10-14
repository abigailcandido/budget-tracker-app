"""Sending email through Resend's HTTP API.

Render's free tier blocks outbound connections to the usual email ports, so
SMTP is not an option from the server. Resend is called over HTTPS instead,
using only the standard library so there is nothing extra to install.
"""

import json
import logging
import os
import urllib.error
import urllib.request

RESEND_URL = "https://api.resend.com/emails"
DEFAULT_SENDER = "Budget Tracker <onboarding@resend.dev>"

# Addresses at these domains can never receive mail (RFC 2606), so there is no
# point spending the daily sending allowance on them. The smoke test's
# throwaway accounts all live here.
UNDELIVERABLE_DOMAINS = ("example.com", "example.org", "example.net")

# Uvicorn only prints messages sent to its own loggers, so this is what makes
# the development fallback below visible in the terminal.
logger = logging.getLogger("uvicorn.error")


def send_email(to: str, subject: str, text: str) -> None:
    """Send a plain-text email, or print it to the log when sending is off.

    Runs after the response has already gone back to the phone, so a slow or
    failing email service never holds up the app and never reveals anything
    through how long a request took. Failures are logged, not raised, because
    by the time this runs there is nobody left to report them to.
    """
    api_key = os.getenv("RESEND_API_KEY")
    domain = to.rsplit("@", 1)[-1].lower()

    if not api_key or domain in UNDELIVERABLE_DOMAINS:
        logger.info("Email not sent (no RESEND_API_KEY, or a test address).")
        logger.info("To: %s | Subject: %s\n%s", to, subject, text)
        return

    payload = {
        "from": os.getenv("EMAIL_FROM") or DEFAULT_SENDER,
        "to": [to],
        "subject": subject,
        "text": text,
    }
    request = urllib.request.Request(
        RESEND_URL,
        data=json.dumps(payload).encode("utf-8"),
        method="POST",
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            # Requests carrying Python's default user agent can be turned away
            # by the service's firewall, so this names the app instead.
            "User-Agent": "budget-tracker-api/1.0",
        },
    )

    try:
        with urllib.request.urlopen(request, timeout=10):
            pass
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        logger.error("Resend refused the email to %s (%s): %s", to, error.code, body)
    except (urllib.error.URLError, TimeoutError) as error:
        reason = getattr(error, "reason", error)
        logger.error("Could not reach Resend to email %s: %s", to, reason)