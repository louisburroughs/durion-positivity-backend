#!/usr/bin/env python3
"""Generate pos-vehicle-inventory vehicle_records seed from the customer seed.

The customer service seeds vehicle ownership (customer_vehicle: customer_id->vin)
and a local projection (vehicle_projection: vin->vehicle_id/make/model/year), but
the authoritative vehicle registry (pos-vehicle-inventory.vehicle_records) was never
seeded. CrmVehicleServiceImpl resolves a party's vehicles by calling the registry
per VIN and silently drops VINs the registry cannot resolve, so vehicleCount (raw
VIN set size) disagrees with the empty vehicle list/dropdown.

This script joins customer_vehicle with vehicle_projection on VIN and emits an
idempotent R__ seed inserting one vehicle_records row per owned VIN, mirroring what
createVehicle would have written (account_id = owning party, vehicle_id reused from
the projection so ids stay aligned).
"""
import hashlib
import re
import sys
from pathlib import Path

SRC = Path("pos-customer/src/main/resources/db/migration/R__seed_customer_operational_data.sql")
OUT = Path("pos-vehicle-inventory/src/main/resources/db/migration/R__seed_vehicle_inventory_operational_data.sql")


def normalize(vin: str) -> str:
    return re.sub(r"[^A-Z0-9]", "", vin.strip().upper())


def block(text: str, table: str) -> str:
    """Return the VALUES body of the first INSERT INTO <table>."""
    m = re.search(r"INSERT\s+INTO\s+" + re.escape(table) + r"\b", text)
    if not m:
        sys.exit(f"INSERT INTO {table} not found")
    vm = re.search(r"\bVALUES\b", text[m.end():])
    if not vm:
        sys.exit(f"VALUES not found for {table}")
    start = m.end() + vm.end()
    end = text.index(";", start)
    return text[start:end]


# row tuple parser: top-level (...) groups, comma-split respecting quotes
ROW_RE = re.compile(r"\(([^()]*)\)")
UUID_RE = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")


def is_uuid(cell: str) -> bool:
    """True only for a real UUID literal cell, e.g. '...'::uuid — rejects column
    aliases like `customer_id` that appear in `... AS v(customer_id, vin)`."""
    return UUID_RE.match(uuid_cell(cell)) is not None


def is_quoted(cell: str) -> bool:
    return "'" in cell


def cells(row: str):
    out, cur, q = [], "", False
    for ch in row:
        if ch == "'":
            q = not q
            cur += ch
        elif ch == "," and not q:
            out.append(cur.strip())
            cur = ""
        else:
            cur += ch
    out.append(cur.strip())
    return out


def uuid_cell(c: str) -> str:
    return c.split("'")[1] if "'" in c else c.replace("::uuid", "").strip()


def str_cell(c: str):
    c = c.strip()
    if c.upper() == "NULL":
        return None
    return c.split("'")[1] if "'" in c else c


def int_cell(c: str):
    c = c.strip().rstrip("::integer").strip()
    return None if c.upper() == "NULL" else int(re.sub(r"[^0-9]", "", c) or 0)


def main():
    text = SRC.read_text()

    # strip line comments and flatten NOW() so the paren-free tuple regex parses cleanly
    strip_comments = lambda s: re.sub(r"--[^\n]*", "", s).replace("NOW()", "NOW")

    # customer_vehicle (customer_id, vin)
    cv = strip_comments(block(text, "customer_vehicle"))
    owner = {}  # vin -> customer_id
    dup_owner = 0
    for row in ROW_RE.findall(cv):
        c = cells(row)
        # skip guard subqueries and the `AS v(customer_id, vin)` column-alias tuple
        if len(c) != 2 or "select" in row.lower() or not is_uuid(c[0]) or not is_quoted(c[1]):
            continue
        cid, vin = c
        v = normalize(str_cell(vin))
        if v in owner:
            dup_owner += 1
        owner[v] = uuid_cell(cid)

    # vehicle_projection (vehicle_id, vin, make, model, vehicle_year, color, last_event_at)
    vp = strip_comments(block(text, "vehicle_projection"))
    detail = {}  # vin -> (vehicle_id, make, model, year)
    for row in ROW_RE.findall(vp):
        c = cells(row)
        if len(c) < 5 or "select" in row.lower() or not is_uuid(c[0]) or not is_quoted(c[1]):
            continue  # skip guard subqueries and the column-alias tuple
        vid, vin, make, model, year = c[0], c[1], c[2], c[3], c[4]
        v = normalize(str_cell(vin))
        detail[v] = (uuid_cell(vid), str_cell(make), str_cell(model), int_cell(year))

    rows, matched, synthetic = [], 0, 0
    for vin, account_id in sorted(owner.items()):
        if vin in detail:
            vid, make, model, year = detail[vin]
            matched += 1
        else:
            vid = hashlib.md5(vin.encode()).hexdigest()
            vid = f"{vid[:8]}-{vid[8:12]}-{vid[12:16]}-{vid[16:20]}-{vid[20:32]}"
            make = model = year = None
            synthetic += 1

        def q(x):
            return "NULL" if x is None else "'" + str(x).replace("'", "''") + "'"

        rows.append(
            f"    ('{vid}'::uuid, '{account_id}'::uuid, '{vin}', '{vin}', "
            f"{q(make)}, {q(model)}, {('NULL' if year is None else year)}, "
            f"'', '', true, 0, NOW(), NOW())"
        )

    header = (
        "-- Repeatable seed: vehicle registry (vehicle_records) parity with the customer\n"
        "-- service's seeded vehicle ownership. Generated by\n"
        "-- build-tools/gen_vehicle_inventory_seed.py from\n"
        "-- pos-customer R__seed_customer_operational_data.sql (customer_vehicle x vehicle_projection).\n"
        "-- DO NOT EDIT BY HAND -- regenerate via the script.\n\n"
        "SET TIME ZONE 'UTC';\n\n"
        "INSERT INTO vehicle_records (\n"
        "    vehicle_id, account_id, vin, vin_normalized,\n"
        "    make, model, model_year,\n"
        "    description, unit_number, is_active, version, created_at, updated_at\n"
        ") VALUES\n"
    )
    body = ",\n".join(rows) + "\nON CONFLICT (vin_normalized) DO NOTHING;\n"
    OUT.write_text(header + body)

    print(f"owned VINs:        {len(owner)} (dup owner overwrites: {dup_owner})")
    print(f"projection matched:{matched}")
    print(f"synthetic (no proj){synthetic}")
    print(f"rows written:      {len(rows)} -> {OUT}")


if __name__ == "__main__":
    main()
