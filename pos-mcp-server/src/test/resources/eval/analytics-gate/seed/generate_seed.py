#!/usr/bin/env python3
"""TRACKB analytics-gate seed generator (deterministic, stdlib-only, Python 3.9+).

Emits one SQL file per target database into seed/sql/:

    pos_invoice_db.sql      invoices, invoice_items, payment_intents, refund_records,
                            deposit_credit(+application), ext_workorder, ext_customer_party,
                            ext_people_employee, ext_location
    pos_accounting_db.sql   ext_invoice, receivable_payment, payment_application(+reversal),
                            credit_memo, customer_credit(+transaction),
                            ext_invoice_payment_reversal, ext_invoice_deposit_credit_application,
                            ap_vendor, vendor_bill, ap_payment, ap_payment_allocation
    pos_workorder_db.sql    workorder, workorder_service, work_order_state_transitions,
                            workorder_labor_entry, ext_invoice, ext_customer_party,
                            ext_people_contact_person, ext_people_contact_user_link
    pos_people_db.sql       employee, employee_location_assignment
    pos_customer_db.sql     person_party, commercial_party

Determinism:
  * every UUID is uuid.uuid5 under the single NS namespace below;
  * every date is derived from EVAL_AS_OF; "months" are the 24 calendar months
    2024-09 .. 2026-08 (25th month 2026-09 is the as-of / boundary-safety month
    and is deliberately left almost empty).

Idempotency: each file begins by deleting prior TRACKB rows (marker column where the
schema has one — invoice_number / workorder_number / bill_number / payment_ref /
*_request_id LIKE 'TRACKB-%', created_by = 'trackb-seed' — and the deterministic ids
themselves where it does not). Inserts are plain INSERTs: a collision with a leftover
row FAILS the load instead of hiding drift.

The narrative spec for every number in here is seed/DATASET.md. The SQL is the
compilation of that document; change them together.
"""

import os
import uuid
from datetime import date, datetime, timedelta

# ---------------------------------------------------------------------------
# constants
# ---------------------------------------------------------------------------

EVAL_AS_OF = date(2026, 9, 1)
NS = uuid.uuid5(uuid.NAMESPACE_URL, "durion://pos-mcp-server/eval/analytics-gate/trackb")
MARK = "TRACKB"
SEED_USER = "trackb-seed"
CCY = "USD"

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sql")


def uid(*parts):
    """Deterministic UUID (string) for a logical entity key."""
    return str(uuid.uuid5(NS, ":".join(parts)))


# 24 invoice-bearing months, oldest first: 2024-09 .. 2026-08.
def build_months():
    months = []
    y, m = 2024, 9
    for _ in range(24):
        months.append(date(y, m, 1))
        m += 1
        if m == 13:
            y, m = y + 1, 1
    return months


MONTHS = build_months()
RECENT_START = date(2025, 9, 1)  # recent 12-month window start (Q7)


def wo_lag_days(d):
    """Designed WO-creation -> invoice-creation lag (whole days) for invoices created in
    month of d: 2.0d through 2026-02, then rising 1d/month Mar..Aug 2026 (Q4 drift)."""
    anchor = date(2026, 3, 1)
    if d < anchor:
        return 2
    return 2 + (d.year - anchor.year) * 12 + (d.month - anchor.month)


# ---------------------------------------------------------------------------
# SQL literal helpers
# ---------------------------------------------------------------------------


def q(v):
    if v is None:
        return "NULL"
    if isinstance(v, bool):
        return "TRUE" if v else "FALSE"
    if isinstance(v, (int, float)):
        return str(v)
    return "'" + str(v).replace("'", "''") + "'"


def money(x):
    return "%.2f" % x  # numeric literal, no quotes


def tstz(d, hh, mm=0, ss=0):
    """timestamptz literal (UTC) on date d."""
    return "%sT%02d:%02d:%02d+00:00" % (d.isoformat(), hh, mm, ss)


def tsnaive(d, hh, mm=0, ss=0):
    """timestamp-without-time-zone literal on date d (read as UTC by the services)."""
    return "%s %02d:%02d:%02d" % (d.isoformat(), hh, mm, ss)


class SqlFile:
    def __init__(self, name):
        self.name = name
        self.lines = []
        self.counts = {}

    def raw(self, s):
        self.lines.append(s)

    def insert(self, table, cols, vals):
        assert len(cols) == len(vals), (table, cols, vals)
        self.lines.append(
            "INSERT INTO %s (%s) VALUES (%s);" % (table, ", ".join(cols), ", ".join(vals))
        )
        self.counts[table] = self.counts.get(table, 0) + 1

    def write(self):
        path = os.path.join(OUT_DIR, self.name)
        with open(path, "w") as f:
            f.write("\n".join(self.lines) + "\n")
        return path


# ---------------------------------------------------------------------------
# dimension entities
# ---------------------------------------------------------------------------

LOCATION_ID = uid("location", "main")
LOCATION_NAME = "TRACKB Main Street Garage"

TECHS = {
    # key: (first, last, username) -- sam is the Q3 reopen technician
    "sam": ("Sam", "Ellison", "sam.ellison"),
    "nadia": ("Nadia", "Torres", "nadia.torres"),
    "alex": ("Alex", "Kim", "alex.kim"),
}
TECH_PERSON = {k: uid("person", k) for k in TECHS}
TECH_EMPLOYEE = {k: uid("employee", k) for k in TECHS}
TECH_LINK = {k: uid("userlink", k) for k in TECHS}

CUSTOMERS = {
    # key: (display name, kind)  kind: person | commercial
    "c1": ("Bluerock Freight LLC", "commercial"),
    "c2": ("Harbor Tool & Die Inc", "commercial"),
    "c3": ("Alice Prescott", "person"),
    "c4": ("Marcus Webb", "person"),
    "c5": ("Dana Whitfield", "person"),
    "c6": ("Peter Okafor", "person"),
}
CUST_ID = {k: uid("customer", k) for k in CUSTOMERS}
CUST_PERSON = {k: uid("customer-person", k) for k in CUSTOMERS}

VENDORS = {
    "v1": "Evergreen Parts Supply",  # +12% YoY average bill (Q17)
    "v2": "Cascade Auto Warehouse",  # top vendor by spend (Q15)
    "v3": "Summit Lubricants",
}
VENDOR_ID = {k: uid("vendor", k) for k in VENDORS}

DIM_TS = tstz(date(2024, 8, 15), 12)  # created/updated for dimension rows

# ---------------------------------------------------------------------------
# invoice / workorder scenario construction
# ---------------------------------------------------------------------------
# Each invoice record:
#   key            logical key (uuid5 seed)
#   cust           customer key
#   amount, labor, parts, fee   money split (labor+parts+fee == amount)
#   created        date invoice created AND finalized (created 14:00Z, finalized 14:30Z)
#   terms, due     payment terms code and due date (due == created + terms days)
#   pay_lag        days to full cash payment, or None (open / special)
#   special        None | 'refund' | 'creditapp' | 'creditmemo' | 'deposit_take'
#                  | 'settlement'
#   wo             workorder logical key (S and D share one); tech: completing technician
#   wo_hours       labor-entry hours for the workorder

