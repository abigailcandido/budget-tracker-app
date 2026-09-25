"""Smoke tests for the Budget Tracker API.

Run with the server already running:

    python smoke_test.py
    python smoke_test.py https://budget-tracker-api-fpd3.onrender.com

Creates two throwaway accounts with timestamped email addresses, so it never
touches data you care about. Uses only the Python standard library.
"""

from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.request

BASE_URL = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8000").rstrip("/")

PASSED: list[str] = []
FAILED: list[str] = []


def call(method: str, path: str, token: str | None = None, body: dict | None = None):
    """Send one request and return (status_code, parsed_body_or_None)."""
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(f"{BASE_URL}{path}", data=data, method=method)
    request.add_header("Accept", "application/json")

    if data is not None:
        request.add_header("Content-Type", "application/json")
    if token is not None:
        request.add_header("Authorization", f"Bearer {token}")

    try:
        with urllib.request.urlopen(request) as response:
            raw = response.read()
            return response.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            return error.code, (json.loads(raw) if raw else None)
        except json.JSONDecodeError:
            return error.code, None
    except urllib.error.URLError as error:
        print(f"Could not reach {BASE_URL} — is the server running? ({error.reason})")
        sys.exit(1)


def check(description: str, actual, expected) -> None:
    if actual == expected:
        PASSED.append(description)
        print(f"  PASS  {description}")
    else:
        FAILED.append(f"{description}: expected {expected!r}, got {actual!r}")
        print(f"  FAIL  {description}: expected {expected!r}, got {actual!r}")


def names(rows) -> list[str]:
    return sorted(row["name"] for row in (rows or []))


def enabled_names(rows) -> list[str]:
    return sorted(row["name"] for row in (rows or []) if row["is_enabled"])


