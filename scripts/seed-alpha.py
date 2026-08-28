#!/usr/bin/env python3
"""Alpha seed-pipeline driver (docs/DATA_SEED_STRATEGY.md §3 Tier 2).

Loads the fixture packs under scripts/fixtures/seed/alpha/ through the
pos-bulk-loader, in dependency order, via the API gateway — so every row goes
through the owning service's application layer and emits its events/facts.

For each pack file: create a bulk-load job, upload the CSV, start processing,
poll until the job reaches a terminal state, and report row counters. The
run is idempotent-ish, not upserting: services reject duplicates per-row
(unique codes/names) or create duplicates where no natural key exists
(commercial accounts) — re-run whole packs only against a reset alpha, or
expect per-row failures / duplicates accordingly.

Bootstrap: bulk-load jobs themselves require a locationId. The driver
resolves --location-code against the location roster; with
--bootstrap-location it creates that location from the first row of
locations.csv via the gateway location API when the roster is empty (that row
then reports one expected duplicate failure in the LOCATION job).

Usage:
  scripts/seed-alpha.py --gateway https://alpha.example.com \
      --token "$SEED_BEARER_TOKEN" [--location-code CLT-MAIN-001] \
      [--bootstrap-location] [--only customer/person-customers.csv] [--dry-run]

The bearer token needs bulkImport:upload:execute plus the per-domain create
permissions relayed to downstream services (location:read, location:write,
crm:party:create, for the putaway-rules pack catalog:product:view plus
inventory:putaway_rule:view/inventory:putaway_rule:manage, and for the on-hand
pack inventory:adjustment:create and inventory:adjustment:approve, and for the
cycle-count-plans pack
inventory:cycle_count:view and inventory:cycle_count:initiate).
"""

import argparse
import csv
import datetime
import io
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

FIXTURE_ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures", "seed", "alpha")

# Dependency-ordered pack files (README run order): locations before anything
# that references them; customers before vehicles, whose owner names the loader
# resolves against the live party directory as it loads.
PACK_FILES = [
    ("security/users.csv", "@security-users"),
    ("location/locations.csv", "LOCATION"),
    ("location/storage-locations.csv", "@storage-locations"),
    ("location/bays.csv", "@location-bays"),
    ("location/mobile-units.csv", "@mobile-units"),
    ("people/employees.csv", "PERSON"),
    ("people/staffing-assignments.csv", "@staffing-assignments"),
    ("security/user-person-links.csv", "@user-person-links"),
    ("shop-manager/mechanic-skills.csv", "@mechanic-skills"),
    ("customer/person-customers.csv", "CUSTOMER"),
    ("customer/commercial-customers.csv", "COMMERCIAL_CUSTOMER"),
    ("vehicle/vehicles.csv", "VEHICLE"),
    ("catalog/products.csv", "CATALOG_PRODUCT"),
    ("inventory/putaway-rules.csv", "@putaway-rules"),
    ("inventory/on-hand.csv", "INVENTORY_STOCK_COUNT"),
    ("inventory/cycle-count-plans.csv", "@cycle-count-plans"),
]

# The catalog pack, reused by the putaway-rules pack to resolve category and
# subcategory names (see catalog_exemplar_skus).
CATALOG_PRODUCTS_PACK = "catalog/products.csv"

POLL_INTERVAL_SECONDS = 5
# PARTIAL is terminal too: the batch finished, but the owning service rejected some rows. Without
# it here the driver would poll a finished job forever and then report a timeout.
TERMINAL_STATUSES = {"COMPLETED", "PARTIAL", "FAILED", "CANCELLED"}


class Gateway:
    def __init__(self, base_url, token, api_version="1"):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.api_version = api_version

    def _request(self, method, path, body=None, content_type=None, allow_error=False):
        url = self.base_url + path
        headers = {
            "Authorization": f"Bearer {self.token}",
            "X-API-Version": self.api_version,
            "Accept": "application/json",
        }
        data = None
        if body is not None:
            data = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
            headers["Content-Type"] = content_type or "application/json"
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request) as response:
                payload = response.read()
                return response.status, json.loads(payload) if payload else None
        except urllib.error.HTTPError as error:
            if allow_error:
                error.read()
                return error.code, None
            detail = error.read().decode("utf-8", errors="replace")
            raise SystemExit(f"ERROR: {method} {url} -> HTTP {error.code}: {detail[:500]}") from error

    def get(self, path, allow_error=False):
        return self._request("GET", path, allow_error=allow_error)

    def post_json(self, path, body, allow_error=False):
        return self._request("POST", path, body=body, allow_error=allow_error)

    def put_json(self, path, body, allow_error=False):
        return self._request("PUT", path, body=body, allow_error=allow_error)

    def patch_json(self, path, body, allow_error=False):
        return self._request("PATCH", path, body=body, allow_error=allow_error)

    def post_multipart_file(self, path, field_name, file_name, file_bytes):
        boundary = uuid.uuid4().hex
        buffer = io.BytesIO()
        buffer.write(f"--{boundary}\r\n".encode())
        buffer.write(
            f'Content-Disposition: form-data; name="{field_name}"; filename="{file_name}"\r\n'.encode())
        buffer.write(b"Content-Type: text/csv\r\n\r\n")
        buffer.write(file_bytes)
        buffer.write(f"\r\n--{boundary}--\r\n".encode())
        return self._request(
            "POST", path, body=buffer.getvalue(), content_type=f"multipart/form-data; boundary={boundary}")