INVOICES = []
WORKORDERS = {}  # wo_key -> dict


def add_wo(wo_key, cust, created_dt_date, tech, hours, completed_date, status="COMPLETED",
           replica_created_visible=True):
    if wo_key in WORKORDERS:
        return
    WORKORDERS[wo_key] = {
        "key": wo_key,
        "id": uid("wo", wo_key),
        "cust": cust,
        "created": created_dt_date,
        "tech": tech,
        "hours": hours,
        "completed": completed_date,  # date or None
        "status": status,
        "replica_created_visible": replica_created_visible,
        "reopened": None,  # (marker_date,) filled later
    }


def add_invoice(key, cust, amount, labor, parts, created, terms, due, pay_lag,
                special=None, fee=0.0, wo_key=None, tech=None, hours=None):
    if wo_key is None:
        wo_key = "wo-" + key
    if tech is None:
        tech = CUST_TECH[cust]
    if hours is None:
        hours = CUST_HOURS[cust]
    lag = wo_lag_days(created)
    wo_created = created - timedelta(days=lag)
    add_wo(wo_key, cust, wo_created, tech, hours, created)
    INVOICES.append({
        "key": key,
        "id": uid("inv", key),
        "cust": cust,
        "amount": amount,
        "labor": labor,
        "parts": parts,
        "fee": fee,
        "created": created,
        "terms": terms,
        "due": due,
        "pay_lag": pay_lag,
        "special": special,
        "wo": wo_key,
    })


CUST_TECH = {"c1": "nadia", "c2": "nadia", "c3": "alex", "c4": "sam", "c5": "nadia", "c6": "alex"}
CUST_HOURS = {"c1": 4.00, "c2": 3.00, "c3": 2.00, "c4": 2.50, "c5": 1.00, "c6": 1.00}


def month_key(d):
    return "%04d%02d" % (d.year, d.month)


# --- C1 Bluerock Freight: 1000/mo all 24 months, paid at 20d; Jul+Aug completed by sam
#     (Q3 reopen targets); two extra open invoices (A/R whale, 45% of open A/R).
for m in MONTHS:
    d = m.replace(day=1)
    tech = "sam" if d in (date(2026, 7, 1), date(2026, 8, 1)) else "nadia"
    add_invoice("c1-%s" % month_key(m), "c1", 1000.00, 600.00, 400.00, d,
                "NET_30", d + timedelta(days=30), 20, tech=tech)
add_invoice("c1-open-90plus", "c1", 2000.00, 1200.00, 800.00, date(2026, 4, 15),
            "NET_30", date(2026, 5, 15), None)
add_invoice("c1-open-current", "c1", 2500.00, 1500.00, 1000.00, date(2026, 7, 21),
            "NET_30", date(2026, 8, 20), None)

# --- C2 Harbor Tool & Die: 500/mo through 2026-05 (25d lag), Jun 800, Jul 1000 (rising,
#     Q10); one open 61-90d invoice; Aug = deposit-take (500) + settlement (2500) pair.
for m in MONTHS:
    if m > date(2026, 7, 1):
        continue  # Aug handled by the deposit/settlement pair
    d = m.replace(day=3)
    if m == date(2026, 6, 1):
        amt, lab, par, lagp = 800.00, 500.00, 300.00, 20
    elif m == date(2026, 7, 1):
        amt, lab, par, lagp = 1000.00, 600.00, 400.00, 20
    else:
        amt, lab, par, lagp = 500.00, 300.00, 200.00, 25
    add_invoice("c2-%s" % month_key(m), "c2", amt, lab, par, d,
                "NET_30", d + timedelta(days=30), lagp)
add_invoice("c2-open-6190", "c2", 1500.00, 900.00, 600.00, date(2026, 5, 21),
            "NET_30", date(2026, 6, 20), None)

# Deposit-take pair (D8/#1623): D is the down-payment document (excluded from revenue
# measures), S is the gross settlement invoice. They share one workorder whose replica
# workorder_created_at is NULL so E4's monthly lag average stays exact (nulls excluded).
DEPOSIT_CREDIT_ID = uid("deposit-credit", "c2")
DEPOSIT_ORDER_ID = uid("order", "c2-deposit")
add_wo("wo-c2-deposit", "c2", date(2026, 8, 3), "nadia", 3.00, date(2026, 8, 12),
       replica_created_visible=False)
add_invoice("c2-deposit-take", "c2", 500.00, 0.00, 0.00, date(2026, 8, 5),
            "DUE_ON_RECEIPT", date(2026, 8, 5), 0, special="deposit_take", fee=500.00,
            wo_key="wo-c2-deposit")
add_invoice("c2-settlement", "c2", 2500.00, 1500.00, 1000.00, date(2026, 8, 12),
            "NET_15", date(2026, 8, 27), None, special="settlement",
            wo_key="wo-c2-deposit")

# --- C3 Alice Prescott: 400/mo, paid at 10d; July invoice is the refund/re-pay chain;
#     July WO completed by alex gets one reopen (Q3 decoy, 1 < sam's 2); open 1200 in Aug.
for m in MONTHS:
    d = m.replace(day=1)
    special = "refund" if m == date(2026, 7, 1) else None
    add_invoice("c3-%s" % month_key(m), "c3", 400.00, 250.00, 150.00, d,
                "NET_30", d + timedelta(days=30), None if special else 10, special=special)
add_invoice("c3-open-current", "c3", 1200.00, 700.00, 500.00, date(2026, 8, 13),
            "NET_15", date(2026, 8, 28), None)

# --- C4 Marcus Webb (lapsed, Q8): 900/mo day 25 through 2026-04 paid at 15d; final
#     unpaid 900 created 2026-05-30 due 2026-07-14 (31-60 bucket). Nothing after.
for m in MONTHS:
    if m > date(2026, 4, 1):
        continue
    d = m.replace(day=25)
    add_invoice("c4-%s" % month_key(m), "c4", 900.00, 500.00, 400.00, d,
                "NET_30", d + timedelta(days=30), 15)
add_invoice("c4-final-open", "c4", 900.00, 500.00, 400.00, date(2026, 5, 30),
            "NET_45", date(2026, 7, 14), None)

# --- C5 Dana Whitfield: 300/mo day 1 through 2026-07, paid at 40d (31-60 cohort);
#     2026-02 invoice is the credit-memo case (250 cash + 50 memo); open 600 in Aug.
for m in MONTHS:
    if m > date(2026, 7, 1):
        continue
    d = m.replace(day=1)
    special = "creditmemo" if m == date(2026, 2, 1) else None
    add_invoice("c5-%s" % month_key(m), "c5", 300.00, 0.00, 300.00, d,
                "NET_30", d + timedelta(days=30), None if special else 40, special=special)
add_invoice("c5-open-current", "c5", 600.00, 0.00, 600.00, date(2026, 8, 21),
            "NET_15", date(2026, 9, 5), None)

