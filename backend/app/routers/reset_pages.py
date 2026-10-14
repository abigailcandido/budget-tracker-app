"""The web pages that emailed reset links open.

A link in an email may be opened on a laptop as easily as on the phone, so it
opens a plain web page served from here rather than the app. Each page reads
the token from its own address in the browser and sends it back as JSON, so
the token is never written into the HTML, and the page needs nothing beyond
what FastAPI already provides.
"""

from fastapi import APIRouter
from fastapi.responses import HTMLResponse

router = APIRouter(include_in_schema=False)

# Stops the page from passing its address, token included, to any other site;
# stops browsers keeping a copy; and stops other sites framing the page to
# trick someone into typing into it.
PAGE_HEADERS = {
    "Referrer-Policy": "no-referrer",
    "Cache-Control": "no-store",
    "X-Frame-Options": "DENY",
    "Content-Security-Policy": (
        "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
        "connect-src 'self'; form-action 'none'; frame-ancestors 'none'; base-uri 'none'"
    ),
}

PAGE_TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>__TITLE__ · Budget Tracker</title>
<style>
  :root { color-scheme: light dark; --accent: #2e7d32; }
  body { font-family: system-ui, sans-serif; margin: 0; padding: 32px 16px;
         display: flex; justify-content: center; background: Canvas; color: CanvasText; }
  main { width: 100%; max-width: 380px; }
  h1 { font-size: 1.4rem; margin: 0 0 8px; }
  p { line-height: 1.5; }
  label { display: block; margin: 16px 0 6px; font-weight: 600; }
  input { width: 100%; box-sizing: border-box; padding: 12px; font-size: 1rem;
          border: 1px solid GrayText; border-radius: 8px; background: Field; color: FieldText; }
  button { width: 100%; margin-top: 24px; padding: 12px; font-size: 1rem; border: 0;
           border-radius: 8px; background: var(--accent); color: white; cursor: pointer; }
  button:disabled { opacity: 0.6; cursor: default; }
  #message { margin-top: 20px; }
  .error { color: #c62828; }
</style>
</head>
<body>
<main>
  <h1>__TITLE__</h1>
  <p>__INTRO__</p>
  <form id="form" novalidate>
    <label for="first">New __WHAT__</label>
    <input id="first" __INPUT__ required>
    <label for="second">Type it again</label>
    <input id="second" __INPUT__ required>
    <button id="submit" type="submit">Save new __WHAT__</button>
  </form>
  <p id="message" role="status"></p>
</main>
<script>
  const token = new URLSearchParams(location.search).get("token");
  // Take the token out of the address bar, so it does not linger in history.
  history.replaceState(null, "", location.pathname);

  const form = document.getElementById("form");
  const button = document.getElementById("submit");
  const message = document.getElementById("message");

  function show(text, isError) {
    message.textContent = text;
    message.className = isError ? "error" : "";
  }

  function finish(text, isError) {
    form.hidden = true;
    show(text, isError);
  }

  if (!token) {
    finish("This link is incomplete. Ask for a new one from the app.", true);
  }

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const first = document.getElementById("first").value;
    const second = document.getElementById("second").value;

    if (!(__RULE__)) { show("__RULE_MESSAGE__", true); return; }
    if (first !== second) { show("The two entries do not match.", true); return; }

    button.disabled = true;
    try {
      const response = await fetch("__ENDPOINT__", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ token: token, __FIELD__: first }),
      });

      if (response.status === 204) {
        finish("__SUCCESS__", false);
        return;
      }

      const body = await response.json().catch(() => ({}));
      const detail = typeof body.detail === "string" ? body.detail : "__RULE_MESSAGE__";
      if (response.status === 400) { finish(detail, true); return; }
      show(detail, true);
    } catch (error) {
      show("Could not reach the server. Check your connection and try again.", true);
    }
    button.disabled = false;
  });
</script>
</body>
</html>
"""


def _render(**values: str) -> HTMLResponse:
    html = PAGE_TEMPLATE
    for key, value in values.items():
        html = html.replace(f"__{key.upper()}__", value)
    return HTMLResponse(html, headers=PAGE_HEADERS)


@router.get("/reset/pin", response_class=HTMLResponse)
def pin_reset_page() -> HTMLResponse:
    return _render(
        title="Choose a new PIN",
        intro="Your PIN unlocks the app each time you open it. It must be exactly 6 digits.",
        what="PIN",
        input='type="password" inputmode="numeric" maxlength="6" autocomplete="off"',
        rule="/^[0-9]{6}$/.test(first)",
        rule_message="Your PIN must be exactly 6 digits.",
        endpoint="/auth/pin/reset",
        field="pin",
        success="Your PIN has been changed. Go back to the app and unlock it with your new PIN.",
    )


@router.get("/reset/password", response_class=HTMLResponse)
def password_reset_page() -> HTMLResponse:
    return _render(
        title="Choose a new password",
        intro="Use at least 8 characters. You will need to sign in again on your phone afterwards.",
        what="password",
        input='type="password" autocomplete="new-password"',
        rule="first.length >= 8 && new TextEncoder().encode(first).length <= 72",
        rule_message="Your password must be 8 to 72 characters long.",
        endpoint="/auth/password/reset",
        field="password",
        success="Your password has been changed. Go back to the app and sign in with your new password.",
    )