def run_staffing_assignments(gateway, relative_path, _location_id):
    """API pack: replay employee location assignments row by row.

    No bulk endpoint exists for staffing assignments; each row resolves
    employeeNumber -> personId (getEmployeeByNumber) and locationCode -> id
    (roster), then POSTs createStaffingAssignment. Employees must be loaded
    first (the PERSON pack precedes this one)."""
    _, roster = gateway.get("/location/locations")
    location_ids = {loc["code"]: loc["id"] for loc in roster or []}
    person_ids = {}
    effective_from = time.strftime("%Y-%m-%d")

    csv_path = os.path.join(FIXTURE_ROOT, relative_path)
    with open(csv_path, newline="") as fh:
        rows = list(csv.DictReader(fh))

    success = 0
    failures = 0
    for row in rows:
        employee_number = row["employeeNumber"]
        if employee_number not in person_ids:
            status_code, identity = gateway.get(
                f"/people/employees/by-number/{urllib.parse.quote(employee_number)}", allow_error=True)
            person_ids[employee_number] = (identity or {}).get("personId") if status_code == 200 else None
        person_id = person_ids[employee_number]
        location_id = location_ids.get(row["locationCode"])
        if person_id is None or location_id is None:
            missing = "employee" if person_id is None else f"location {row['locationCode']}"
            print(f"  WARN: {employee_number}: {missing} not found — assignment skipped")
            failures += 1
            continue
        status_code, _ = gateway.post_json(
            "/people/staffing/assignments",
            {
                "personId": person_id,
                "locationId": location_id,
                "role": row["role"],
                "primary": row["primary"].lower() == "true",
                "effectiveFrom": effective_from,
            },
            allow_error=True,
        )
        if 200 <= status_code < 300:
            success += 1
        else:
            print(f"  WARN: {employee_number} -> {row['locationCode']} {row['role']}: HTTP {status_code}")
            failures += 1

    print(f"  assignments: success={success} failures={failures} of {len(rows)}")
    return failures == 0


PASSWORD_OVERRIDES = {}
CREDENTIALS_OUT = "alpha-seed-credentials.csv"