# --- C6 Peter Okafor: 200/mo day 15 through 2026-06, paid at 75d (61-90 cohort);
#     2025-11 invoice is the customer-credit application case (100 cash + 100 credit);
#     no July invoice; open 300 in Aug.
for m in MONTHS:
    if m > date(2026, 6, 1):
        continue
    d = m.replace(day=15)
    special = "creditapp" if m == date(2025, 11, 1) else None
    add_invoice("c6-%s" % month_key(m), "c6", 200.00, 0.00, 200.00, d,
                "NET_30", d + timedelta(days=30), None if special else 75, special=special)
add_invoice("c6-open-current", "c6", 300.00, 0.00, 300.00, date(2026, 8, 15),
            "NET_15", date(2026, 8, 30), None)

# --- open (never invoiced) workorders for Q5 ---
add_wo("wo-open-c1-assigned", "c1", date(2026, 8, 18), "nadia", None, None, status="ASSIGNED")
add_wo("wo-open-c1-wip", "c1", date(2026, 8, 24), "sam", None, None, status="WORK_IN_PROGRESS")
add_wo("wo-open-c2-parts", "c2", date(2026, 8, 20), "nadia", None, None, status="AWAITING_PARTS")
add_wo("wo-open-c3-approved", "c3", date(2026, 8, 26), "alex", None, None, status="APPROVED")

# --- reopen markers (Q3): (wo_key, marker_date). Completion actor = the WO's tech. ---
REOPENS = [
    ("wo-c1-202607", date(2026, 7, 4)),   # sam, +3d  (within 7)
    ("wo-c1-202608", date(2026, 8, 6)),   # sam, +5d  (within 7)  -> sam has 2
    ("wo-c3-202607", date(2026, 7, 5)),   # alex, +4d (within 7)  -> alex has 1
    ("wo-c1-open-current", date(2026, 8, 15)),  # nadia, +25d (outside 7) -> decoy
]
for wo_key, marker in REOPENS:
    WORKORDERS[wo_key]["reopened"] = marker

# ---------------------------------------------------------------------------
# derived payment facts
# ---------------------------------------------------------------------------
# payments: (key, cust, invoice_key, amount, cleared_date, balance_before, balance_after)
PAYMENTS = []
REVERSALS = []  # (key, original_payment_key, amount, reversed_date)

for inv in INVOICES:
    k, amt = inv["key"], inv["amount"]
    if inv["special"] == "refund":
        # paid at 10d, refunded 2026-08-05, re-paid 2026-08-10
        PAYMENTS.append((k + "-p1", inv["cust"], k, amt,
                         inv["created"] + timedelta(days=10), amt, 0.0))
        REVERSALS.append((k + "-rev", k + "-p1", amt, date(2026, 8, 5)))
        PAYMENTS.append((k + "-p2", inv["cust"], k, amt, date(2026, 8, 10), amt, 0.0))
    elif inv["special"] == "creditapp":
        # 100 cash at day 5, 100 customer-credit APPLICATION next day (accounting side)
        PAYMENTS.append((k + "-p1", inv["cust"], k, 100.00,
                         inv["created"] + timedelta(days=5), amt, 100.00))
    elif inv["special"] == "creditmemo":
        # 250 cash at 40d; 50.00 credit memo posted 2026-03-20 clears the rest
        PAYMENTS.append((k + "-p1", inv["cust"], k, 250.00,
                         inv["created"] + timedelta(days=40), amt, 50.00))
    elif inv["special"] == "deposit_take":
        PAYMENTS.append((k + "-p1", inv["cust"], k, amt, inv["created"], amt, 0.0))
    elif inv["special"] == "settlement":
        # 1500 cash 2026-08-20; deposit-credit draw-down (500) is invisible to the
        # accounting balance, leaving 1000 open (C2's designed current bucket).
        PAYMENTS.append((k + "-p1", inv["cust"], k, 1500.00, date(2026, 8, 20), amt, 1000.00))
    elif inv["pay_lag"] is not None:
        pay_d = inv["created"] + timedelta(days=inv["pay_lag"])
        assert pay_d <= EVAL_AS_OF, ("payment after as-of", k, pay_d)
        PAYMENTS.append((k + "-p1", inv["cust"], k, amt, pay_d, amt, 0.0))

# the C6 overpayment that funded the customer credit (received 2025-10-20)
CC_SOURCE_PAYMENT = ("cc-c6-source", "c6", None, 150.00, date(2025, 10, 20), None, None)

# ---------------------------------------------------------------------------
# vendor / A-P scenario
# ---------------------------------------------------------------------------
# monthly bills: bill day 10, paid in full day 25 same month (ap_payment GL_POSTED +
# allocation). V1 1000 -> 1120 from 2026-03 (+12% YoY avg, Q17). 4 unpaid APPROVED
# bills due within 14 days after EVAL_AS_OF (Q16).
BILLS = []  # (key, vendor, amount, bill_date, due_date, paid: bool)
for m in MONTHS:
    for vk in VENDORS:
        if vk == "v1":
            amt = 1120.00 if m >= date(2026, 3, 1) else 1000.00
        elif vk == "v2":
            amt = 2000.00
        else:
            amt = 400.00
        bd = m.replace(day=10)
        BILLS.append(("bill-%s-%s" % (vk, month_key(m)), vk, amt, bd,
                      bd + timedelta(days=20), True))
# bill_date = EVAL_AS_OF (not August): keeps these out of every 6-month bill window
# ending 2026-08-31 so Q17's +12% average is not diluted, while the document date is
# still <= asOfDate so aged payables includes them (current bucket, not yet due).
Q16_BILLS = [
    ("bill-v1-due0904", "v1", 800.00, date(2026, 9, 1), date(2026, 9, 4)),
    ("bill-v2-due0908", "v2", 2000.00, date(2026, 9, 1), date(2026, 9, 8)),
    ("bill-v2-due0912", "v2", 600.00, date(2026, 9, 1), date(2026, 9, 12)),
    ("bill-v3-due0915", "v3", 400.00, date(2026, 9, 1), date(2026, 9, 15)),
]
for key, vk, amt, bd, dd in Q16_BILLS:
    BILLS.append((key, vk, amt, bd, dd, False))

# ---------------------------------------------------------------------------
# emit: pos_customer_db
# ---------------------------------------------------------------------------


