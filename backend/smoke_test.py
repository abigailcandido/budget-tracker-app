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
from datetime import date, timedelta
import urllib.error
import urllib.request

# The first test account's PIN.
PIN = "135790"

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

def fetch_page(path: str):
    """Load a web page and return (status_code, headers, text)."""
    try:
        with urllib.request.urlopen(f"{BASE_URL}{path}") as response:
            return response.status, response.headers, response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        return error.code, error.headers, ""

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
    
    status, _ = call("POST", "/auth/pin", token_a, {"password": "testing12345", "pin": PIN})
    check("set a PIN", status, 204)

    print("\nReset links")
    # The links themselves only ever reach an inbox (or, with no email key set,
    # the server window), so these checks cover everything around them.
    status, _ = call("POST", "/auth/pin/reset-request", token_a)
    check("ask for a PIN reset link", status, 202)

    status, _ = call("POST", "/auth/pin/reset-request", token_a)
    check("asking again straight away is refused", status, 429)

    status, _ = call("POST", "/auth/pin/reset-request")
    check("a PIN reset link needs a signed-in phone", status, 401)

    status, known = call(
        "POST",
        "/auth/password/reset-request",
        body={"email": f"smoke-{stamp}-a@example.com"},
    )
    check("ask for a password reset link", status, 202)

    status, unknown = call(
        "POST",
        "/auth/password/reset-request",
        body={"email": f"smoke-{stamp}-nobody@example.com"},
    )
    check("an unknown email gets the same status", status, 202)
    check("and the same message, revealing nothing", unknown, known)

    status, _ = call(
        "POST", "/auth/pin/reset", body={"token": "not-a-real-link", "pin": "123456"}
    )
    check("a made-up PIN link is refused", status, 400)

    status, _ = call(
        "POST", "/auth/pin/reset", body={"token": "not-a-real-link", "pin": "12345"}
    )
    check("a new PIN must be 6 digits", status, 422)

    status, _ = call(
        "POST",
        "/auth/password/reset",
        body={"token": "not-a-real-link", "password": "testing67890"},
    )
    check("a made-up password link is refused", status, 400)

    status, headers, _ = fetch_page("/reset/pin")
    check("the PIN reset page loads", status, 200)
    check("the page never passes its address on", headers.get("Referrer-Policy"), "no-referrer")

    status, _, _ = fetch_page("/reset/password")
    check("the password reset page loads", status, 200)

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

    print("\nCategories created with their subcategories")
    status, utilities = call(
        "POST",
        "/categories",
        token_a,
        {
            "name": "Utilities",
            "main_tracker_id": two,
            "subcategory_names": ["Internet", "Mobile"],
        },
    )
    check("create a category together with its subcategories", status, 201)
    check("it reports having children", (utilities or {}).get("has_children"), True)

    status, rows = call("GET", f"/trackers/{two}/categories", token_a)
    children = [row for row in (rows or []) if row["parent_id"] == (utilities or {}).get("id")]
    check("its subcategories sit underneath it", names(children), ["Internet", "Mobile"])
    check(
        "the subcategories take the parent's scope and switches",
        bool(children) and all(row["is_enabled"] and not row["is_global"] for row in children),
        True,
    )

    status, _ = call(
        "POST",
        "/categories",
        token_a,
        {"name": "Phones", "subcategory_names": ["Case", "case"]},
    )
    check("repeated subcategory names rejected", status, 422)

    status, rows = call("GET", "/categories", token_a)
    check(
        "a rejected request leaves nothing behind",
        "Phones" in [row["name"] for row in (rows or [])],
        False,
    )

    status, _ = call(
        "POST",
        "/categories",
        token_a,
        {"name": "Solar", "parent_id": bills["id"], "subcategory_names": ["Panels"]},
    )
    check("a subcategory cannot be created with subcategories", status, 409)

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

    status, cash_two = call(
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
    
    print("\nIncome categories")
    status, salary = call("POST", "/categories", token_a, {"name": "Salary", "kind": "income"})
    check("create an income category", status, 201)
    check("it is marked as income", (salary or {}).get("kind"), "income")

    status, _ = call("POST", "/categories", token_a, {"name": "Other"})
    check("create an expense category called Other", status, 201)

    status, _ = call("POST", "/categories", token_a, {"name": "Other", "kind": "income"})
    check("an income category may share a name with an expense one", status, 201)

    status, _ = call("POST", "/categories", token_a, {"name": "other", "kind": "income"})
    check("but not with another income one", status, 409)

    status, freelance = call(
        "POST", "/categories", token_a, {"name": "Freelance", "kind": "income"}
    )
    status, design = call(
        "POST", "/categories", token_a, {"name": "Design", "parent_id": freelance["id"]}
    )
    check("create an income subcategory", status, 201)
    check("it takes its parent's kind", (design or {}).get("kind"), "income")

    status, _ = call(
        "POST",
        "/categories",
        token_a,
        {"name": "Tips", "parent_id": freelance["id"], "kind": "expense"},
    )
    check("a subcategory cannot be a different kind", status, 409)

    status, rows = call("GET", f"/trackers/{one}/categories?kind=income", token_a)
    check("list only income categories", names(rows), ["Design", "Freelance", "Other", "Salary"])

    print("\nTransactions")

    def balance_of(wallet_id: int):
        _, body = call("GET", f"/wallets/{wallet_id}", token_a)
        return (body or {}).get("balance")

    def id_of(row):
        return (row or {}).get("id")
    
    def delete_entry(transaction_id, reason="Bought by mistake"):
        """Ask for a deletion code, then delete with it. Returns the status."""
        _, sent = call("POST", f"/transactions/{transaction_id}/delete-code", token_a)
        status, _ = call(
            "DELETE",
            f"/transactions/{transaction_id}",
            token_a,
            {"reason": reason, "code": (sent or {}).get("test_code")},
        )
        return status
    
    today = date.today()

    status, rows = call("GET", f"/trackers/{one}/transactions", token_a)
    check(
        "a wallet's opening balance is its first transaction",
        [(row["type"], row["amount"], row["wallet_name"]) for row in rows or []],
        [("opening", "1500.50", "Cash")],
    )

    status, groceries_run = call(
        "POST",
        "/transactions",
        token_a,
        {
            "wallet_id": wallet["id"],
            "type": "expense",
            "amount": "250.00",
            "category_id": groceries["id"],
            "transaction_date": today.isoformat(),
            "description": "  Weekly groceries  ",
        },
    )
    check("record an expense", status, 201)
    check("its description is tidied", (groceries_run or {}).get("description"), "Weekly groceries")
    check("the wallet's balance goes down", balance_of(wallet["id"]), "1250.50")

    status, pay = call(
        "POST",
        "/transactions",
        token_a,
        {
            "wallet_id": bank["id"],
            "type": "income",
            "amount": "30000.00",
            "category_id": salary["id"],
            "transaction_date": today.isoformat(),
        },
    )
    check("record an income", status, 201)
    check("the wallet's balance goes up", balance_of(bank["id"]), "30000.00")

    expense = {
        "wallet_id": wallet["id"],
        "type": "expense",
        "amount": "10.00",
        "category_id": groceries["id"],
        "transaction_date": today.isoformat(),
    }

    status, _ = call("POST", "/transactions", token_a, {**expense, "category_id": salary["id"]})
    check("an expense cannot use an income category", status, 409)

    status, _ = call("POST", "/transactions", token_a, {**expense, "category_id": bills["id"]})
    check("a category with subcategories cannot be used directly", status, 409)

    status, _ = call(
        "POST", "/transactions", token_a, {**expense, "category_id": utilities["id"]}
    )
    check("a category private to another tracker is refused", status, 409)

    call("DELETE", f"/trackers/{one}/categories/{water['id']}", token_a)
    status, _ = call("POST", "/transactions", token_a, {**expense, "category_id": water["id"]})
    check("a category switched off in this tracker is refused", status, 409)
    call("PUT", f"/trackers/{one}/categories/{water['id']}", token_a)

    status, _ = call("POST", "/transactions", token_a, {**expense, "amount": "0"})
    check("a zero amount is refused", status, 422)

    status, _ = call("POST", "/transactions", token_a, {**expense, "amount": "-5"})
    check("a negative amount is refused", status, 422)

    status, _ = call("POST", "/transactions", token_a, {**expense, "amount": "1.005"})
    check("more than two decimal places is refused", status, 422)

    status, _ = call(
        "POST",
        "/transactions",
        token_a,
        {**expense, "transaction_date": (today + timedelta(days=5)).isoformat()},
    )
    check("a date in the future is refused", status, 422)

    status, bill = call(
        "POST",
        "/transactions",
        token_a,
        {**expense, "amount": "2000.00", "category_id": electricity["id"]},
    )
    check("an expense larger than the balance is allowed", status, 201)
    check("and the balance goes below zero", balance_of(wallet["id"]), "-749.50")

    # A correction never changes the entry it corrects. It adds a reversal
    # that cancels it and a corrected entry, which comes back with a new id.
    status, bill_v2 = call(
        "PATCH",
        f"/transactions/{bill['id']}",
        token_a,
        {"amount": "200.00", "reason": "Typed one zero too many"},
    )
    check("correct an amount", status, 200)
    check("the correction is a new entry", id_of(bill_v2) not in (None, bill["id"]), True)
    check("which says what it replaced", (bill_v2 or {}).get("replaces_id"), bill["id"])
    check("the balance follows the correction", balance_of(wallet["id"]), "1050.50")

    status, _ = call("PATCH", f"/transactions/{bill['id']}", token_a, {"amount": "150.00"})
    check("an entry that was already corrected cannot be corrected again", status, 409)

    status, bill_v3 = call(
        "PATCH", f"/transactions/{id_of(bill_v2)}", token_a, {"wallet_id": bank["id"]}
    )
    check("move a transaction to another wallet", status, 200)
    check("the old wallet gets the money back", balance_of(wallet["id"]), "1250.50")
    check("the new wallet is charged", balance_of(bank["id"]), "29800.00")

    status, _ = call(
        "PATCH", f"/transactions/{id_of(bill_v3)}", token_a, {"wallet_id": cash_two["id"]}
    )
    check("moving to a wallet in another tracker is refused", status, 409)

    status, _ = call("PATCH", f"/transactions/{id_of(bill_v3)}", token_a, {"type": "income"})
    check("becoming an income without an income category is refused", status, 409)

    status, bill_v4 = call(
        "PATCH",
        f"/transactions/{id_of(bill_v3)}",
        token_a,
        {"type": "income", "category_id": salary["id"]},
    )
    check("change an expense into an income", status, 200)
    check("the balance swings the other way", balance_of(bank["id"]), "30200.00")

    status, groceries_v2 = call(
        "PATCH", f"/transactions/{groceries_run['id']}", token_a, {"description": None}
    )
    check("clear a description", (status, (groceries_v2 or {}).get("description")), (200, None))

    call("DELETE", f"/trackers/{one}/categories/{groceries['id']}", token_a)
    status, groceries_v3 = call(
        "PATCH", f"/transactions/{id_of(groceries_v2)}", token_a, {"amount": "300.00"}
    )
    check("an entry keeps a category that was switched off later", status, 200)
    call("PUT", f"/trackers/{one}/categories/{groceries['id']}", token_a)
    check("the balance follows that correction too", balance_of(wallet["id"]), "1200.50")

    status, rows = call("GET", f"/trackers/{one}/transactions?wallet_id={wallet['id']}", token_a)
    opening = next((row for row in rows or [] if row["type"] == "opening"), {})

    status, opening_v2 = call(
        "PATCH", f"/transactions/{opening.get('id')}", token_a, {"amount": "1000.00"}
    )
    check("correct an opening balance", status, 200)
    check("the balance follows the opening balance", balance_of(wallet["id"]), "700.00")

    status, _ = call(
        "PATCH", f"/transactions/{id_of(opening_v2)}", token_a, {"type": "expense"}
    )
    check("an opening balance cannot change type", status, 409)

    status, same = call("PATCH", f"/transactions/{pay['id']}", token_a, {"amount": "30000.00"})
    check("saving without a change adds nothing", (status, id_of(same)), (200, pay["id"]))

    status, rows = call("GET", f"/trackers/{one}/transactions", token_a)
    check(
        "listed newest first, with the opening balance last",
        [(row["type"], row["amount"]) for row in rows or []],
        [("income", "200.00"), ("income", "30000.00"), ("expense", "300.00"), ("opening", "1000.00")],
    )
    check(
        "only the latest version of each entry is listed",
        [row["id"] for row in rows or []],
        [id_of(bill_v4), pay["id"], id_of(groceries_v3), id_of(opening_v2)],
    )

    status, rows = call("GET", f"/trackers/{one}/transactions?wallet_id={bank['id']}", token_a)
    check("list one wallet's transactions", [row["amount"] for row in rows or []], ["200.00", "30000.00"])

    # Deleting needs a code sent to the account's email. The smoke test's
    # addresses can never receive email, so for them alone the code comes back
    # in the response.
    status, sent = call("POST", f"/transactions/{id_of(groceries_v3)}/delete-code", token_a)
    check("ask for a code to delete a transaction", status, 200)
    check(
        "it says where the code went, without the whole address",
        (sent or {}).get("sent_to"),
        "sm•••@example.com",
    )
    check("and how long it lasts", (sent or {}).get("expires_in_minutes"), 10)
    code = (sent or {}).get("test_code") or ""
    check("a test address gets the code back", (len(code), code.isdigit()), (6, True))

    status, _ = call("POST", f"/transactions/{id_of(groceries_v3)}/delete-code", token_a)
    check("asking again straight away is refused", status, 429)

    deletion = {"reason": "Bought by mistake", "code": code}
    wrong_code = "000000" if code != "000000" else "111111"

    status, _ = call("DELETE", f"/transactions/{id_of(groceries_v3)}", token_a)
    check("deleting needs a reason and the code", status, 422)

    status, _ = call(
        "DELETE", f"/transactions/{id_of(groceries_v3)}", token_a, {**deletion, "reason": "   "}
    )
    check("a blank reason is refused", status, 422)

    status, body = call(
        "DELETE", f"/transactions/{id_of(groceries_v3)}", token_a, {**deletion, "code": wrong_code}
    )
    check(
        "a wrong code is refused, saying how many tries are left",
        (status, (body or {}).get("detail")),
        (403, "Incorrect code. 4 tries left"),
    )

    status, _ = call("DELETE", f"/transactions/{pay['id']}", token_a, deletion)
    check("a code only works for the entry it was sent for", status, 403)

    status, _ = call("DELETE", f"/transactions/{id_of(groceries_v3)}", token_a, deletion)
    check("delete a transaction", status, 204)
    check("its amount returns to the wallet", balance_of(wallet["id"]), "1000.00")

    status, rows = call("GET", f"/trackers/{one}/transactions?wallet_id={wallet['id']}", token_a)
    check(
        "a deleted transaction stays in the list, marked as deleted",
        [(row["id"], row["is_deleted"]) for row in rows or [] if row["type"] == "expense"],
        [(id_of(groceries_v3), True)],
    )

    status, _ = call("DELETE", f"/transactions/{id_of(groceries_v3)}", token_a, deletion)
    check("a deleted transaction cannot be deleted again", status, 409)

    print("\nAdjustments")

    status, history = call("GET", f"/transactions/{id_of(bill_v4)}/history", token_a)
    check(
        "an entry's history holds every version and every reversal, oldest first",
        [
            (row["type"], row["amount"], row["is_reversed"], row["reverses_id"] is not None)
            for row in history or []
        ],
        [
            ("expense", "2000.00", True, False),
            ("expense", "-2000.00", False, True),
            ("expense", "200.00", True, False),
            ("expense", "-200.00", False, True),
            ("expense", "200.00", True, False),
            ("expense", "-200.00", False, True),
            ("income", "200.00", False, False),
        ],
    )
    check(
        "a reversal takes the date and wallet of the entry it cancels",
        [(row["transaction_date"], row["wallet_name"]) for row in (history or [])[:2]],
        [(bill["transaction_date"], "Cash")] * 2,
    )

    check(
        "a correction's reason is kept with it",
        (history or [{}, {}])[1].get("reason"),
        "Typed one zero too many",
    )

    status, from_original = call("GET", f"/transactions/{bill['id']}/history", token_a)
    check(
        "the same history comes back from any of its versions",
        [row["id"] for row in from_original or []],
        [row["id"] for row in history or []],
    )

    reversal_id = (history or [{}, {}])[1].get("id")
    status, _ = call("PATCH", f"/transactions/{reversal_id}", token_a, {"amount": "1.00"})
    check("a reversal cannot be corrected", status, 409)

    status, _ = call("DELETE", f"/transactions/{reversal_id}", token_a, deletion)
    check("a reversal cannot be deleted", status, 409)

    status, deleted = call("GET", f"/transactions/{id_of(groceries_v3)}", token_a)
    check(
        "a deleted entry is kept, marked as reversed and deleted",
        ((deleted or {}).get("is_reversed"), (deleted or {}).get("is_deleted")),
        (True, True),
    )

    status, history = call("GET", f"/transactions/{id_of(groceries_v3)}/history", token_a)
    check(
        "the reason for deleting is kept with it",
        (history or [{}])[-1].get("reason"),
        "Bought by mistake",
    )

    status, rows = call(
        "GET", f"/trackers/{one}/transactions?wallet_id={wallet['id']}&include_adjustments=true", token_a
    )
    check(
        "the full record can still be listed on request",
        len(rows or []),
        # Groceries: three versions and three reversals. Opening: two and one.
        # The bill's first two versions and their reversals were in Cash too.
        6 + 3 + 4,
    )

    print("\nRestoring")

    status, _ = call("POST", f"/transactions/{pay['id']}/restore", token_a, {})
    check("only a deleted entry can be restored", status, 409)

    status, _ = call("POST", f"/transactions/{reversal_id}/restore", token_a, {})
    check("a reversal cannot be restored", status, 409)

    status, restored = call(
        "POST",
        f"/transactions/{id_of(groceries_v3)}/restore",
        token_a,
        {"reason": "  Bought after all  "},
    )
    check("restore a deleted transaction", status, 200)
    check(
        "it comes back as a new entry, in place of the deleted one",
        (
            id_of(restored) not in (None, id_of(groceries_v3)),
            (restored or {}).get("replaces_id"),
            (restored or {}).get("is_restoration"),
        ),
        (True, id_of(groceries_v3), True),
    )
    check("the reason for restoring is kept with it", (restored or {}).get("reason"), "Bought after all")
    check("its amount leaves the wallet again", balance_of(wallet["id"]), "700.00")

    status, rows = call("GET", f"/trackers/{one}/transactions?wallet_id={wallet['id']}", token_a)
    check(
        "the restored entry is listed instead of the deleted one",
        [(row["id"], row["is_deleted"]) for row in rows or [] if row["type"] == "expense"],
        [(id_of(restored), False)],
    )

    status, _ = call("POST", f"/transactions/{id_of(groceries_v3)}/restore", token_a, {})
    check("a deleted entry can only be restored once", status, 409)

    status, history = call("GET", f"/transactions/{id_of(restored)}/history", token_a)
    check(
        "the deletion and the restoration both stay in the history",
        [
            (row["amount"], row["reverses_id"] is not None, row["is_restoration"])
            for row in (history or [])[-3:]
        ],
        [("300.00", False, False), ("-300.00", True, False), ("300.00", False, True)],
    )

    check("a restored entry can be deleted again", delete_entry(id_of(restored), "Returned it"), 204)
    check("and its amount returns to the wallet", balance_of(wallet["id"]), "1000.00")

    status, spare = call(
        "POST",
        f"/trackers/{one}/wallets",
        token_a,
        {"name": "Spare", "currency": "PHP", "opening_balance": "0.00"},
    )
    status, mistake = call(
        "POST",
        "/transactions",
        token_a,
        {**expense, "wallet_id": id_of(spare), "amount": "5.00"},
    )

    _, sent = call("POST", f"/transactions/{id_of(mistake)}/delete-code", token_a)
    spare_code = (sent or {}).get("test_code") or ""
    spare_wrong = "000000" if spare_code != "000000" else "111111"
    for _ in range(4):
        call(
            "DELETE",
            f"/transactions/{id_of(mistake)}",
            token_a,
            {"reason": "Test", "code": spare_wrong},
        )
    status, body = call(
        "DELETE", f"/transactions/{id_of(mistake)}", token_a, {"reason": "Test", "code": spare_wrong}
    )
    check(
        "the fifth wrong code uses the code up",
        (status, (body or {}).get("detail")),
        (403, "Incorrect code. Send a new one"),
    )
    status, body = call(
        "DELETE", f"/transactions/{id_of(mistake)}", token_a, {"reason": "Test", "code": spare_code}
    )
    check(
        "after five wrong tries even the right code is refused",
        (status, (body or {}).get("detail")),
        (403, "Too many wrong tries. Send a new code"),
    )
    check(
        "a used-up code can be replaced straight away",
        delete_entry(id_of(mistake)),
        204,
    )
    status, _ = call("DELETE", f"/wallets/{id_of(spare)}", token_a)
    check("a wallet whose entries were all deleted can be deleted", status, 204)

    status, _ = call("DELETE", f"/categories/{salary['id']}", token_a)
    check("a category with transactions cannot be deleted", status, 409)

    status, _ = call("POST", "/categories", token_a, {"name": "Bonus", "parent_id": salary["id"]})
    check("give a category in use its first subcategory", status, 201)

    status, rows = call("GET", f"/trackers/{one}/transactions?wallet_id={bank['id']}", token_a)
    check(
        "its transactions move into General",
        sorted({(row["parent_category_name"], row["category_name"]) for row in rows or []}),
        [("Salary", "General")],
    )

    status, _ = call("DELETE", f"/wallets/{bank['id']}", token_a)
    check("a wallet with transactions cannot be deleted", status, 409)

    status, coins = call(
        "POST",
        f"/trackers/{one}/wallets",
        token_a,
        {
            "name": "Coins",
            "currency": "PHP",
            "opening_balance": "50.00",
            "opening_date": today.isoformat(),
        },
    )
    status, _ = call("DELETE", f"/wallets/{coins['id']}", token_a)
    check("a wallet with only its opening balance can be deleted", status, 204)
    
    print("\nDate ranges and summaries")
    long_ago = "2000-01-01"
    tomorrow = (today + timedelta(days=1)).isoformat()

    status, rows = call(
        "GET", f"/trackers/{one}/transactions?start_date={long_ago}&end_date={long_ago}", token_a
    )
    check("a range with nothing in it lists nothing", (status, rows), (200, []))

    status, everything = call("GET", f"/trackers/{one}/transactions", token_a)
    status, ranged = call(
        "GET", f"/trackers/{one}/transactions?start_date={long_ago}&end_date={tomorrow}", token_a
    )
    check(
        "a range covering everything lists everything",
        [row["id"] for row in ranged or []],
        [row["id"] for row in everything or []],
    )

    status, _ = call(
        "GET", f"/trackers/{one}/transactions?start_date={tomorrow}&end_date={long_ago}", token_a
    )
    check("a range that ends before it starts is refused", status, 422)

    status, _ = call(
        "POST",
        "/transactions",
        token_a,
        {**expense, "amount": "100.00", "category_id": groceries["id"]},
    )

    def summary_rows(start: str, end: str):
        _, body = call(
            "GET", f"/trackers/{one}/summary?start_date={start}&end_date={end}", token_a
        )
        body = body or {}
        wallets = [
            (row["wallet_name"], row["opening"], row["income"], row["expenses"], row["ending"])
            for row in body.get("wallets", [])
        ]
        totals = [
            (row["currency"], row["opening"], row["income"], row["expenses"], row["ending"])
            for row in body.get("totals", [])
        ]
        return wallets, totals

    wallets, totals = summary_rows(long_ago, tomorrow)
    check(
        "a summary splits each wallet into opening, income, expenses and ending",
        wallets,
        [
            ("Cash", "1000.00", "0.00", "100.00", "900.00"),
            ("Bank", "0.00", "30200.00", "0.00", "30200.00"),
        ],
    )
    check(
        "and adds them up per currency",
        totals,
        [("PHP", "1000.00", "30200.00", "100.00", "31100.00")],
    )
    check(
        "the ending balance matches the wallet's balance",
        (wallets[0][4] if wallets else None, balance_of(wallet["id"])),
        ("900.00", "900.00"),
    )

    later = (today + timedelta(days=2)).isoformat()
    wallets, _ = summary_rows(later, later)
    check(
        "a range after every entry opens with the full balance",
        wallets,
        [
            ("Cash", "900.00", "0.00", "0.00", "900.00"),
            ("Bank", "30200.00", "0.00", "0.00", "30200.00"),
        ],
    )

    status, _ = call(
        "GET", f"/trackers/{one}/summary?start_date={tomorrow}&end_date={long_ago}", token_a
    )
    check("a summary range that ends before it starts is refused", status, 422)

    print("\nOthers")

    status, others = call(
        "POST", f"/trackers/{one}/categories/others", token_a, {"kind": "expense"}
    )
    check(
        "ask for the tracker's Others category",
        (status, (others or {}).get("name"), (others or {}).get("is_enabled")),
        (200, "Others", True),
    )

    status, again = call(
        "POST", f"/trackers/{one}/categories/others", token_a, {"kind": "expense"}
    )
    check("asking again gives the same one", id_of(again), id_of(others))

    status, others_two = call(
        "POST", f"/trackers/{two}/categories/others", token_a, {"kind": "expense"}
    )
    check(
        "each tracker has an Others of its own",
        ((others or {}).get("is_global"), id_of(others_two) not in (None, id_of(others))),
        (False, True),
    )

    status, under_bills = call(
        "POST", f"/trackers/{one}/categories/others", token_a, {"parent_id": bills["id"]}
    )
    check(
        "a category with subcategories has an Others of its own",
        (status, (under_bills or {}).get("parent_id")),
        (200, bills["id"]),
    )

    status, _ = call(
        "POST", f"/trackers/{one}/categories/others", token_a, {"parent_id": groceries["id"]}
    )
    check("a category without subcategories has no Others under it", status, 409)

    pet = {**expense, "category_id": id_of(others), "amount": "80.00"}
    status, _ = call("POST", "/transactions", token_a, pet)
    check("an entry under Others has to say what it was", status, 422)

    status, _ = call("POST", "/transactions", token_a, {**expense, "other_name": "Pet food"})
    check("an entry under any other category has no Others name", status, 409)

    status, pet_one = call("POST", "/transactions", token_a, {**pet, "other_name": "Pet food"})
    check(
        "record an entry under Others",
        (status, (pet_one or {}).get("other_name")),
        (201, "Pet food"),
    )
    status, pet_two = call("POST", "/transactions", token_a, {**pet, "other_name": "  pet FOOD "})
    status, toys = call("POST", "/transactions", token_a, {**pet, "other_name": "Toys"})

    status, promoted = call(
        "POST", f"/transactions/{id_of(pet_one)}/promote-other", token_a, {}
    )
    check(
        "give an Others entry a category of its own",
        (status, (promoted or {}).get("category_name"), (promoted or {}).get("created")),
        (200, "Pet food", True),
    )
    check(
        "every entry of that name moves into it, whatever its capitals",
        (promoted or {}).get("moved"),
        2,
    )

    status, rows = call("GET", f"/trackers/{one}/transactions?wallet_id={wallet['id']}", token_a)
    check(
        "the moved entries are filed under it, and Toys stays in Others",
        sorted(
            (row["category_name"], row["other_name"] or "")
            for row in rows or []
            if row["amount"] == "80.00"
        ),
        [("Others", "Toys"), ("Pet food", ""), ("Pet food", "")],
    )

    status, history = call("GET", f"/transactions/{id_of(pet_two)}/history", token_a)
    check(
        "each move is kept as an adjustment, with its reason",
        (history or [{}, {}])[1].get("reason"),
        "Moved from Others to Pet food",
    )

    status, _ = call(
        "POST", f"/transactions/{id_of(pet_one)}/promote-other", token_a, {}
    )
    check("an entry that has been moved cannot be moved again", status, 409)

    status, shopping = call(
        "POST", "/transactions", token_a, {**pet, "other_name": "GROCERIES"}
    )
    status, reused = call(
        "POST", f"/transactions/{id_of(shopping)}/promote-other", token_a, {}
    )
    check(
        "a name that is already a category, in any capitals, uses that category",
        (status, (reused or {}).get("category_id"), (reused or {}).get("created")),
        (200, groceries["id"], False),
    )

    status, _ = call(
        "POST",
        f"/transactions/{id_of(toys)}/promote-other",
        token_a,
        {"name": "others"},
    )
    check("Others cannot be the new category's name", status, 422)

    status, internet = call(
        "POST",
        "/transactions",
        token_a,
        {**pet, "category_id": id_of(under_bills), "other_name": "Internet"},
    )
    status, promoted = call(
        "POST", f"/transactions/{id_of(internet)}/promote-other", token_a, {}
    )
    check(
        "the new category is named in full",
        (promoted or {}).get("category_name"),
        "Bills › Internet",
    )
    status, rows = call("GET", f"/trackers/{one}/categories", token_a)
    check(
        "an entry under Bills › Others becomes another of Bills' subcategories",
        next((row["parent_id"] for row in rows or [] if row["name"] == "Internet"), None),
        bills["id"],
    )

    status, vet = call("POST", "/transactions", token_a, {**pet, "other_name": "Vet"})
    status, promoted = call(
        "POST",
        f"/transactions/{id_of(vet)}/promote-other",
        token_a,
        {"name": "Pets", "subcategory_name": "Vet visits"},
    )
    check(
        "an Others entry can become a new category with a sub-category",
        (status, (promoted or {}).get("category_name"), (promoted or {}).get("created")),
        (200, "Pets › Vet visits", True),
    )
    status, rows = call("GET", f"/trackers/{one}/categories", token_a)
    pets = next((row for row in rows or [] if row["name"] == "Pets"), {})
    check(
        "both are made and switched on, one inside the other",
        (
            pets.get("is_enabled"),
            pets.get("has_children"),
            next((row["parent_id"] for row in rows or [] if row["name"] == "Vet visits"), None),
        ),
        (True, True, pets.get("id")),
    )

    status, water_bill = call(
        "POST",
        "/transactions",
        token_a,
        {**pet, "category_id": id_of(under_bills), "other_name": "Water filter"},
    )
    status, _ = call(
        "POST",
        f"/transactions/{id_of(water_bill)}/promote-other",
        token_a,
        {"subcategory_name": "Parts"},
    )
    check("an entry under Bills › Others cannot make a third level", status, 409)

    status, _ = call(
        "POST",
        f"/transactions/{id_of(water_bill)}/promote-other",
        token_a,
        {"name": "Groceries", "subcategory_name": "Snacks"},
    )
    check("an entry under Bills › Others cannot make a third level either way", status, 409)

    status, snack = call("POST", "/transactions", token_a, {**pet, "other_name": "Snack"})
    status, _ = call(
        "POST",
        f"/transactions/{id_of(snack)}/promote-other",
        token_a,
        {"name": "groceries", "subcategory_name": "Snacks"},
    )
    check("a category without sub-categories is not given its first one from here", status, 409)

    status, renamed = call(
        "PATCH", f"/transactions/{id_of(toys)}", token_a, {"other_name": "Pet toys"}
    )
    check(
        "an Others entry's name can be corrected",
        (status, (renamed or {}).get("other_name")),
        (200, "Pet toys"),
    )

    status, moved_out = call(
        "PATCH", f"/transactions/{id_of(renamed)}", token_a, {"category_id": groceries["id"]}
    )
    check(
        "moving an entry out of Others drops its Others name",
        (status, (moved_out or {}).get("other_name")),
        (200, None),
    )

    status, _ = call("POST", f"/transactions/{pay['id']}/promote-other", token_a, {})
    check("only an entry under Others can be given a category this way", status, 409)

    print("\nNew categories while adding an entry")
    make = f"/trackers/{one}/categories/find-or-create"

    status, books = call("POST", make, token_a, {"kind": "expense", "name": "  Books  "})
    check(
        "make a category from the add form",
        (
            status,
            (books or {}).get("name"),
            (books or {}).get("parent_id"),
            (books or {}).get("is_enabled"),
        ),
        (200, "Books", None, True),
    )
    status, again = call("POST", make, token_a, {"name": "BOOKS"})
    check("asking again in other capitals gives the same one", id_of(again), id_of(books))

    status, _ = call("POST", "/transactions", token_a, {**expense, "category_id": id_of(books)})
    check("an entry can be filed under it straight away", status, 201)

    status, seeds = call("POST", make, token_a, {"name": "Garden", "subcategory_name": "Seeds"})
    status, rows = call("GET", f"/trackers/{one}/categories", token_a)
    garden = next((row for row in rows or [] if row["name"] == "Garden"), {})
    check(
        "a new category with a sub-category gives back the sub-category",
        (status, (seeds or {}).get("name"), (seeds or {}).get("parent_id"), garden.get("is_enabled")),
        (200, "Seeds", garden.get("id"), True),
    )

    status, food = call("POST", make, token_a, {"name": "pets", "subcategory_name": "Food"})
    check(
        "a new sub-category under an existing category that has them",
        (status, (food or {}).get("parent_id")),
        (200, pets.get("id")),
    )

    status, wifi = call("POST", make, token_a, {"parent_id": bills["id"], "name": "Wifi"})
    check(
        "a new sub-category of a chosen category",
        (status, (wifi or {}).get("parent_id")),
        (200, bills["id"]),
    )

    status, _ = call(
        "POST", make, token_a, {"parent_id": bills["id"], "name": "Wifi", "subcategory_name": "Router"}
    )
    check("a sub-category cannot have sub-categories", status, 409)

    status, _ = call("POST", make, token_a, {"parent_id": groceries["id"], "name": "Snacks"})
    check("a category without sub-categories gets its first one from Categories only", status, 409)

    status, _ = call("POST", make, token_a, {"name": "Groceries", "subcategory_name": "Snacks"})
    check("nor by naming it with a sub-category", status, 409)

    status, _ = call("POST", make, token_a, {"name": "others"})
    check("Others cannot be a new category's name", status, 422)

    status, _ = call("POST", make, token_a, {"name": "Garden", "subcategory_name": "OTHERS"})
    check("nor a new sub-category's", status, 422)

    print("\nWhere new categories belong")
    status, rows = call("GET", f"/trackers/{one}/categories", token_a)
    by_name = {row["name"]: row for row in rows or []}
    check(
        "a category made from Others belongs to its tracker unless asked",
        [by_name.get(name, {}).get("is_global") for name in ("Pet food", "Books", "Garden", "Seeds", "Wifi")],
        [False] * 5,
    )

    status, rows = call("GET", f"/trackers/{two}/categories", token_a)
    check(
        "so another tracker never sees it",
        sorted({"Pet food", "Books", "Wifi"} & set(names(rows))),
        [],
    )

    check(
        "an entry says whether its parent category is global",
        (
            (internet or {}).get("parent_category_is_global"),
            (pet_one or {}).get("parent_category_is_global"),
        ),
        (True, None),
    )

    status, hobby = call("POST", "/transactions", token_a, {**pet, "other_name": "Hobby"})
    status, promoted = call(
        "POST", f"/transactions/{id_of(hobby)}/promote-other", token_a, {"is_global": True}
    )
    check(
        "an Others entry can become a global category",
        (status, (promoted or {}).get("is_global")),
        (200, True),
    )
    status, rows = call("GET", f"/trackers/{two}/categories", token_a)
    hobby_two = next((row for row in rows or [] if row["name"] == "Hobby"), {})
    check(
        "which every tracker is offered, switched on only where it was made",
        (hobby_two.get("is_global"), hobby_two.get("is_enabled")),
        (True, False),
    )

    status, tea = call("POST", make, token_a, {"name": "Tea", "is_global": True})
    check("the add form can make a global category too", (tea or {}).get("is_global"), True)

    status, _ = call(
        "POST",
        f"/trackers/{two}/categories/find-or-create",
        token_a,
        {"name": "Books", "is_global": True},
    )
    check("a global category cannot take a name another tracker keeps to itself", status, 409)

    status, wifi_two = call(
        "POST",
        f"/trackers/{two}/categories/find-or-create",
        token_a,
        {"parent_id": bills["id"], "name": "Wifi"},
    )
    check(
        "two trackers can each keep their own sub-category of the same name",
        (status, id_of(wifi_two) not in (None, id_of(wifi))),
        (200, True),
    )

    status, body = call("POST", "/categories", token_a, {"name": "wifi", "parent_id": bills["id"]})
    check(
        "a global sub-category cannot take a name a tracker keeps to itself",
        (status, (body or {}).get("detail")),
        (409, "One of your trackers already has its own category with this name"),
    )
    
    print("\nGlobal sub-categories")
    status, yarn = call(
        "POST", make, token_a, {"name": "Crafts", "subcategory_name": "Yarn", "is_global": True}
    )
    check(
        "a global category's new sub-category belongs to its tracker unless asked",
        (status, (yarn or {}).get("is_global")),
        (200, False),
    )
    status, lessons = call(
        "POST",
        make,
        token_a,
        {
            "name": "Music",
            "subcategory_name": "Lessons",
            "is_global": True,
            "subcategory_is_global": True,
        },
    )
    check(
        "or it can be global too",
        (status, (lessons or {}).get("is_global")),
        (200, True),
    )
    status, rows = call("GET", f"/trackers/{two}/categories", token_a)
    check(
        "another tracker is offered the global one, not the one kept to a tracker",
        sorted({"Crafts", "Yarn", "Music", "Lessons"} & set(names(rows))),
        ["Crafts", "Lessons", "Music"],
    )

    status, flour = call(
        "POST",
        make,
        token_a,
        {"name": "Baking", "subcategory_name": "Flour", "subcategory_is_global": True},
    )
    check(
        "under a category kept to one tracker, a sub-category cannot be global",
        (status, (flour or {}).get("is_global")),
        (200, False),
    )

    status, trip = call("POST", "/transactions", token_a, {**pet, "other_name": "Travel"})
    status, promoted = call(
        "POST",
        f"/transactions/{id_of(trip)}/promote-other",
        token_a,
        {"subcategory_name": "Flights", "is_global": True, "subcategory_is_global": True},
    )
    check(
        "an Others entry can become a global category with a global sub-category",
        (status, (promoted or {}).get("category_name"), (promoted or {}).get("is_global")),
        (200, "Travel › Flights", True),
    )

    print("\nBudget")
    status, budgets = call("POST", "/trackers", token_a, {"name": "Budgets"})
    three = id_of(budgets)
    status, purse = call(
        "POST", f"/trackers/{three}/wallets", token_a, {"name": "Purse", "currency": "PHP"}
    )
    status, dollars = call(
        "POST", f"/trackers/{three}/wallets", token_a, {"name": "Dollars", "currency": "USD"}
    )
    status, allowance = call("POST", "/categories", token_a, {"name": "Allowance", "kind": "income"})
    budget_path = f"/trackers/{three}/budget"

    def budget(start, end, currency=None):
        path = f"{budget_path}?start_date={start}&end_date={end}"
        if currency:
            path += f"&currency={currency}"
        return call("GET", path, token_a)[1] or {}

    def budget_row(body, kind, name, parent=None):
        rows = body.get(kind, {}).get("rows", [])
        if parent is not None:
            rows = next((row["subcategories"] for row in rows if row["name"] == parent), [])
        return next((row for row in rows if row["name"] == name), {})

    def set_expected(category, starts_on, amount, currency="PHP", tracker=None, token=None):
        body = {"currency": currency, "starts_on": starts_on}
        if amount is not None:
            body["amount"] = amount
        return call(
            "PUT",
            f"/trackers/{tracker or three}/budget/{id_of(category)}",
            token or token_a,
            body,
        )

    body = budget("2025-01-01", "2025-01-31")
    check(
        "a budget names its currency and offers the tracker's others",
        (body.get("currency"), body.get("currencies")),
        ("PHP", ["PHP", "USD"]),
    )
    food = budget_row(body, "expense", "Groceries")
    check(
        "a category starts with no expected amount",
        (food.get("expected"), food.get("actual"), food.get("difference")),
        (None, "0.00", None),
    )

    status, saved = set_expected(groceries, "2025-01-15", "3100.00")
    check(
        "an expected amount is set from the month its date falls in",
        (status, (saved or {}).get("starts_on"), (saved or {}).get("amount")),
        (200, "2025-01-01", "3100.00"),
    )

    shopping_trip = {"wallet_id": id_of(purse), "type": "expense", "category_id": id_of(groceries)}
    status, jan_food = call(
        "POST",
        "/transactions",
        token_a,
        {**shopping_trip, "amount": "1000.00", "transaction_date": "2025-01-10"},
    )
    status, feb_food = call(
        "POST",
        "/transactions",
        token_a,
        {**shopping_trip, "amount": "500.00", "transaction_date": "2025-02-03"},
    )
    call(
        "POST",
        "/transactions",
        token_a,
        {
            **shopping_trip,
            "wallet_id": id_of(dollars),
            "amount": "20.00",
            "transaction_date": "2025-01-12",
        },
    )

    food = budget_row(budget("2025-01-01", "2025-01-31"), "expense", "Groceries")
    check(
        "actual is what was spent in the dates, in that currency",
        (food.get("expected"), food.get("actual"), food.get("difference")),
        ("3100.00", "1000.00", "2100.00"),
    )
    check(
        "the amount carries over to later months",
        budget_row(budget("2025-02-01", "2025-02-28"), "expense", "Groceries").get("expected"),
        "3100.00",
    )
    check(
        "a week gets its share of the month by days",
        budget_row(budget("2025-02-03", "2025-02-09"), "expense", "Groceries").get("expected"),
        "775.00",
    )
    check(
        "dates across two months add each month's share",
        budget_row(budget("2025-01-25", "2025-02-07"), "expense", "Groceries").get("expected"),
        "1475.00",
    )

    set_expected(groceries, "2025-02-01", "2800.00")
    check(
        "a change applies from its month, and earlier months keep theirs",
        (
            budget_row(budget("2025-01-01", "2025-01-31"), "expense", "Groceries").get("expected"),
            budget_row(budget("2025-02-01", "2025-02-28"), "expense", "Groceries").get("expected"),
        ),
        ("3100.00", "2800.00"),
    )
    check(
        "a year adds up every month's amount",
        budget_row(budget("2025-01-01", "2025-12-31"), "expense", "Groceries").get("expected"),
        "33900.00",
    )

    body = budget("2025-01-01", "2025-01-31", "USD")
    usd_food = budget_row(body, "expense", "Groceries")
    check(
        "each currency has its own expected and actual amounts",
        (body.get("currency"), usd_food.get("expected"), usd_food.get("actual")),
        ("USD", None, "20.00"),
    )
    check(
        "a currency no wallet uses falls back to the main one",
        budget("2025-01-01", "2025-01-31", "EUR").get("currency"),
        "PHP",
    )

    set_expected(electricity, "2025-01-01", "1500.00")
    body = budget("2025-01-01", "2025-01-31")
    bills_row = budget_row(body, "expense", "Bills")
    check(
        "a category with subcategories adds theirs up",
        (
            bills_row.get("expected"),
            budget_row(body, "expense", "Electricity", parent="Bills").get("expected"),
            bills_row.get("amounts"),
        ),
        ("1500.00", "1500.00", []),
    )
    total = body.get("expense", {}).get("total", {})
    check(
        "the table's total adds up its rows",
        (total.get("expected"), total.get("actual"), total.get("difference")),
        ("4600.00", "1000.00", "3600.00"),
    )
    status, _ = set_expected(bills, "2025-01-01", "100.00")
    check("a category with subcategories cannot have an amount of its own", status, 409)

    call("PATCH", f"/transactions/{id_of(jan_food)}", token_a, {"amount": "1200.00"})
    delete_entry(id_of(feb_food))
    check(
        "actual follows corrections and deletions",
        (
            budget_row(budget("2025-01-01", "2025-01-31"), "expense", "Groceries").get("actual"),
            budget_row(budget("2025-02-01", "2025-02-28"), "expense", "Groceries").get("actual"),
        ),
        ("1200.00", "0.00"),
    )

    call(
        "POST",
        "/transactions",
        token_a,
        {**shopping_trip, "amount": "3000.00", "transaction_date": "2025-01-20"},
    )
    food = budget_row(budget("2025-01-01", "2025-01-31"), "expense", "Groceries")
    check(
        "spending more than expected puts the difference below zero",
        (food.get("actual"), food.get("difference")),
        ("4200.00", "-1100.00"),
    )

    set_expected(allowance, "2025-01-01", "20000.00")
    call(
        "POST",
        "/transactions",
        token_a,
        {
            "wallet_id": id_of(purse),
            "type": "income",
            "category_id": id_of(allowance),
            "amount": "25000.00",
            "transaction_date": "2025-01-31",
        },
    )
    received = budget_row(budget("2025-01-01", "2025-01-31"), "income", "Allowance")
    check(
        "for income, the difference is what came in over what was expected",
        (received.get("expected"), received.get("actual"), received.get("difference")),
        ("20000.00", "25000.00", "5000.00"),
    )

    status, _ = set_expected(groceries, "2025-03-15", None)
    march = budget("2025-03-01", "2025-03-31")
    check(
        "an expected amount can be cleared from a month on",
        (
            budget_row(march, "expense", "Groceries").get("expected"),
            budget_row(budget("2025-02-01", "2025-02-28"), "expense", "Groceries").get("expected"),
        ),
        (None, "2800.00"),
    )
    check(
        "every change is listed, oldest first",
        [
            (change["starts_on"], change["amount"])
            for change in budget_row(march, "expense", "Groceries").get("amounts", [])
        ],
        [("2025-01-01", "3100.00"), ("2025-02-01", "2800.00"), ("2025-03-01", None)],
    )

    status, _ = set_expected(groceries, "2025-01-01", "-5.00")
    check("an expected amount cannot be below zero", status, 422)
    status, _ = set_expected(groceries, "2025-01-01", "10.00", currency="EUR")
    check("nor in a currency none of the tracker's wallets use", status, 409)
    status, _ = set_expected(wifi, "2025-01-01", "10.00")
    check("nor for a category another tracker keeps to itself", status, 409)
    status, _ = call("GET", f"{budget_path}?start_date=2025-02-01&end_date=2025-01-01", token_a)
    check("a budget's dates cannot run backwards", status, 422)

    call("DELETE", f"/trackers/{three}/categories/{water['id']}", token_a)
    check(
        "a switched-off category with nothing in the dates is left out",
        budget_row(budget("2025-01-01", "2025-01-31"), "expense", "Water", parent="Bills"),
        {},
    )

    status, plants = call("POST", "/categories", token_a, {"name": "Plants"})
    set_expected(plants, "2025-01-01", "300.00")
    call("POST", "/categories", token_a, {"name": "Tools", "parent_id": id_of(plants)})
    body = budget("2025-01-01", "2025-01-31")
    general = budget_row(body, "expense", "General", parent="Plants")
    check(
        "a category's first subcategory moves its expected amount into General",
        (general.get("expected"), budget_row(body, "expense", "Plants").get("expected")),
        ("300.00", "300.00"),
    )
    status, _ = call("DELETE", f"/categories/{general.get('category_id')}", token_a)
    check("a category with expected amounts can still be deleted", status, 204)

    status, short_lived = call(
        "POST", "/trackers", token_a, {"name": "Short-lived", "category_ids": []}
    )
    status, tin = call(
        "POST", f"/trackers/{id_of(short_lived)}/wallets", token_a, {"name": "Tin", "currency": "PHP"}
    )
    status, _ = set_expected(groceries, "2025-01-01", "50.00", tracker=id_of(short_lived))
    check(
        "each tracker keeps its own expected amounts",
        (
            status,
            budget_row(budget("2025-01-01", "2025-01-31"), "expense", "Groceries").get("expected"),
        ),
        (200, "3100.00"),
    )
    call("DELETE", f"/wallets/{id_of(tin)}", token_a)
    status, _ = call("DELETE", f"/trackers/{id_of(short_lived)}", token_a)
    check("a tracker with expected amounts can still be deleted", status, 204)

    status, _ = call("GET", f"{budget_path}?start_date=2025-01-01&end_date=2025-01-31", token_b)
    check("another account's budget is not found", status, 404)
    status, _ = set_expected(groceries, "2025-01-01", "1.00", token=token_b)
    check("another account cannot set an expected amount", status, 404)

    print("\nTransfers")
    status, moves = call("POST", "/trackers", token_a, {"name": "Moves"})
    four = id_of(moves)
    status, spend = call(
        "POST",
        f"/trackers/{four}/wallets",
        token_a,
        {"name": "Spending", "currency": "PHP", "opening_balance": "1000.00"},
    )
    status, savings = call(
        "POST", f"/trackers/{four}/wallets", token_a, {"name": "Savings", "currency": "PHP"}
    )
    status, travel = call(
        "POST", f"/trackers/{four}/wallets", token_a, {"name": "Travel", "currency": "USD"}
    )

    def balances():
        rows = call("GET", f"/trackers/{four}/wallets", token_a)[1] or []
        return {row["name"]: row["balance"] for row in rows}

    def transfer_rows(path_suffix=""):
        rows = call(
            "GET",
            f"/trackers/{four}/transactions?start_date=2025-04-01&end_date=2025-04-30{path_suffix}",
            token_a,
        )[1] or []
        return [row for row in rows if row["type"].startswith("transfer")]

    move = {
        "from_wallet_id": id_of(spend),
        "to_wallet_id": id_of(savings),
        "amount": "300.00",
        "transaction_date": "2025-04-10",
    }
    status, sent = call("POST", "/transfers", token_a, move)
    check(
        "a transfer moves money from one wallet to another",
        (status, (sent or {}).get("type"), (sent or {}).get("to_wallet_name"), (sent or {}).get("to_amount")),
        (201, "transfer_out", "Savings", "300.00"),
    )
    check(
        "both balances follow it",
        (balances().get("Spending"), balances().get("Savings")),
        ("700.00", "300.00"),
    )
    check(
        "it is listed once, as where the money left",
        [(row["wallet_name"], row["to_wallet_name"]) for row in transfer_rows()],
        [("Spending", "Savings")],
    )
    check(
        "and shows up for the wallet it went to",
        len(transfer_rows(f"&wallet_id={id_of(savings)}")),
        1,
    )

    status, body = call(
        "GET",
        f"/trackers/{four}/summary?start_date=2025-04-01&end_date=2025-04-30",
        token_a,
    )
    by_wallet = {row["wallet_name"]: row for row in (body or {}).get("wallets", [])}
    check(
        "the overview counts transfers in and out, never as income or expenses",
        [
            (
                by_wallet.get(name, {}).get("income"),
                by_wallet.get(name, {}).get("expenses"),
                by_wallet.get(name, {}).get("transfers_in"),
                by_wallet.get(name, {}).get("transfers_out"),
                by_wallet.get(name, {}).get("ending"),
            )
            for name in ("Spending", "Savings")
        ],
        [("0.00", "0.00", "0.00", "300.00", "-300.00"), ("0.00", "0.00", "300.00", "0.00", "300.00")],
    )

    status, _ = call("POST", "/transfers", token_a, {**move, "to_wallet_id": id_of(spend)})
    check("a transfer needs two different wallets", status, 409)
    status, _ = call("POST", "/transfers", token_a, {**move, "to_wallet_id": wallet["id"]})
    check("and both in the same tracker", status, 409)
    status, _ = call("POST", "/transfers", token_a, {**move, "to_amount": "250.00"})
    check("in one currency, what arrives is what leaves", status, 422)
    status, _ = call("POST", "/transfers", token_a, {**move, "to_wallet_id": id_of(travel)})
    check("between currencies, what arrives has to be given", status, 422)

    status, abroad = call(
        "POST",
        "/transfers",
        token_a,
        {**move, "to_wallet_id": id_of(travel), "amount": "560.00", "to_amount": "10.00"},
    )
    check(
        "between currencies, each wallet moves by its own amount",
        (status, balances().get("Spending"), balances().get("Travel")),
        (201, "140.00", "10.00"),
    )

    status, with_fee = call(
        "POST",
        "/transfers",
        token_a,
        {**move, "amount": "100.00", "fee": "15.00"},
    )

    def expense_rows():
        return [
            row
            for row in call(
                "GET",
                f"/trackers/{four}/transactions?start_date=2025-04-01&end_date=2025-04-30",
                token_a,
            )[1]
            or []
            if row["type"] == "expense"
        ]

    fee_rows = expense_rows()
    check(
        "a fee is recorded as an expense from the sending wallet, under Transfer Fee",
        (
            status,
            (with_fee or {}).get("fee"),
            balances().get("Spending"),
            [
                (row["wallet_name"], row["amount"], row["category_name"], row["fee_for_id"] == id_of(with_fee))
                for row in fee_rows
            ],
        ),
        (201, "15.00", "25.00", [("Spending", "15.00", "Transfer Fee", True)]),
    )
    fee_row = fee_rows[0] if fee_rows else {}
    status, _ = call("PATCH", f"/transactions/{fee_row.get('id')}", token_a, {"amount": "1.00"})
    check("a transfer's fee is not changed by itself", status, 409)

    status, body = call(
        "GET", f"/trackers/{four}/budget?start_date=2025-04-01&end_date=2025-04-30", token_a
    )
    check(
        "a transfer never counts towards the budget, though its fee does",
        budget_row(body or {}, "expense", "Transfer Fee").get("actual"),
        "15.00",
    )

    status, corrected = call(
        "PATCH", f"/transfers/{id_of(sent)}", token_a, {"amount": "400.00", "reason": "Typo"}
    )
    check(
        "a transfer can be corrected as a whole",
        (
            status,
            id_of(corrected) != id_of(sent),
            (corrected or {}).get("to_amount"),
            balances().get("Spending"),
            balances().get("Savings"),
        ),
        (200, True, "400.00", "-75.00", "500.00"),
    )
    status, rerouted = call(
        "PATCH",
        f"/transfers/{id_of(corrected)}",
        token_a,
        {"to_wallet_id": id_of(travel), "to_amount": "7.00"},
    )
    check(
        "including where it went",
        (status, (rerouted or {}).get("to_wallet_name"), balances().get("Savings"), balances().get("Travel")),
        (200, "Travel", "100.00", "17.00"),
    )
    status, _ = call(
        "PATCH",
        f"/transfers/{id_of(rerouted)}",
        token_a,
        {"to_wallet_id": id_of(savings), "amount": "50.00"},
    )
    check("moving it to one currency needs no amount received", status, 200)
    status, _ = call("PATCH", f"/transactions/{id_of(with_fee)}", token_a, {"amount": "1.00"})
    check("a transfer is not corrected as an ordinary entry", status, 409)

    status, history = call("GET", f"/transactions/{id_of(sent)}/history", token_a)
    check(
        "its history keeps every version",
        [row["type"] for row in history or [] if row["reverses_id"] is None],
        ["transfer_out"] * 4,
    )

    current = transfer_rows()
    latest = next((row for row in current if not row["is_reversed"] and row["to_wallet_name"] == "Savings" and row["amount"] == "50.00"), {})
    arrival_id = (latest.get("id") or 0) + 1
    status, arrival = call("GET", f"/transactions/{arrival_id}", token_a)
    status, _ = call("POST", f"/transactions/{arrival_id}/delete-code", token_a)
    check(
        "where a transfer arrived cannot be deleted by itself",
        ((arrival or {}).get("type"), status),
        ("transfer_in", 409),
    )

    before = balances()
    check("a transfer is deleted with a code like any entry", delete_entry(id_of(latest)), 204)
    check(
        "and both wallets get their money back",
        (before.get("Spending"), before.get("Savings"), balances().get("Spending"), balances().get("Savings")),
        ("275.00", "150.00", "325.00", "100.00"),
    )
    status, back = call("POST", f"/transactions/{id_of(latest)}/restore", token_a, {})
    check(
        "a deleted transfer can be restored",
        (status, (back or {}).get("to_wallet_name"), balances()),
        (200, "Savings", before),
    )

    def current_fees(transfer):
        return [
            (row["amount"], row["is_deleted"])
            for row in expense_rows()
            if row["fee_for_id"] == id_of(transfer)
        ]

    status, refeed = call(
        "PATCH", f"/transfers/{id_of(with_fee)}", token_a, {"fee": "20.00", "reason": "Bank charged more"}
    )
    check(
        "a transfer's fee is corrected along with it",
        (status, (refeed or {}).get("fee"), balances().get("Spending"), current_fees(refeed), current_fees(with_fee)),
        (200, "20.00", "270.00", [("20.00", False)], []),
    )
    status, unfeed = call("PATCH", f"/transfers/{id_of(refeed)}", token_a, {"fee": None})
    check(
        "and removed by sending no fee, which deletes it",
        (status, (unfeed or {}).get("fee"), balances().get("Spending"), current_fees(refeed)),
        (200, None, "290.00", [("20.00", True)]),
    )
    status, feed = call("PATCH", f"/transfers/{id_of(unfeed)}", token_a, {"fee": "5.00"})
    added = [row for row in expense_rows() if row["fee_for_id"] == id_of(feed)]
    check(
        "a fee can be added while correcting, under the same Transfer Fee",
        (
            status,
            (feed or {}).get("fee"),
            balances().get("Spending"),
            [(row["category_id"] == fee_row.get("category_id"), row["replaces_id"]) for row in added],
        ),
        (200, "5.00", "285.00", [(True, None)]),
    )
    check(
        "deleting a transfer deletes its fee too",
        (delete_entry(id_of(feed)), balances().get("Spending"), balances().get("Savings"), current_fees(feed)),
        (204, "390.00", "50.00", [("5.00", True)]),
    )
    status, back = call("POST", f"/transactions/{id_of(feed)}/restore", token_a, {})
    check(
        "and restoring it brings the fee back",
        (status, (back or {}).get("fee"), balances().get("Spending"), current_fees(back)),
        (200, "5.00", "285.00", [("5.00", False)]),
    )
    
    status, _ = call("DELETE", f"/wallets/{id_of(savings)}", token_a)
    check("a wallet a transfer went to cannot be deleted", status, 409)

    status, _ = call("POST", "/transfers", token_b, move)
    check("another account's wallets cannot be used for a transfer", status, 404)

    print("\nOwnership")
    status, _ = call("GET", f"/trackers/{one}", token_b)
    check("another account's tracker is not found", status, 404)

    status, _ = call("GET", f"/trackers/{one}/categories", token_b)
    check("another account's tracker categories are not found", status, 404)

    status, _ = call("GET", f"/wallets/{wallet['id']}", token_b)
    check("another account's wallet is not found", status, 404)
    
    status, _ = call("GET", f"/transactions/{pay['id']}", token_b)
    check("another account's transaction is not found", status, 404)

    status, _ = call("PATCH", f"/transactions/{pay['id']}", token_b, {"amount": "1.00"})
    check("another account's transaction cannot be changed", status, 404)
    
    status, _ = call("GET", f"/transactions/{pay['id']}/history", token_b)
    check("another account's transaction history is not found", status, 404)
    
    status, _ = call(
        "POST", f"/trackers/{one}/categories/others", token_b, {"kind": "expense"}
    )
    check("another account's tracker has no Others to give", status, 404)

    status, _ = call("POST", f"/transactions/{pay['id']}/promote-other", token_b, {})
    check("another account's entry cannot be given a category", status, 404)

    status, _ = call("POST", f"/transactions/{pay['id']}/delete-code", token_b)
    check("no deletion code is sent for another account's entry", status, 404)

    status, _ = call("POST", f"/transactions/{id_of(groceries_v3)}/restore", token_b, {})
    check("another account's entry cannot be restored", status, 404)

    status, _ = call("POST", f"/trackers/{one}/categories/find-or-create", token_b, {"name": "Mine"})
    check("no category can be made in another account's tracker", status, 404)

    status, _ = call("GET", f"/trackers/{one}/transactions", token_b)
    check("another account's transactions cannot be listed", status, 404)

    status, _ = call("POST", "/transactions", token_b, expense)
    check("nothing can be recorded in another account's wallet", status, 404)
    
    status, _ = call(
        "GET",
        f"/trackers/{one}/summary?start_date=2000-01-01&end_date=2000-01-31",
        token_b,
    )
    check("another account's summary is not found", status, 404)

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