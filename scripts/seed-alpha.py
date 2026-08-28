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
    ("location/storage-locations.csv", "STORAGE_LOCATION"),
    ("location/site-defaults.csv", "@site-defaults"),
    ("location/bays.csv", "BAY"),
    ("location/mobile-units.csv", "MOBILE_UNIT"),
    ("people/employees.csv", "PERSON"),
    ("people/staffing-assignments.csv", "STAFFING_ASSIGNMENT"),
    ("security/user-person-links.csv", "@user-person-links"),
    ("shop-manager/mechanic-skills.csv", "@mechanic-skills"),
    ("customer/person-customers.csv", "CUSTOMER"),
    ("customer/commercial-customers.csv", "COMMERCIAL_CUSTOMER"),
    ("vehicle/vehicles.csv", "VEHICLE"),
    ("catalog/products.csv", "CATALOG_PRODUCT"),
    ("inventory/putaway-rules.csv", "PUTAWAY_RULE"),
    ("inventory/on-hand.csv", "INVENTORY_STOCK_COUNT"),
    ("inventory/cycle-count-plans.csv", "CYCLE_COUNT_PLAN"),
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


def run_site_defaults(gateway, relative_path, _location_id):
    """API pack: declare each site's default staging and quarantine locations (issue #1557).

    Without this the sites have no defaults, and StagingLocationResolver falls
    through to a hardcoded 00000000-...-002 that is not a row in any
    storage_location table. Putaway refuses any receipt not booked at the
    resolved staging location, so the staging bins this pipeline just created
    are unreachable: a receipt booked at the real Staging Floor -- the one a
    human or a UI would pick -- is refused with RECEIPT_NOT_STAGED.

    Runs straight after the storage topology, because both names have to exist
    before they can be pointed at, and before the inventory packs, which is
    where receiving starts to matter. The endpoint is a create-or-replace
    upsert, so re-runs converge; it also requires the two ids to differ and to
    belong to the site, which is why they are resolved per site rather than
    assumed."""
    location_ids = location_id_map(gateway)
    storage_cache = {}
    configured, failures = 0, 0

    for row in read_fixture_rows(relative_path):
        code = row["locationCode"]
        site_id = location_ids.get(code)
        if site_id is None:
            print(f"  WARN: site defaults for {code}: location not found")
            failures += 1
            continue

        names = storage_location_ids(gateway, location_ids, storage_cache, code)
        staging_id = names.get(row["stagingName"])
        quarantine_id = names.get(row["quarantineName"])
        missing = [
            name
            for name, resolved in ((row["stagingName"], staging_id), (row["quarantineName"], quarantine_id))
            if resolved is None
        ]
        if missing:
            print(f"  WARN: site defaults for {code}: unresolved storage location(s) {missing}")
            failures += 1
            continue

        status_code, _ = gateway._request(
            "PUT",
            f"/location/locations/{site_id}/defaults",
            body={"defaultStagingLocationId": staging_id, "defaultQuarantineLocationId": quarantine_id},
            allow_error=True,
        )
        if 200 <= status_code < 300:
            configured += 1
        else:
            print(f"  WARN: site defaults for {code}: HTTP {status_code}")
            failures += 1

    print(f"  site defaults: configured={configured} failures={failures}")
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
    "@mechanic-skills": run_mechanic_skills,
    "@security-users": run_security_users,
    "@user-person-links": run_user_person_links,
    "@site-defaults": run_site_defaults,
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