def gen_customer_db():
    f = SqlFile("pos_customer_db.sql")
    f.raw("-- %s seed: pos_customer_db (generated by generate_seed.py; spec: DATASET.md)" % MARK)
    f.raw("BEGIN;")
    f.raw("DELETE FROM person_party WHERE customer_number LIKE '%s-%%';" % MARK)
    f.raw("DELETE FROM commercial_party WHERE customer_number LIKE '%s-%%';" % MARK)
    n = 0
    for ck, (name, kind) in CUSTOMERS.items():
        n += 1
        num = "%s-CUST-%d" % (MARK, n)
        if kind == "person":
            # Live-alpha shape (alpha-live-columns.json): person_party carries NO name
            # columns (identity moved to pos-people-contact, #874/#875) — the Flyway V1
            # first_name/last_name columns do not exist on alpha. Names served to the
            # gate come from the ext_customer_party replicas, which we seed.
            f.insert(
                "person_party",
                ["customer_id", "person_id", "customer_number",
                 "preferred_contact_method", "status", "tier", "tier_manual_override",
                 "created_at", "updated_at"],
                [q(CUST_ID[ck]), q(CUST_PERSON[ck]), q(num),
                 q("EMAIL"), "0", "0", "FALSE", q(DIM_TS), q(DIM_TS)],
            )
        else:
            # Live-alpha shape: commercial_party has NO party_number column (present in
            # Flyway V1, absent on alpha).
            f.insert(
                "commercial_party",
                ["customer_id", "customer_number", "legal_name",
                 "display_name", "party_type", "status", "tier", "tier_manual_override",
                 "created_at", "updated_at"],
                [q(CUST_ID[ck]), q(num), q(name), q(name),
                 "1", "0", "0", "FALSE", q(DIM_TS), q(DIM_TS)],
            )
    f.raw("COMMIT;")
    summary(f, [("person_party", "customer_number LIKE '%s-%%'" % MARK),
                ("commercial_party", "customer_number LIKE '%s-%%'" % MARK)])
    return f


# ---------------------------------------------------------------------------
# emit: pos_people_db
# ---------------------------------------------------------------------------


def gen_people_db():
    f = SqlFile("pos_people_db.sql")
    f.raw("-- %s seed: pos_people_db" % MARK)
    f.raw("BEGIN;")
    f.raw("DELETE FROM employee_location_assignment WHERE employee_id IN "
          "(SELECT id FROM employee WHERE employee_number LIKE '%s-%%');" % MARK)
    f.raw("DELETE FROM employee WHERE employee_number LIKE '%s-%%';" % MARK)
    n = 0
    for tk, (first, last, username) in TECHS.items():
        n += 1
        f.insert(
            "employee",
            ["id", "person_id", "employee_number", "status", "hire_date",
             "created_at", "updated_at"],
            [q(TECH_EMPLOYEE[tk]), q(TECH_PERSON[tk]), q("%s-EMP-%d" % (MARK, n)),
             q("ACTIVE"), q("2024-01-15"), q(DIM_TS), q(DIM_TS)],
        )
        f.insert(
            "employee_location_assignment",
            ["id", "employee_id", "location_id", "role", "is_primary",
             "effective_from", "status", "created_at", "updated_at"],
            [q(uid("ela", tk)), q(TECH_EMPLOYEE[tk]), q(LOCATION_ID), q("TECHNICIAN"),
             "TRUE", q("2024-01-15"), q("ACTIVE"), q(DIM_TS), q(DIM_TS)],
        )
    f.raw("COMMIT;")
    summary(f, [("employee", "employee_number LIKE '%s-%%'" % MARK),
                ("employee_location_assignment",
                 "employee_id IN (SELECT id FROM employee WHERE employee_number LIKE '%s-%%')" % MARK)])
    return f


# ---------------------------------------------------------------------------
# emit: pos_workorder_db
# ---------------------------------------------------------------------------


def wo_number(wo):
    # stable, unique, marker-carrying
    return "%s-WO-%s" % (MARK, wo["key"].replace("wo-", "").upper())