def run_security_users(gateway, relative_path, _location_id):
    """API pack: provision demo users via POST /security-service/users.

    No password material lives in the fixture: each user's password comes from
    the --passwords-file override when present, otherwise it is generated here
    and written to a local (gitignored) credentials file. Passwords travel
    plaintext over TLS and are bcrypt-hashed server-side; an existing username
    (409) counts as already provisioned, not a failure."""
    import secrets

    csv_path = os.path.join(FIXTURE_ROOT, relative_path)
    with open(csv_path, newline="") as fh:
        rows = list(csv.DictReader(fh))

    created, skipped, failures = 0, 0, 0
    generated = []
    for row in rows:
        username = row["username"]
        password = PASSWORD_OVERRIDES.get(username)
        was_generated = password is None
        if was_generated:
            password = secrets.token_urlsafe(14)
        status_code, _ = gateway.post_json(
            "/security-service/users",
            {"username": username, "password": password, "roles": row["roles"].split(";")},
            allow_error=True,
        )
        if status_code == 201:
            created += 1
            if was_generated:
                generated.append((username, password))
        elif status_code == 409:
            skipped += 1
        else:
            print(f"  WARN: user {username}: HTTP {status_code}")
            failures += 1

    if generated:
        fd = os.open(CREDENTIALS_OUT, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        with os.fdopen(fd, "a") as fh:
            writer = csv.writer(fh)
            for username, password in generated:
                writer.writerow([username, password])
        print(f"  generated credentials for {len(generated)} user(s) -> {CREDENTIALS_OUT} (keep local; gitignored)")

    print(f"  users: created={created} already-existed={skipped} failures={failures} of {len(rows)}")
    return failures == 0


def location_id_map(gateway):
    _, roster = gateway.get("/location/locations")
    return {loc["code"]: loc["id"] for loc in roster or []}


def read_fixture_rows(relative_path):
    with open(os.path.join(FIXTURE_ROOT, relative_path), newline="") as fh:
        return list(csv.DictReader(fh))


def run_location_bays(gateway, relative_path, _location_id):
    """API pack: create service bays per location (409 by name = already there)."""
    location_ids = location_id_map(gateway)
    created, skipped, failures = 0, 0, 0
    for row in read_fixture_rows(relative_path):
        location_id = location_ids.get(row["locationCode"])
        if location_id is None:
            print(f"  WARN: bay {row['name']}: location {row['locationCode']} not found")
            failures += 1
            continue
        status_code, _ = gateway.post_json(
            f"/location/locations/{location_id}/bays",
            {
                "name": row["name"],
                "bayType": row["bayType"],
                "capacity": {"maxConcurrentVehicles": int(row["maxConcurrentVehicles"])},
            },
            allow_error=True,
        )
        if status_code == 201:
            created += 1
        elif status_code == 409:
            skipped += 1
        else:
            print(f"  WARN: bay {row['locationCode']}/{row['name']}: HTTP {status_code}")
            failures += 1
    print(f"  bays: created={created} already-existed={skipped} failures={failures}")
    return failures == 0


def run_mobile_units(gateway, relative_path, _location_id):
    """API pack: create mobile units; no server-side name dedupe, so existing
    names (from the paged list) are skipped for idempotent re-runs."""
    location_ids = location_id_map(gateway)
    _, page = gateway.get("/location/mobile-units?size=200", allow_error=True)
    existing = {unit["name"] for unit in (page or {}).get("content", [])}
    created, skipped, failures = 0, 0, 0
    for row in read_fixture_rows(relative_path):
        if row["name"] in existing:
            skipped += 1
            continue
        base_location_id = location_ids.get(row["baseLocationCode"])
        if base_location_id is None:
            print(f"  WARN: mobile unit {row['name']}: location {row['baseLocationCode']} not found")
            failures += 1
            continue
        status_code, _ = gateway.post_json(
            "/location/mobile-units",
            {"name": row["name"], "baseLocationId": base_location_id, "status": row["status"]},
            allow_error=True,
        )
        if 200 <= status_code < 300:
            created += 1
        else:
            print(f"  WARN: mobile unit {row['name']}: HTTP {status_code}")
            failures += 1
    print(f"  mobile units: created={created} already-existed={skipped} failures={failures}")
    return failures == 0


def run_storage_locations(gateway, relative_path, _location_id):
    """API pack: create the storage topology per location. Rows are processed in
    fixture order so parents (shelves) exist before their bins; existing names
    (from the site's paged list) are skipped and reused for parent resolution,
    making re-runs converge.

    Each row also carries its putaway capability (issue #1514):
    storageCategoryCode says what the location is fit to hold, independent of
    the physical type, and hazardContainment flags the ones with a spill bund.
    Both are sent only when the column is populated, so the service applies its
    own defaults (undeclared capability reads back as GENERAL, containment
    false) rather than this script guessing them.

    status and maxUnitCount (issue #1554) are applied by a follow-up
    PATCH through the owning service, because POST always creates ACTIVE and
    uncapped: an INACTIVE row ('Retired Bin') keeps the decommissioned-
    destination refusal path testable, and the capacity descriptors keep the
    capacity paths reachable. Each PATCH republishes the storage-location fact,
    so the pos-inventory replica sees the final state. Rows that already exist
    are never patched — the pack converges without overwriting operator edits."""
    location_ids = location_id_map(gateway)
    created, skipped, failures = 0, 0, 0
    by_location = {}
    for row in read_fixture_rows(relative_path):
        by_location.setdefault(row["locationCode"], []).append(row)

    for code, rows in by_location.items():
        site_id = location_ids.get(code)
        if site_id is None:
            print(f"  WARN: storage for {code}: location not found — {len(rows)} row(s) skipped")
            failures += len(rows)
            continue
        _, page = gateway.get(f"/location/locations/{site_id}/storage-locations?size=500", allow_error=True)
        name_to_id = {sl["name"]: sl["id"] for sl in (page or {}).get("content", [])}
        for row in rows:
            if row["name"] in name_to_id:
                skipped += 1
                continue
            body = {"name": row["name"], "type": row["type"]}
            if row.get("storageCategoryCode"):
                body["storageCategoryCode"] = row["storageCategoryCode"]
            if row.get("hazardContainment"):
                body["hazardContainment"] = row["hazardContainment"].strip().lower() == "true"
            if row["parentName"]:
                parent_id = name_to_id.get(row["parentName"])
                if parent_id is None:
                    print(f"  WARN: storage {code}/{row['name']}: parent {row['parentName']} unresolved")
                    failures += 1
                    continue
                body["parentStorageLocationId"] = parent_id
            status_code, response = gateway.post_json(
                f"/location/locations/{site_id}/storage-locations", body, allow_error=True)
            if 200 <= status_code < 300 and response:
                name_to_id[row["name"]] = response.get("id")
                created += 1
                patch = storage_location_patch(row)
                if patch:
                    status_code, _ = gateway.patch_json(
                        f"/location/locations/{site_id}/storage-locations/{response.get('id')}",
                        patch, allow_error=True)
                    if not 200 <= status_code < 300:
                        print(f"  WARN: storage {code}/{row['name']}: follow-up PATCH {sorted(patch)}"
                              f" failed (HTTP {status_code}) — row created but left ACTIVE/uncapped")
                        failures += 1
            else:
                print(f"  WARN: storage {code}/{row['name']}: HTTP {status_code}")
                failures += 1
    print(f"  storage locations: created={created} already-existed={skipped} failures={failures}")
    return failures == 0


def storage_location_patch(row):
    """The follow-up PATCH body for a freshly created storage location, or None.

    POST cannot express a non-ACTIVE status or a capacity descriptor, so those
    two columns are applied afterwards through the same owning-service surface
    an operator would use. The capacity map carries only maxUnitCount (the
    StorageCapacityJson cap); the fill is real on-hand stock, seeded by the
    inventory on-hand pack, never a declared number."""
    patch = {}
    status = (row.get("status") or "").strip().upper()
    if status and status != "ACTIVE":
        patch["status"] = status
    if (row.get("maxUnitCount") or "").strip():
        patch["capacity"] = {"maxUnitCount": int(row["maxUnitCount"])}
    return patch or None


def storage_location_ids(gateway, location_ids, cache, location_code):
    """name -> storage location id for one site, fetched once per site.

    The same site-scoped lookup run_storage_locations uses for parent
    resolution; here it turns a fixture's (locationCode, name) destination key
    into the id a putaway rule needs."""
    if location_code not in cache:
        cache[location_code] = {}
        site_id = location_ids.get(location_code)
        if site_id is None:
            print(f"  WARN: putaway rules: location {location_code} not found")
        else:
            status_code, page = gateway.get(
                f"/location/locations/{site_id}/storage-locations?size=500", allow_error=True)
            if status_code != 200:
                # Reported explicitly: without it every row of this site would blame the
                # fixture for an unresolved destination when the cause is the token
                # (location:read) or the location service being down.
                print(f"  WARN: putaway rules: cannot list {location_code} storage locations"
                      f" (HTTP {status_code}) — check location:read on the token")
            else:
                cache[location_code] = {sl["name"]: sl["id"] for sl in (page or {}).get("content", [])}
    return cache[location_code]


def catalog_exemplar_skus():
    """A representative SKU per category and per subcategory name, read from the
    catalog pack.

    pos-catalog exposes no endpoint that lists categories, so a name cannot be
    resolved to an id directly. What it does expose is a product's *resolved*
    category and subcategory (`GET /catalog/products/{id}`), and the catalog
    pack already carries the name-to-SKU mapping that produced them. Reading one
    loaded product per name back is therefore the resolution the API actually
    supports, and it keeps the fixtures keyed on business names instead of
    hardcoding Flyway-seeded uuids into this script."""
    categories, subcategories = {}, {}
    for row in read_fixture_rows(CATALOG_PRODUCTS_PACK):
        sku = (row.get("sku") or "").strip()
        if not sku:
            continue
        category = (row.get("categoryName") or "").strip()
        subcategory = (row.get("subcategoryName") or "").strip()
        if category:
            categories.setdefault(category.lower(), sku)
        if subcategory:
            subcategories.setdefault(subcategory.lower(), sku)
    return {"CATEGORY": categories, "SUBCATEGORY": subcategories}


def resolve_catalog_ref(gateway, cache, exemplars, match_type, name):
    """Resolve a category/subcategory name to its catalog id via an exemplar
    product, or None (with a WARN naming the cause) when it cannot be resolved.

    Every failure mode is reported rather than defaulted: a rule that silently
    lost its match value would be authored, accepted and never fire."""
    key = (match_type, name.lower())
    if key in cache:
        return cache[key]
    cache[key] = None

    if match_type not in exemplars:
        # SKU rules are legal in the model but this pack resolves classes, not products:
        # per-SKU slotting is an operator decision, not demo topology. Refuse the row rather
        # than crash the run, and say what would have to change.
        print(f"  WARN: {match_type} '{name}': seed-alpha.py resolves CATEGORY and SUBCATEGORY names only")
        return None
    sku = exemplars[match_type].get(name.lower())
    if sku is None:
        print(f"  WARN: {match_type} '{name}': no product in {CATALOG_PRODUCTS_PACK} carries this name")
        return None
    query = urllib.parse.urlencode({"sku": sku, "limit": "1"})
    status_code, page = gateway.get(f"/catalog/products/search?{query}", allow_error=True)
    matches = (page or {}).get("data") or []
    if status_code != 200 or not matches:
        print(f"  WARN: {match_type} '{name}': exemplar SKU {sku} is not in the catalog yet"
              f" — load {CATALOG_PRODUCTS_PACK} first")
        return None
    status_code, product = gateway.get(f"/catalog/products/{matches[0]['productId']}", allow_error=True)
    node = (product or {}).get("category" if match_type == "CATEGORY" else "subcategory") or {}
    ref_id, resolved_name = node.get("id"), (node.get("name") or "").strip()
    if status_code != 200 or not ref_id:
        print(f"  WARN: {match_type} '{name}': exemplar {sku} landed unclassified"
              f" — re-run {CATALOG_PRODUCTS_PACK} so category resolution applies")
        return None
    if resolved_name.lower() != name.lower():
        # The exemplar resolved to a different class than the fixture claims, so
        # the id is not the one this rule means. Refuse rather than route a whole
        # catalog class to the wrong bin.
        print(f"  WARN: {match_type} '{name}': exemplar {sku} resolved to '{resolved_name}' instead")
        return None
    cache[key] = ref_id
    return ref_id


def run_putaway_rules(gateway, relative_path, _location_id):
    """API pack: create the putaway rules that route received lines to a bin
    (issue #1514).

    Rules are Tier 2 seed data (docs/DATA_SEED_STRATEGY.md §2): they name
    per-environment storage location ids and have an @EmitEvent audited
    lifecycle, so they enter through the CRUD endpoint rather than Flyway.

    Nothing in the fixture is a uuid. Each row keys its catalog class by name
    and its destination by (locationCode, storage-location name), and both are
    resolved here — the destination against the site's storage-location list,
    the class against an exemplar product from the catalog pack. Runs last, so
    both of those are already loaded.

    Re-runs converge: the existing rules are listed up front and a row whose
    (matchType, matchValue) is already configured is skipped rather than
    duplicated, as is the endpoint's own 409 (a second enabled ANY rule). Rules
    are never updated in place — an operator who retuned a priority on alpha
    keeps it, and a deliberate fixture change is applied by deleting the rule and
    re-running.

    Skipping is not silent where it changes behaviour: a *disabled* existing rule
    blocks its fixture row and leaves that class with no reachable rule, and a
    pre-existing ANY rule may point somewhere the fixture's does not, so both
    print a WARN naming what to check."""
    location_ids = location_id_map(gateway)
    exemplars = catalog_exemplar_skus()
    ref_cache, storage_cache = {}, {}

    status_code, existing = gateway.get("/inventory/inventory/putaway/rules", allow_error=True)
    if status_code != 200:
        print(f"  WARN: putaway rules: cannot list existing rules (HTTP {status_code}) — nothing loaded")
        return False
    # matchValue is null for ANY, which the empty string keys consistently with
    # the fixture's empty matchName column.
    existing_rules = {
        (rule.get("matchType"), (rule.get("matchValue") or "").lower()): rule for rule in existing or []
    }

    created, skipped, failures = 0, 0, 0
    for row in read_fixture_rows(relative_path):
        match_type = row["matchType"].strip()
        match_name = (row.get("matchName") or "").strip()
        label = f"{match_type} '{match_name}'" if match_name else match_type

        match_value = None
        if match_type != "ANY":
            match_value = resolve_catalog_ref(gateway, ref_cache, exemplars, match_type, match_name)
            if match_value is None:
                failures += 1
                continue

        key = (match_type, (match_value or "").lower())
        if key in existing_rules:
            # Left alone deliberately (see the docstring), but a *disabled* rule occupying
            # this tier/class means the fixture's intent is not in effect: the class has no
            # reachable rule and its lines fall through to the ANY tier — or, for the ANY
            # rule itself, dead-end. Silence there would report a converged run that is not.
            if not existing_rules[key].get("isEnabled", True):
                print(f"  WARN: {label}: an existing but DISABLED rule already holds this tier;"
                      f" the fixture's rule was not created — enable or delete rule"
                      f" {existing_rules[key].get('ruleId')}")
            skipped += 1
            continue

        location_code = row["locationCode"].strip()
        destination_name = row["destinationName"].strip()
        destination_id = storage_location_ids(gateway, location_ids, storage_cache, location_code).get(
            destination_name)
        if destination_id is None:
            print(f"  WARN: {label}: destination {location_code}/{destination_name} unresolved")
            failures += 1
            continue

        body = {
            "priority": int(row["priority"]),
            "matchType": match_type,
            "destinationLocationId": destination_id,
        }
        if match_value:
            body["matchValue"] = match_value
        if row.get("destinationStrategy"):
            body["destinationStrategy"] = row["destinationStrategy"].strip()
        if row.get("isEnabled"):
            body["isEnabled"] = row["isEnabled"].strip().lower() == "true"

        status_code, response = gateway.post_json(
            "/inventory/inventory/putaway/rules", body, allow_error=True)
        if 200 <= status_code < 300:
            existing_rules[key] = response or {"matchType": match_type, "isEnabled": True}
            created += 1
        elif status_code == 409:
            # The endpoint's only 409: an enabled ANY rule already exists. The listing above
            # normally catches that first, so reaching here means the rule appeared between
            # the two calls — and the fixture's terminal fallback is NOT what is configured,
            # which matters because an ANY rule pointing at STAGING refuses every line.
            print(f"  WARN: {label}: an enabled ANY rule already exists, so this fixture row was"
                  f" not applied — check where the configured ANY rule points")
            skipped += 1
        else:
            print(f"  WARN: {label}: HTTP {status_code}")
            failures += 1

    print(f"  putaway rules: created={created} already-existed={skipped} failures={failures}")
    return failures == 0


def run_cycle_count_plans(gateway, relative_path, _location_id):
    """API pack: create demo cycle count plans through the plan lifecycle
    (issue #1554, replacing the cycle_count_plan/cycle_count_plan_zone rows the
    Flyway seed briefly carried against invented storage-location UUIDs).

    Each row names its site and zones by business key: locationCode resolves
    against the roster and every pipe-separated zoneNames entry against that
    site's storage-location list, so the pack runs after the location pack.
    scheduledDate is computed as today + scheduledDaysOut because the endpoint
    requires a strictly future date — a fixed date would rot. Convergence: the
    driver lists the site's existing plans first and skips a row whose planName
    is already present, so re-runs create nothing. Plans are created in PLANNED
    status only — task generation is a demo action, deliberately not seeded.
    The token needs inventory:cycle_count:view (the plan list) and
    inventory:cycle_count:initiate (the create)."""
    location_ids = location_id_map(gateway)
    storage_cache = {}
    created, skipped, failures = 0, 0, 0
    for row in read_fixture_rows(relative_path):
        code, plan_name = row["locationCode"], row["planName"]
        site_id = location_ids.get(code)
        if site_id is None:
            print(f"  WARN: cycle count plan '{plan_name}': location {code} not found")
            failures += 1
            continue
        names = storage_location_ids(gateway, location_ids, storage_cache, code)
        zone_ids, unresolved = [], []
        for zone_name in row["zoneNames"].split("|"):
            zone_id = names.get(zone_name)
            if zone_id is None:
                unresolved.append(zone_name)
            else:
                zone_ids.append(zone_id)
        if unresolved:
            print(f"  WARN: cycle count plan '{plan_name}': unresolved zone(s) {unresolved}")
            failures += 1
            continue

        query = urllib.parse.urlencode({"locationId": site_id, "size": 200})
        status_code, existing = gateway.get(f"/inventory/inventory/cycleCountPlans?{query}", allow_error=True)
        if status_code != 200:
            print(f"  WARN: cycle count plan '{plan_name}': cannot list existing plans"
                  f" (HTTP {status_code}) — check inventory:cycle_count:view on the token")
            failures += 1
            continue
        if any(plan.get("planName") == plan_name for plan in existing or []):
            skipped += 1
            continue

        scheduled_date = datetime.date.today() + datetime.timedelta(days=int(row["scheduledDaysOut"]))
        status_code, _ = gateway.post_json(
            "/inventory/inventory/cycleCountPlans",
            {
                "locationId": site_id,
                "zoneIds": zone_ids,
                "planName": plan_name,
                "scheduledDate": scheduled_date.isoformat(),
            },
            allow_error=True)
        if 200 <= status_code < 300:
            created += 1
        else:
            print(f"  WARN: cycle count plan '{plan_name}': HTTP {status_code}")
            failures += 1

    print(f"  cycle count plans: created={created} already-existed={skipped} failures={failures}")
    return failures == 0


def run_mechanic_skills(gateway, relative_path, _location_id):
    """API pack: replace-set each mechanic's skills via pos-shop-manager.

    Runs after the staffing assignments: mechanics are projected from ACTIVE
    TECHNICIAN assignments over Kafka, so a 404 here usually means the
    projection has not caught up yet — re-run this pack once it has. Each PUT
    replaces the full skill set, so re-runs converge."""
    rows = read_fixture_rows(relative_path)
    by_employee = {}
    for row in rows:
        by_employee.setdefault(row["employeeNumber"], []).append(
            {"skillCode": row["skillCode"], "proficiencyLevel": int(row["proficiencyLevel"])})

    updated, failures = 0, 0
    for employee_number, skills in by_employee.items():
        status_code, identity = gateway.get(
            f"/people/employees/by-number/{urllib.parse.quote(employee_number)}", allow_error=True)
        person_id = (identity or {}).get("personId") if status_code == 200 else None
        if person_id is None:
            print(f"  WARN: {employee_number}: employee not found — skills skipped")
            failures += 1
            continue
        status_code, _ = gateway.put_json(
            f"/shop-manager/mechanics/by-person/{person_id}/skills", {"skills": skills}, allow_error=True)
        if status_code == 204:
            updated += 1
        elif status_code == 404:
            print(f"  WARN: {employee_number}: mechanic projection not there yet — re-run after the feed catches up")
            failures += 1
        else:
            print(f"  WARN: {employee_number}: HTTP {status_code}")
            failures += 1
    print(f"  mechanic skills: updated={updated} failures={failures} of {len(by_employee)} mechanics")
    return failures == 0


def run_user_person_links(gateway, relative_path, _location_id):
    """API pack: link user accounts to their canonical persons.

    Runs after the employees pack: usernames resolve to user ids via the user
    directory, employee numbers to person ids via getEmployeeByNumber, then
    PUT /users/{id}/person-link queues the people-contact link command (the
    users.person_id projection lands asynchronously). Re-runs re-queue the
    same link, which the link consumer upserts by username."""
    _, users = gateway.get("/security-service/users")
    user_ids = {u["username"]: u["id"] for u in users or []}

    queued, failures = 0, 0
    for row in read_fixture_rows(relative_path):
        user_id = user_ids.get(row["username"])
        if user_id is None:
            print(f"  WARN: link {row['username']}: user not found")
            failures += 1
            continue
        status_code, identity = gateway.get(
            f"/people/employees/by-number/{urllib.parse.quote(row['employeeNumber'])}", allow_error=True)
        person_id = (identity or {}).get("personId") if status_code == 200 else None
        if person_id is None:
            print(f"  WARN: link {row['username']}: employee {row['employeeNumber']} not found")
            failures += 1
            continue
        status_code, _ = gateway._request(
            "PUT", f"/security-service/users/{user_id}/person-link", body={"personId": person_id}, allow_error=True)
        if 200 <= status_code < 300:
            queued += 1
        else:
            print(f"  WARN: link {row['username']}: HTTP {status_code}")
            failures += 1

    print(f"  user-person links: queued={queued} failures={failures}")
    return failures == 0


API_PACKS = {
    "@staffing-assignments": run_staffing_assignments,
    "@mechanic-skills": run_mechanic_skills,
    "@security-users": run_security_users,
    "@user-person-links": run_user_person_links,
    "@location-bays": run_location_bays,
    "@mobile-units": run_mobile_units,
    "@storage-locations": run_storage_locations,
    "@putaway-rules": run_putaway_rules,
    "@cycle-count-plans": run_cycle_count_plans,
}


def resolve_location_id(gateway, location_code):
    _, locations = gateway.get("/location/locations")
    for location in locations or []:
        if location.get("code") == location_code:
            return location["id"]
    return None


def bootstrap_location(gateway, location_code):
    """Create the bootstrap location from the matching row of locations.csv."""
    csv_path = os.path.join(FIXTURE_ROOT, "location", "locations.csv")
    with open(csv_path, newline="") as fh:
        rows = list(csv.DictReader(fh))
    row = next((r for r in rows if r["code"] == location_code), rows[0])
    body = {
        "name": row["name"],
        "code": row["code"],
        "addressLine1": row["addressLine1"] or None,
        "addressLine2": row["addressLine2"] or None,
        "city": row["city"] or None,
        "state": row["stateOrProvince"] or None,
        "postalCode": row["postalCode"] or None,
        "country": row["countryCode"] or None,
        "timezone": row["timezone"] or None,
        "active": True,
        "type": {"name": row["locationTypeName"] or "STORE"},
    }
    _, created = gateway.post_json("/location/locations", body)
    print(f"  bootstrapped location {created['code']} -> {created['id']}")
    return created["id"]


def run_pack_file(gateway, relative_path, domain_type, location_id, poll_timeout_seconds):
    csv_path = os.path.join(FIXTURE_ROOT, relative_path)
    file_name = os.path.basename(csv_path)
    with open(csv_path, "rb") as fh:
        file_bytes = fh.read()
    data_rows = max(0, len(file_bytes.decode("utf-8").strip().splitlines()) - 1)

    _, job = gateway.post_json(
        "/bulk-loader/bulk-jobs",
        {"fileName": file_name, "domainType": domain_type, "locationId": location_id},
    )
    job_id = job["id"]
    print(f"  job {job_id} created ({domain_type}, {data_rows} rows)")

    gateway.post_multipart_file(f"/bulk-loader/bulk-jobs/{job_id}/upload", "file", file_name, file_bytes)
    gateway.post_json(f"/bulk-loader/bulk-jobs/{job_id}/process", None)

    deadline = time.monotonic() + poll_timeout_seconds
    while True:
        _, status = gateway.get(f"/bulk-loader/bulk-jobs/{job_id}")
        if status["status"] in TERMINAL_STATUSES:
            break
        if time.monotonic() > deadline:
            print(f"  TIMEOUT: job {job_id} still {status['status']} after {poll_timeout_seconds}s")
            return False
        time.sleep(POLL_INTERVAL_SECONDS)

    ok = status["status"] == "COMPLETED" and not status.get("failureCount")
    print(
        f"  job {job_id}: {status['status']} — processed={status.get('processedRows')} "
        f"success={status.get('successCount')} failures={status.get('failureCount')}"
    )
    if status.get("failureCount"):
        # Every rejected row now has an audit record naming what the owning service said about it,
        # so point at the listing that carries the reason rather than the job summary.
        print(f"  review failures: GET {gateway.base_url}/bulk-loader/bulk-jobs/{job_id}/audit")
    return ok


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--gateway", required=True, help="Alpha API gateway base URL")
    parser.add_argument("--token", default=os.environ.get("SEED_BEARER_TOKEN"),
                        help="Bearer token (default: $SEED_BEARER_TOKEN)")
    parser.add_argument("--location-code", default="CLT-MAIN-001",
                        help="Location code that scopes the bulk-load jobs (default: CLT-MAIN-001)")
    parser.add_argument("--location-id", help="Skip roster resolution and use this location id")
    parser.add_argument("--bootstrap-location", action="store_true",
                        help="Create the --location-code location from locations.csv when the roster lacks it")
    parser.add_argument("--only", action="append", default=None, metavar="PACK_FILE",
                        help="Run only these pack files (repeatable, e.g. customer/person-customers.csv)")
    parser.add_argument("--poll-timeout", type=int, default=600,
                        help="Seconds to wait for each job to finish (default: 600)")
    parser.add_argument("--passwords-file", metavar="CSV",
                        help="Local username,password CSV overriding generated passwords (never commit it)")
    parser.add_argument("--credentials-out", default="alpha-seed-credentials.csv",
                        help="Where generated credentials are written (default: alpha-seed-credentials.csv; gitignored)")
    parser.add_argument("--dry-run", action="store_true", help="List planned actions without calling the gateway")
    args = parser.parse_args()

    selected = [(p, d) for p, d in PACK_FILES if args.only is None or p in args.only]
    if args.only:
        unknown = set(args.only) - {p for p, _ in PACK_FILES}
        if unknown:
            parser.error(f"unknown pack file(s): {', '.join(sorted(unknown))}")
    if not selected:
        parser.error("nothing selected")

    if args.dry_run:
        print(f"dry-run against {args.gateway}; location code {args.location_id or args.location_code}")
        for path, domain in selected:
            print(f"  would load {path} as {domain}")
        return 0

    if not args.token:
        parser.error("--token or $SEED_BEARER_TOKEN is required")

    gateway = Gateway(args.gateway, args.token)

    global CREDENTIALS_OUT
    CREDENTIALS_OUT = args.credentials_out
    if args.passwords_file:
        with open(args.passwords_file, newline="") as fh:
            for row in csv.reader(fh):
                if len(row) >= 2 and row[0] and not row[0].startswith("#"):
                    PASSWORD_OVERRIDES[row[0]] = row[1]

    location_id = args.location_id or resolve_location_id(gateway, args.location_code)
    if location_id is None:
        if not args.bootstrap_location:
            raise SystemExit(
                f"ERROR: location {args.location_code} not found and --bootstrap-location not given; "
                "bulk-load jobs need an existing location")
        location_id = bootstrap_location(gateway, args.location_code)
        print("  note: the bootstrapped row will report one expected duplicate failure in the LOCATION job")

    all_ok = True
    for path, domain in selected:
        print(f"pack {path} -> {domain}")
        if domain in API_PACKS:
            all_ok = API_PACKS[domain](gateway, path, location_id) and all_ok
        else:
            all_ok = run_pack_file(gateway, path, domain, location_id, args.poll_timeout) and all_ok

    print("done" if all_ok else "done with failures — inspect the review queue / job counters above")
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