def main() -> None:
    stamp = int(time.time())
    print(f"Testing {BASE_URL}\n")

    print("Accounts")
    status, body = call(
        "POST",
        "/auth/register",
        body={"email": f"smoke-{stamp}-a@example.com", "password": "testing12345"},
    )
    check("register first account", status, 201)
    token_a = body["access_token"]

    status, body = call(
        "POST",
        "/auth/register",
        body={"email": f"smoke-{stamp}-b@example.com", "password": "testing12345"},
    )
    check("register second account", status, 201)
    token_b = body["access_token"]

    status, _ = call(
        "POST",
        "/auth/register",
        body={"email": f"smoke-{stamp}-a@example.com", "password": "testing12345"},
    )
    check("duplicate email rejected", status, 409)

    status, _ = call(
        "POST",
        "/auth/register",
        body={"email": f"smoke-{stamp}-c@example.com", "password": "short"},
    )
    check("short password rejected", status, 422)

    print("\nTrackers")
    status, tracker_one = call("POST", "/trackers", token_a, {"name": "Tracker One"})
    check("create tracker", status, 201)

    status, tracker_two = call("POST", "/trackers", token_a, {"name": "Tracker Two"})
    check("create second tracker", status, 201)

    status, _ = call("POST", "/trackers", token_a, {"name": "Tracker One"})
    check("duplicate tracker name rejected", status, 409)

    status, _ = call("POST", "/trackers", token_a, {"name": "tracker one"})
    check("tracker name differing only in case rejected", status, 409)

    status, _ = call("POST", "/trackers", token_a, {"name": "   "})
    check("blank tracker name rejected", status, 422)

    one = tracker_one["id"]
    two = tracker_two["id"]

    print("\nCategories")
    status, groceries = call("POST", "/categories", token_a, {"name": "Groceries"})
    check("create global category", status, 201)
    check("global category flagged correctly", groceries["is_global"], True)

    status, rows = call("GET", f"/trackers/{one}/categories", token_a)
    check("global category auto-enabled in first tracker", enabled_names(rows), ["Groceries"])

    status, rows = call("GET", f"/trackers/{two}/categories", token_a)
    check("global category auto-enabled in second tracker", enabled_names(rows), ["Groceries"])

    status, meds = call(
        "POST", "/categories", token_a, {"name": "Meds", "main_tracker_id": one}
    )
    check("create private category", status, 201)
    check("private category flagged correctly", meds["is_global"], False)

    status, rows = call("GET", f"/trackers/{two}/categories", token_a)
    check("private category hidden from other tracker", names(rows), ["Groceries"])

    status, _ = call("PUT", f"/trackers/{two}/categories/{meds['id']}", token_a)
    check("enabling a private category elsewhere rejected", status, 409)

    status, _ = call("POST", "/categories", token_a, {"name": "Groceries"})
    check("duplicate category name rejected", status, 409)

    status, _ = call("POST", "/categories", token_a, {"name": "groceries"})
    check("category name differing only in case rejected", status, 409)

    print("\nCategory switches")
    status, _ = call("DELETE", f"/trackers/{one}/categories/{groceries['id']}", token_a)
    check("disable category", status, 204)

    status, rows = call("GET", f"/trackers/{one}/categories?only_enabled=true", token_a)
    check("disabled category excluded when filtering", names(rows), ["Meds"])

    status, rows = call("GET", f"/trackers/{one}/categories", token_a)
    check("disabled category still listed as available", names(rows), ["Groceries", "Meds"])

    status, _ = call("PUT", f"/trackers/{one}/categories/{groceries['id']}", token_a)
    check("enable category", status, 204)

    status, _ = call("PUT", f"/trackers/{one}/categories/{groceries['id']}", token_a)
    check("enabling twice is harmless", status, 204)

    status, tracker_three = call("POST", "/trackers", token_a, {"name": "Tracker Three"})
    check("create third tracker", status, 201)

    status, rows = call("GET", f"/trackers/{tracker_three['id']}/categories", token_a)
    check("new tracker inherits global categories", enabled_names(rows), ["Groceries"])

    status, _ = call("PATCH", f"/categories/{meds['id']}", token_a, {"name": "Medication"})
    check("rename category", status, 200)

    status, _ = call("PATCH", f"/categories/{meds['id']}", token_a, {"name": "Groceries"})
    check("rename onto an existing name rejected", status, 409)

    print("\nSubcategories")
    status, bills = call("POST", "/categories", token_a, {"name": "Bills"})
    check("create a parent category", status, 201)
    check("a new category has no children", bills["has_children"], False)

    status, electricity = call(
        "POST", "/categories", token_a, {"name": "Electricity", "parent_id": bills["id"]}
    )
    check("create a subcategory", status, 201)
    check("subcategory points at its parent", electricity["parent_id"], bills["id"])
    # Scope is inherited, never chosen: a private child under a global parent
    # would be invisible in trackers where the parent is perfectly visible.
    check("subcategory inherits its parent's scope", electricity["is_global"], True)

    status, rows = call("GET", "/categories", token_a)
    parent_row = next((row for row in rows if row["id"] == bills["id"]), {})
    check("parent now reports having children", parent_row.get("has_children"), True)

    status, _ = call(
        "POST",
        "/categories",
        token_a,
        {"name": "Meralco", "parent_id": electricity["id"]},
    )
    check("a third level is rejected", status, 409)

    status, _ = call(
        "POST",
        "/categories",
        token_a,
        {"name": "electricity", "parent_id": bills["id"]},
    )
    check("duplicate sibling name rejected", status, 409)

    status, water = call(
        "POST", "/categories", token_a, {"name": "Water", "parent_id": bills["id"]}
    )
    check("second subcategory accepted", status, 201)

    status, _ = call("DELETE", f"/categories/{bills['id']}", token_a)
    check("parent holding subcategories cannot be deleted", status, 409)

    print("\nSubcategory switches")
    # Everything below runs against the second tracker, so the first tracker's
    # state stays as the earlier checks left it.
    status, _ = call("DELETE", f"/trackers/{two}/categories/{water['id']}", token_a)
    check("switch one subcategory off", status, 204)

    status, rows = call("GET", f"/trackers/{two}/categories", token_a)
    check(
        "parent stays on while a sibling is still on",
        enabled_names(rows),
        ["Bills", "Electricity", "Groceries"],
    )

    status, _ = call("DELETE", f"/trackers/{two}/categories/{electricity['id']}", token_a)
    check("switch off the last subcategory that was on", status, 204)

    # A parent cannot be chosen directly, so one left on with nothing beneath it
    # would be a dead end in every picker. It goes off with its last child.
    status, rows = call("GET", f"/trackers/{two}/categories", token_a)
    check(
        "parent goes off with its last subcategory",
        enabled_names(rows),
        ["Groceries"],
    )

    status, _ = call("PUT", f"/trackers/{two}/categories/{electricity['id']}", token_a)
    check("switch a subcategory back on", status, 204)

    status, rows = call("GET", f"/trackers/{two}/categories", token_a)
    check(
        "parent returns with its subcategory",
        enabled_names(rows),
        ["Bills", "Electricity", "Groceries"],
    )

    status, _ = call("DELETE", f"/trackers/{two}/categories/{bills['id']}", token_a)
    check("switch the parent off", status, 204)

    status, rows = call("GET", f"/trackers/{two}/categories", token_a)
    check("children go off with the parent", enabled_names(rows), ["Groceries"])

    status, _ = call("PUT", f"/trackers/{two}/categories/{bills['id']}", token_a)
    check("switch the parent on", status, 204)

    status, rows = call("GET", f"/trackers/{two}/categories", token_a)
    check(
        "children come back with the parent",
        enabled_names(rows),
        ["Bills", "Electricity", "Groceries", "Water"],
    )

    status, tracker_four = call(
        "POST",
        "/trackers",
        token_a,
        {"name": "Tracker Four", "category_ids": [electricity["id"]]},
    )
    check("create a tracker choosing one subcategory", status, 201)

    status, rows = call("GET", f"/trackers/{tracker_four['id']}/categories", token_a)
    check(
        "a chosen subcategory brings its parent along",
        enabled_names(rows),
        ["Bills", "Electricity"],
    )

    print("\nName scoping")
    status, _ = call(
        "POST", "/categories", token_a, {"name": "Rent", "main_tracker_id": one}
    )
    check("private category in the first tracker", status, 201)

    # Two categories private to different trackers never appear together, so
    # they may share a name. Refusing this would block a name with a category
    # the user cannot see from where they are standing.
    status, _ = call(
        "POST", "/categories", token_a, {"name": "Rent", "main_tracker_id": two}
    )
    check("same private name allowed in another tracker", status, 201)

    # A global category appears everywhere, so it has to differ from everything.
    status, _ = call("POST", "/categories", token_a, {"name": "Rent"})
    check("a global category cannot reuse a private name", status, 409)

    print("\nWallets")
    status, wallet = call(
        "POST",
        f"/trackers/{one}/wallets",
        token_a,
        {"name": "Cash", "currency": "php", "opening_balance": "1500.50"},
    )
    check("create wallet", status, 201)
    check("currency normalised to upper case", wallet["currency"], "PHP")
    check("balance returned as a string", wallet["balance"], "1500.50")

    status, bank = call(
        "POST", f"/trackers/{one}/wallets", token_a, {"name": "Bank", "currency": "PHP"}
    )
    check("opening balance defaults to zero", bank["balance"], "0.00")

    status, _ = call(
        "POST", f"/trackers/{one}/wallets", token_a, {"name": "Cash", "currency": "PHP"}
    )
    check("duplicate wallet name in same tracker rejected", status, 409)

    status, _ = call(
        "POST", f"/trackers/{one}/wallets", token_a, {"name": "cash", "currency": "PHP"}
    )
    check("wallet name differing only in case rejected", status, 409)

    status, _ = call(
        "POST", f"/trackers/{two}/wallets", token_a, {"name": "Cash", "currency": "PHP"}
    )
    check("same wallet name allowed in another tracker", status, 201)

    print("\nGuard rails")
    status, _ = call("DELETE", f"/trackers/{one}", token_a)
    check("tracker holding wallets cannot be deleted", status, 409)

    status, _ = call("DELETE", f"/trackers/{tracker_three['id']}", token_a)
    check("empty tracker can be deleted", status, 204)

    status, _ = call("DELETE", f"/categories/{meds['id']}", token_a)
    check("unused category can be deleted", status, 204)

    status, rows = call("GET", f"/trackers/{one}/categories", token_a)
    check(
        "deleted category leaves no trace in tracker list",
        names(rows),
        ["Bills", "Electricity", "Groceries", "Rent", "Water"],
    )

    print("\nOwnership")
    status, _ = call("GET", f"/trackers/{one}", token_b)
    check("another account's tracker is not found", status, 404)

    status, _ = call("GET", f"/trackers/{one}/categories", token_b)
    check("another account's tracker categories are not found", status, 404)

    status, _ = call("GET", f"/wallets/{wallet['id']}", token_b)
    check("another account's wallet is not found", status, 404)

    status, _ = call("GET", "/trackers/999999", token_a)
    check("missing tracker is not found", status, 404)

    status, _ = call("GET", "/trackers")
    check("request without a token is rejected", status, 401)

    print(f"\n{len(PASSED)} passed, {len(FAILED)} failed")
    for failure in FAILED:
        print(f"  - {failure}")

    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()