def gen_workorder_db():
    f = SqlFile("pos_workorder_db.sql")
    f.raw("-- %s seed: pos_workorder_db" % MARK)
    f.raw("BEGIN;")
    sub = "(SELECT id FROM workorder WHERE workorder_number LIKE '%s-%%')" % MARK
    f.raw("DELETE FROM workorder_labor_entry WHERE workorder_id IN %s;" % sub)
    f.raw("DELETE FROM work_order_state_transitions WHERE workorder_id IN %s;" % sub)
    f.raw("DELETE FROM workorder_service WHERE work_order_id IN %s;" % sub)
    f.raw("DELETE FROM workorder WHERE workorder_number LIKE '%s-%%';" % MARK)
    f.raw("DELETE FROM ext_invoice WHERE invoice_number LIKE '%s-%%';" % MARK)
    f.raw("DELETE FROM ext_customer_party WHERE party_id IN (%s);"
          % ", ".join(q(CUST_ID[c]) for c in CUSTOMERS))
    # Scoped by deterministic person_id, NOT username: username is a natural, non-namespaced
    # value, and a real replica row sharing one of our invented usernames would be silently
    # destroyed (PR #1647 review finding 1 — the one delete in the suite that was not provably
    # TRACKB-scoped). person_id carries our uuid5 namespace and cannot collide.
    f.raw("DELETE FROM ext_people_contact_user_link WHERE person_id IN (%s);"
          % ", ".join(q(TECH_PERSON[t]) for t in TECHS))
    f.raw("DELETE FROM ext_people_contact_person WHERE person_id IN (%s);"
          % ", ".join(q(TECH_PERSON[t]) for t in TECHS))

    # replicas: people
    for tk, (first, last, username) in TECHS.items():
        f.insert("ext_people_contact_person",
                 ["person_id", "first_name", "last_name", "aggregate_version", "updated_at"],
                 [q(TECH_PERSON[tk]), q(first), q(last), "1", q(DIM_TS)])
        f.insert("ext_people_contact_user_link",
                 ["link_id", "person_id", "username", "status", "aggregate_version", "updated_at"],
                 [q(TECH_LINK[tk]), q(TECH_PERSON[tk]), q(username), q("ACTIVE"), "1", q(DIM_TS)])
    # replicas: customers
    for ck, (name, kind) in CUSTOMERS.items():
        f.insert("ext_customer_party",
                 ["party_id", "party_type", "display_name", "status", "requirements_met",
                  "aggregate_version", "updated_at"],
                 [q(CUST_ID[ck]), q("PERSON" if kind == "person" else "COMMERCIAL"),
                  q(name), q("ACTIVE"), "TRUE", "1", q(DIM_TS)])

    # workorders + services + transitions + labor entries
    for wo in WORKORDERS.values():
        created_ts = tstz(wo["created"], 14)
        completed = wo["completed"]
        completed_ts = tstz(completed, 10) if completed else None
        reopened = wo["reopened"]
        username = TECHS[wo["tech"]][2]
        f.insert(
            "workorder",
            ["id", "workorder_number", "status", "customer_id", "crm_party_id",
             "location_id", "shop_id", "created_at", "updated_at", "completed_at",
             "completed_by", "is_reopened", "reopened_at", "reopened_by", "reopen_reason"],
            [q(wo["id"]), q(wo_number(wo)), q(wo["status"]), q(CUST_ID[wo["cust"]]),
             q(CUST_ID[wo["cust"]]), q(LOCATION_ID), q(LOCATION_ID), q(created_ts),
             q(completed_ts or created_ts), q(completed_ts), q(username if completed else None),
             q(bool(reopened)) if reopened else "FALSE",
             q(tstz(reopened, 11) if reopened else None),
             q("trackb.manager" if reopened else None),
             q("%s seed reopen" % MARK if reopened else None)],
        )
        svc_id = uid("wos", wo["key"])
        f.insert(
            "workorder_service",
            ["id", "work_order_id", "status", "description", "quantity", "unit_price",
             "line_total", "technician_id", "created_at", "updated_at"],
            [q(svc_id), q(wo["id"]),
             q("COMPLETED" if completed else "OPEN"),
             q("%s service" % MARK), money(1), money(0), money(0),
             q(TECH_PERSON[wo["tech"]]), q(created_ts), q(created_ts)],
        )

        # lifecycle transitions
        def trans(idx, frm, to, at_ts, actor, reason=None):
            f.insert(
                "work_order_state_transitions",
                ["id", "workorder_id", "from_status", "to_status", "transitioned_at",
                 "transitioned_by", "reason", "created_at", "updated_at"],
                [q(uid("wot", wo["key"], str(idx))), q(wo["id"]), q(frm), q(to),
                 q(at_ts), q(actor), q(reason), q(at_ts), q(at_ts)],
            )

        trans(1, "DRAFT", "APPROVED", tstz(wo["created"], 15), username)
        if wo["status"] == "APPROVED" and not completed:
            pass
        elif wo["status"] == "ASSIGNED" and not completed:
            trans(2, "APPROVED", "ASSIGNED", tstz(wo["created"], 16), username)
        elif wo["status"] == "AWAITING_PARTS" and not completed:
            trans(2, "APPROVED", "WORK_IN_PROGRESS", tstz(wo["created"], 16), username)
            trans(3, "WORK_IN_PROGRESS", "AWAITING_PARTS", tstz(wo["created"], 17), username)
        elif wo["status"] == "WORK_IN_PROGRESS" and not completed:
            trans(2, "APPROVED", "WORK_IN_PROGRESS", tstz(wo["created"], 16), username)
        else:  # completed
            trans(2, "APPROVED", "WORK_IN_PROGRESS", tstz(wo["created"], 16), username)
            trans(3, "WORK_IN_PROGRESS", "COMPLETED", completed_ts, username)
            if reopened:
                # same-status marker row, exactly as WorkorderStateMachine
                # .reopenCompletedWorkorder records it (#1594/E6)
                trans(4, "COMPLETED", "COMPLETED", tstz(reopened, 11),
                      "trackb.manager", "Reopened: %s seed reopen" % MARK)

        # labor entry: stopped entry (hours) for completed WOs; one running entry
        # for the WIP open workorder so E12's technician filter has a live match.
        if completed:
            hours = wo["hours"]
            start_dt = datetime(completed.year, completed.month, completed.day, 10, 0) \
                - timedelta(hours=hours)
            f.insert(
                "workorder_labor_entry",
                ["id", "workorder_id", "workorder_service_id", "technician_id",
                 "hours_worked", "start_time", "end_time", "created_by",
                 "created_at", "updated_at"],
                [q(uid("wle", wo["key"])), q(wo["id"]), q(svc_id), q(TECH_PERSON[wo["tech"]]),
                 money(hours), q(start_dt.strftime("%Y-%m-%d %H:%M:%S")),
                 q(tsnaive(completed, 10)), q(username), q(completed_ts), q(completed_ts)],
            )
        elif wo["status"] == "WORK_IN_PROGRESS":
            f.insert(
                "workorder_labor_entry",
                ["id", "workorder_id", "workorder_service_id", "technician_id",
                 "hours_worked", "start_time", "end_time", "created_by",
                 "created_at", "updated_at"],
                [q(uid("wle", wo["key"])), q(wo["id"]), q(svc_id), q(TECH_PERSON[wo["tech"]]),
                 money(0), q(tsnaive(wo["created"], 16)), "NULL", q(username),
                 q(created_ts), q(created_ts)],
            )

    # ext_invoice replica (labor/parts split feeds E5 labor revenue)
    for inv in INVOICES:
        f.insert(
            "ext_invoice",
            ["invoice_id", "workorder_id", "invoice_number", "status", "subtotal", "tax",
             "total", "labor_total", "parts_total", "invoice_created_at",
             "aggregate_version", "updated_at"],
            [q(inv["id"]), q(WORKORDERS[inv["wo"]]["id"]), q(inv_number(inv)),
             q("FINALIZED"), money(inv["amount"]), money(0), money(inv["amount"]),
             money(inv["labor"]), money(inv["parts"]),
             q(tstz(inv["created"], 14)), "1", q(tstz(inv["created"], 14, 30))],
        )
    f.raw("COMMIT;")
    summary(f, [
        ("workorder", "workorder_number LIKE '%s-%%'" % MARK),
        ("work_order_state_transitions", "workorder_id IN %s" % sub),
        ("workorder_service", "work_order_id IN %s" % sub),
        ("workorder_labor_entry", "workorder_id IN %s" % sub),
        ("ext_invoice", "invoice_number LIKE '%s-%%'" % MARK),
        ("ext_customer_party", "party_id IN (%s)" % ", ".join(q(CUST_ID[c]) for c in CUSTOMERS)),
        ("ext_people_contact_person", "person_id IN (%s)" % ", ".join(q(TECH_PERSON[t]) for t in TECHS)),
        ("ext_people_contact_user_link", "username IN (%s)" % ", ".join(q(TECHS[t][2]) for t in TECHS)),
    ])
    return f


# ---------------------------------------------------------------------------
# emit: pos_invoice_db
# ---------------------------------------------------------------------------

INV_NUMBERS = {}


def inv_number(inv):
    if inv["key"] not in INV_NUMBERS:
        INV_NUMBERS[inv["key"]] = "%s-INV-%04d" % (MARK, len(INV_NUMBERS) + 1)
    return INV_NUMBERS[inv["key"]]


def gen_invoice_db():
    f = SqlFile("pos_invoice_db.sql")
    f.raw("-- %s seed: pos_invoice_db" % MARK)
    f.raw("BEGIN;")
    sub = "(SELECT id FROM invoices WHERE invoice_number LIKE '%s-%%')" % MARK
    f.raw("DELETE FROM invoice_items WHERE invoice_id IN %s;" % sub)
    f.raw("DELETE FROM invoice_adjustments WHERE invoice_id IN %s;" % sub)
    f.raw("DELETE FROM refund_records WHERE invoice_id IN %s;" % sub)
    f.raw("DELETE FROM deposit_credit_application WHERE deposit_credit_id = %s;" % q(DEPOSIT_CREDIT_ID))
    f.raw("DELETE FROM deposit_credit WHERE deposit_credit_id = %s;" % q(DEPOSIT_CREDIT_ID))
    f.raw("DELETE FROM payment_intents WHERE idempotency_key LIKE '%s-%%';" % MARK)
    f.raw("DELETE FROM invoices WHERE invoice_number LIKE '%s-%%';" % MARK)
    f.raw("DELETE FROM ext_workorder WHERE workorder_number LIKE '%s-%%';" % MARK)
    f.raw("DELETE FROM ext_customer_party WHERE party_id IN (%s);"
          % ", ".join(q(CUST_ID[c]) for c in CUSTOMERS))
    f.raw("DELETE FROM ext_people_employee WHERE employee_number LIKE '%s-%%';" % MARK)
    f.raw("DELETE FROM ext_location WHERE location_id = %s;" % q(LOCATION_ID))

    # replicas
    f.insert("ext_location",
             ["location_id", "name", "active", "aggregate_version", "updated_at", "timezone"],
             [q(LOCATION_ID), q(LOCATION_NAME), "TRUE", "1", q(DIM_TS), q("UTC")])
    for ck, (name, kind) in CUSTOMERS.items():
        f.insert("ext_customer_party",
                 ["party_id", "party_type", "display_name", "status",
                  "aggregate_version", "updated_at"],
                 [q(CUST_ID[ck]), q("PERSON" if kind == "person" else "COMMERCIAL"),
                  q(name), q("ACTIVE"), "1", q(DIM_TS)])
    n = 0
    for tk in TECHS:
        n += 1
        f.insert("ext_people_employee",
                 ["employee_id", "person_id", "employee_number", "status",
                  "aggregate_version", "updated_at"],
                 [q(TECH_EMPLOYEE[tk]), q(TECH_PERSON[tk]), q("%s-EMP-%d" % (MARK, n)),
                  q("ACTIVE"), "1", q(DIM_TS)])
    for wo in WORKORDERS.values():
        f.insert(
            "ext_workorder",
            ["workorder_id", "workorder_number", "status", "customer_id",
             "workorder_created_at", "aggregate_version", "updated_at"],
            [q(wo["id"]), q(wo_number(wo)), q(wo["status"]), q(CUST_ID[wo["cust"]]),
             q(tstz(wo["created"], 14)) if wo["replica_created_visible"] else "NULL",
             "1", q(DIM_TS)],
        )

    # invoices + items
    for inv in INVOICES:
        created_ts = tstz(inv["created"], 14)
        finalized_ts = tstz(inv["created"], 14, 30)
        dep_type = "WORKORDER" if inv["special"] == "deposit_take" else None
        dep_id = DEPOSIT_CREDIT_ID if inv["special"] == "deposit_take" else None
        order_id = DEPOSIT_ORDER_ID if inv["special"] == "deposit_take" else None
        f.insert(
            "invoices",
            ["id", "invoice_number", "workorder_id", "order_id", "customer_id",
             "location_id", "status", "subtotal", "tax_amount", "adjustments_amount",
             "total_amount", "version", "created_at", "finalized_at", "updated_at",
             "finalized_by", "due_date", "payment_terms_code", "deposit_source_type",
             "deposit_source_id"],
            [q(inv["id"]), q(inv_number(inv)), q(WORKORDERS[inv["wo"]]["id"]), q(order_id),
             q(CUST_ID[inv["cust"]]), q(LOCATION_ID), q("FINALIZED"),
             money(inv["amount"]), money(0), money(0), money(inv["amount"]), "0",
             q(created_ts), q(finalized_ts), q(finalized_ts), q(SEED_USER),
             q(inv["due"].isoformat()), q(inv["terms"]), q(dep_type), q(dep_id)],
        )
        item_no = 0
        for typ, amt in (("LABOR", inv["labor"]), ("PART", inv["parts"]), ("FEE", inv["fee"])):
            if amt <= 0:
                continue
            item_no += 1
            f.insert(
                "invoice_items",
                ["id", "invoice_id", "type", "description", "quantity", "unit_price",
                 "line_total"],
                [q(uid("item", inv["key"], typ)), q(inv["id"]), q(typ),
                 q("%s %s line" % (MARK, typ.lower())), money(1), money(amt), money(amt)],
            )

    # refund chain source artifacts (C3 July): captured intent + completed refund
    c3jul = next(i for i in INVOICES if i["special"] == "refund")
    pi_id = uid("pi", c3jul["key"])
    f.insert(
        "payment_intents",
        ["id", "invoice_id", "payment_flow", "status", "authorized_amount",
         "captured_amount", "idempotency_key", "payment_token", "created_at", "updated_at"],
        [q(pi_id), q(c3jul["id"]), q("SALE_CAPTURE"), q("CAPTURED"), money(400),
         money(400), q("%s-PI-C3-JUL" % MARK), q("%s-TOKEN" % MARK),
         q(tstz(date(2026, 7, 11), 15)), q(tstz(date(2026, 7, 11), 15))],
    )
    f.insert(
        "refund_records",
        ["id", "invoice_id", "payment_intent_id", "amount", "status", "reason",
         "requested_by", "requested_at", "completed_at", "created_at", "updated_at", "notes"],
        [q(uid("refund", c3jul["key"])), q(c3jul["id"]), q(pi_id), money(400),
         q("COMPLETED"), q("SERVICE_ERROR"), q(SEED_USER), q(tstz(date(2026, 8, 4), 10)),
         q(tstz(date(2026, 8, 5), 10)), q(tstz(date(2026, 8, 4), 10)),
         q(tstz(date(2026, 8, 5), 10)), q("%s refund scenario" % MARK)],
    )

    # deposit credit + draw-down (C2 settlement)
    settle = next(i for i in INVOICES if i["special"] == "settlement")
    dep_wo = WORKORDERS["wo-c2-deposit"]
    f.insert(
        "deposit_credit",
        ["deposit_credit_id", "version", "source_type", "source_id", "order_id",
         "party_id", "currency_code", "original_amount", "remaining_balance", "status",
         "created_at", "updated_at"],
        [q(DEPOSIT_CREDIT_ID), "0", q("WORKORDER"), q(dep_wo["id"]), q(DEPOSIT_ORDER_ID),
         q(CUST_ID["c2"]), q(CCY), money(500), money(0), q("FULLY_APPLIED"),
         q(tstz(date(2026, 8, 5), 15)), q(tstz(date(2026, 8, 12), 15))],
    )
    f.insert(
        "deposit_credit_application",
        ["application_id", "deposit_credit_id", "invoice_id", "amount_applied", "applied_at"],
        [q(uid("dca", "c2")), q(DEPOSIT_CREDIT_ID), q(settle["id"]), money(500),
         q(tstz(date(2026, 8, 12), 15))],
    )
    f.raw("COMMIT;")
    summary(f, [
        ("invoices", "invoice_number LIKE '%s-%%'" % MARK),
        ("invoice_items", "invoice_id IN %s" % sub),
        ("payment_intents", "idempotency_key LIKE '%s-%%'" % MARK),
        ("refund_records", "invoice_id IN %s" % sub),
        ("deposit_credit", "deposit_credit_id = %s" % q(DEPOSIT_CREDIT_ID)),
        ("deposit_credit_application", "deposit_credit_id = %s" % q(DEPOSIT_CREDIT_ID)),
        ("ext_workorder", "workorder_number LIKE '%s-%%'" % MARK),
        ("ext_customer_party", "party_id IN (%s)" % ", ".join(q(CUST_ID[c]) for c in CUSTOMERS)),
        ("ext_people_employee", "employee_number LIKE '%s-%%'" % MARK),
        ("ext_location", "location_id = %s" % q(LOCATION_ID)),
    ])
    return f


# ---------------------------------------------------------------------------
# emit: pos_accounting_db
# ---------------------------------------------------------------------------


def gen_accounting_db():
    f = SqlFile("pos_accounting_db.sql")
    f.raw("-- %s seed: pos_accounting_db" % MARK)
    f.raw("BEGIN;")
    f.raw("DELETE FROM payment_application_reversal WHERE reversed_by = %s;" % q(SEED_USER))
    f.raw("DELETE FROM payment_application WHERE application_request_id LIKE '%s-%%';" % MARK)
    f.raw("DELETE FROM receivable_payment WHERE created_by = %s;" % q(SEED_USER))
    f.raw("DELETE FROM customer_credit_transaction WHERE request_id LIKE '%s-%%';" % MARK)
    f.raw("DELETE FROM customer_credit WHERE created_by = %s;" % q(SEED_USER))
    f.raw("DELETE FROM credit_memo WHERE created_by_user_id = %s;" % q(SEED_USER))
    f.raw("DELETE FROM ext_invoice_payment_reversal WHERE refund_id = %s;"
          % q(uid("extrev", "c3-202607")))
    f.raw("DELETE FROM ext_invoice_deposit_credit_application WHERE application_id = %s;"
          % q(uid("extdca", "c2")))
    f.raw("DELETE FROM ext_invoice WHERE invoice_number LIKE '%s-%%';" % MARK)
    f.raw("DELETE FROM ap_payment_allocation WHERE payment_id IN "
          "(SELECT payment_id FROM ap_payment WHERE payment_ref LIKE '%s-%%');" % MARK)
    f.raw("DELETE FROM ap_payment WHERE payment_ref LIKE '%s-%%';" % MARK)
    f.raw("DELETE FROM vendor_bill WHERE bill_number LIKE '%s-%%';" % MARK)
    f.raw("DELETE FROM ap_vendor WHERE vendor_number LIKE '%s-%%';" % MARK)

    inv_by_key = {i["key"]: i for i in INVOICES}

    # ext_invoice replica (A/R aging + E2 invoiced + E3 cohorts read THIS, not pos-invoice)
    for inv in INVOICES:
        dep_type = "WORKORDER" if inv["special"] == "deposit_take" else None
        f.insert(
            "ext_invoice",
            ["invoice_id", "invoice_number", "workorder_id", "location_id", "party_id",
             "status", "subtotal", "tax", "total", "adjustments_amount",
             "invoice_created_at", "finalized_at", "due_date", "deposit_source_type",
             "aggregate_version", "updated_at"],
            [q(inv["id"]), q(inv_number(inv)), q(WORKORDERS[inv["wo"]]["id"]),
             q(LOCATION_ID), q(CUST_ID[inv["cust"]]), q("FINALIZED"),
             money(inv["amount"]), money(0), money(inv["amount"]), money(0),
             q(tstz(inv["created"], 14)), q(tstz(inv["created"], 14, 30)),
             q(inv["due"].isoformat()), q(dep_type), "1", q(tstz(inv["created"], 14, 30))],
        )

    # cash: receivable_payment + payment_application per payment fact
    napp = 0
    for pk, cust, inv_key, amount, cleared, before, after in PAYMENTS + [CC_SOURCE_PAYMENT]:
        rp_id = uid("rp", pk)
        cleared_ts = tstz(cleared, 15)
        f.insert(
            "receivable_payment",
            ["payment_id", "customer_id", "currency", "total_amount", "unapplied_amount",
             "status", "cleared_at", "created_at", "created_by"],
            [q(rp_id), q(CUST_ID[cust]), q(CCY), money(amount), money(0),
             q("FULLY_APPLIED"), q(cleared_ts), q(cleared_ts), q(SEED_USER)],
        )
        if inv_key is None:
            continue  # the credit-funding overpayment has no application
        napp += 1
        f.insert(
            "payment_application",
            ["payment_application_id", "payment_id", "invoice_id", "customer_id",
             "currency", "applied_amount", "invoice_balance_before",
             "invoice_balance_after", "application_timestamp", "created_at",
             "created_by", "application_request_id"],
            [q(uid("pa", pk)), q(rp_id), q(inv_by_key[inv_key]["id"]), q(CUST_ID[cust]),
             q(CCY), money(amount), money(before), money(after), q(cleared_ts),
             q(cleared_ts), q(SEED_USER), q("%s-APP-%04d" % (MARK, napp))],
        )

    # the one application reversal (movement-basis D7 scenario)
    for rk, orig_pk, amount, rev_d in REVERSALS:
        f.insert(
            "payment_application_reversal",
            ["reversal_id", "original_payment_application_id", "amount", "reversed_at",
             "reversed_by", "reason"],
            [q(uid("rev", rk)), q(uid("pa", orig_pk)), money(amount),
             q(tstz(rev_d, 10)), q(SEED_USER), q("%s refund reversal scenario" % MARK)],
        )

    # refund replica (feeds E2 `refunded`, movement basis over reversed_at)
    c3jul = inv_by_key["c3-202607"]
    f.insert(
        "ext_invoice_payment_reversal",
        ["refund_id", "payment_intent_id", "invoice_id", "party_id", "amount",
         "currency_code", "reversal_type", "reversed_at", "source_event_id"],
        [q(uid("extrev", "c3-202607")), q(uid("pi", "c3-202607")), q(c3jul["id"]),
         q(CUST_ID["c3"]), money(400), q(CCY), q("REFUND"), q(tstz(date(2026, 8, 5), 10)),
         q(uid("evt", "extrev-c3"))],
    )

    # deposit draw-down replica (feeds E2 `nonCashSettled`)
    settle = inv_by_key["c2-settlement"]
    f.insert(
        "ext_invoice_deposit_credit_application",
        ["application_id", "deposit_credit_id", "invoice_id", "amount_applied",
         "applied_at", "source_event_id"],
        [q(uid("extdca", "c2")), q(DEPOSIT_CREDIT_ID), q(settle["id"]), money(500),
         q(tstz(date(2026, 8, 12), 15)), q(uid("evt", "extdca-c2"))],
    )

    # credit memo clearing the C5 2026-02 invoice remainder
    c5feb = inv_by_key["c5-202602"]
    f.insert(
        "credit_memo",
        ["credit_memo_id", "customer_id", "original_invoice_id", "credit_amount",
         "tax_amount_reversed", "currency", "prior_period_adjustment", "status",
         "reason_code", "created_by_user_id", "creation_timestamp", "posted_timestamp"],
        [q(uid("cm", "c5-202602")), q(CUST_ID["c5"]), q(c5feb["id"]), money(50),
         money(0), q(CCY), "FALSE", q("POSTED"), q("GOODWILL"), q(SEED_USER),
         q(tstz(date(2026, 3, 18), 12)), q(tstz(date(2026, 3, 20), 12))],
    )

    # customer credit lifecycle: 150 overpayment credit -> 100 APPLICATION (C6 2025-11
    # invoice) + 50 REFUND (2026-08-15, feeds E2 `refunded` second source)
    c6nov = inv_by_key["c6-202511"]
    cc_id = uid("cc", "c6")
    f.insert(
        "customer_credit",
        ["credit_id", "customer_id", "source_payment_id", "amount", "applied_amount",
         "refunded_amount", "currency", "status", "version", "created_at", "updated_at",
         "created_by"],
        [q(cc_id), q(CUST_ID["c6"]), q(uid("rp", "cc-c6-source")), money(150), money(100),
         money(50), q(CCY), q("CONSUMED"), "0", q(tstz(date(2025, 10, 20), 15)),
         q(tstz(date(2026, 8, 15), 12)), q(SEED_USER)],
    )
    f.insert(
        "customer_credit_transaction",
        ["credit_transaction_id", "credit_id", "transaction_type", "invoice_id", "amount",
         "currency", "request_id", "created_at", "created_by"],
        [q(uid("cct", "c6-app")), q(cc_id), q("APPLICATION"), q(c6nov["id"]), money(100),
         q(CCY), q("%s-CCT-1" % MARK), q(tstz(date(2025, 11, 16), 12)), q(SEED_USER)],
    )
    f.insert(
        "customer_credit_transaction",
        ["credit_transaction_id", "credit_id", "transaction_type", "invoice_id", "amount",
         "currency", "request_id", "created_at", "created_by"],
        [q(uid("cct", "c6-refund")), q(cc_id), q("REFUND"), "NULL", money(50),
         q(CCY), q("%s-CCT-2" % MARK), q(tstz(date(2026, 8, 15), 12)), q(SEED_USER)],
    )

    # A/P: vendor directory, bills, payments, allocations
    n = 0
    for vk, name in VENDORS.items():
        n += 1
        f.insert(
            "ap_vendor",
            ["vendor_id", "name", "vendor_number", "status", "created_at", "updated_at"],
            [q(VENDOR_ID[vk]), q(name), q("%s-V%d" % (MARK, n)), q("ACTIVE"),
             q(DIM_TS), q(DIM_TS)],
        )
    for key, vk, amt, bd, dd, paid in BILLS:
        bill_id = uid(key)
        pay_d = bd.replace(day=25) if paid else None
        f.insert(
            "vendor_bill",
            ["vendor_bill_id", "vendor_id", "vendor_name", "bill_number", "status",
             "total_amount", "bill_date", "due_date", "created_at", "modified_at",
             "created_by", "modified_by", "approved_at", "approved_by", "paid_at",
             "paid_by"],
            [q(bill_id), q(VENDOR_ID[vk]), q(VENDORS[vk]),
             q("%s-BILL-%s" % (MARK, key.replace("bill-", "").upper())),
             q("PAID" if paid else "APPROVED"), money(amt), q(tsnaive(bd, 12)),
             q(tsnaive(dd, 0)), q(tstz(bd, 12)), q(tstz(pay_d or bd, 12)), q(SEED_USER),
             q(SEED_USER), q(tstz(bd, 13)), q(SEED_USER),
             q(tstz(pay_d, 12)) if paid else "NULL", q(SEED_USER) if paid else "NULL"],
        )
        if paid:
            pay_id = uid("appay", key)
            f.insert(
                "ap_payment",
                ["payment_id", "vendor_id", "vendor_name", "vendor_bill_id", "currency",
                 "gross_amount", "net_amount", "fee_amount", "unapplied_amount", "status",
                 "payment_method", "payment_date", "gl_posted_at", "created_at",
                 "created_by", "payment_ref"],
                [q(pay_id), q(VENDOR_ID[vk]), q(VENDORS[vk]), q(bill_id), q(CCY),
                 money(amt), money(amt), money(0), money(0), q("GL_POSTED"), q("ACH"),
                 q(tsnaive(pay_d, 12)), q(tstz(pay_d, 13)), q(tstz(pay_d, 12)),
                 q(SEED_USER), q("%s-PAY-%s" % (MARK, key.replace("bill-", "").upper()))],
            )
            f.insert(
                "ap_payment_allocation",
                ["allocation_id", "payment_id", "vendor_bill_id", "applied_amount",
                 "allocation_sequence", "created_at"],
                [q(uid("apalloc", key)), q(pay_id), q(bill_id), money(amt), "1",
                 q(tstz(pay_d, 12))],
            )
    f.raw("COMMIT;")
    summary(f, [
        ("ext_invoice", "invoice_number LIKE '%s-%%'" % MARK),
        ("receivable_payment", "created_by = %s" % q(SEED_USER)),
        ("payment_application", "application_request_id LIKE '%s-%%'" % MARK),
        ("payment_application_reversal", "reversed_by = %s" % q(SEED_USER)),
        ("ext_invoice_payment_reversal", "refund_id = %s" % q(uid("extrev", "c3-202607"))),
        ("ext_invoice_deposit_credit_application", "application_id = %s" % q(uid("extdca", "c2"))),
        ("credit_memo", "created_by_user_id = %s" % q(SEED_USER)),
        ("customer_credit", "created_by = %s" % q(SEED_USER)),
        ("customer_credit_transaction", "request_id LIKE '%s-%%'" % MARK),
        ("ap_vendor", "vendor_number LIKE '%s-%%'" % MARK),
        ("vendor_bill", "bill_number LIKE '%s-%%'" % MARK),
        ("ap_payment", "payment_ref LIKE '%s-%%'" % MARK),
        ("ap_payment_allocation",
         "payment_id IN (SELECT payment_id FROM ap_payment WHERE payment_ref LIKE '%s-%%')" % MARK),
    ])
    return f


def summary(f, table_preds):
    parts = []
    for t, pred in table_preds:
        parts.append("SELECT '%s' AS seeded_table, count(*) AS seeded_rows FROM %s WHERE %s"
                     % (t, t, pred))
    f.raw("\n-- row-count summary (printed by apply_seed.sh)")
    f.raw("\nUNION ALL\n".join(parts) + "\nORDER BY seeded_table;")


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    # numbering must be stable: assign invoice numbers in construction order first
    for inv in INVOICES:
        inv_number(inv)
    files = [gen_customer_db(), gen_people_db(), gen_workorder_db(),
             gen_invoice_db(), gen_accounting_db()]
    print("EVAL_AS_OF=%s  namespace=%s" % (EVAL_AS_OF, NS))
    print("invoices=%d workorders=%d payments=%d bills=%d"
          % (len(INVOICES), len(WORKORDERS), len(PAYMENTS) + 1, len(BILLS)))
    for f in files:
        path = f.write()
        total = sum(f.counts.values())
        print("\n%s  (%d inserted rows)" % (path, total))
        for t in sorted(f.counts):
            print("  %-42s %4d" % (t, f.counts[t]))


if __name__ == "__main__":
    main()
