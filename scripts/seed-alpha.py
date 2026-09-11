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
      --token "$SEED_BEARER_TOKEN" [--tenant-id "$SEED_TENANT_ID"] [--location-code CLT-MAIN-001] \
      [--bootstrap-location] [--only customer/person-customers.csv] [--dry-run]

Every job loads into --tenant-id (ADR-0062, plan WS8), which must be the token's own tenant
(its tid claim; the default when --tenant-id is omitted): the loader's upload, process and
status calls are scoped to the token's tenant. Loading security/roles.csv and
security/role-permissions.csv with a PLATFORM_ADMIN token and --tenant-id set to the platform
tenant (plus an explicit --location-id, the platform tenant having no locations) makes those
roles the role template; see docs/OPERATIONS_RUNBOOK.md, "Bulk loading into a tenant".
PLATFORM_ADMIN is seeded with exactly the four grants that load needs on top of its platform:*
families -- bulkImport:upload:execute, bulkImport:status:read, security:role:create and
security:role:edit (R__seed_tenant_template.sql) -- so no extra role is needed for it.

Seeded user accounts get a password generated inside pos-security-service and
returned to no one, so they have no usable login until someone goes through the
reset path. That is deliberate: a bulk file is uploaded and stored, and a
password column in it would exist at rest for as long as the upload does.

The bearer token needs bulkImport:upload:execute and bulkImport:status:read plus the
per-domain create permissions relayed to downstream services (location:read, location:write,
crm:party:create, for the putaway-rules pack catalog:product:view plus
inventory:putaway_rule:view/inventory:putaway_rule:manage, and for the on-hand
pack inventory:adjustment:create and inventory:adjustment:approve, and for the
cycle-count-plans pack
inventory:cycle_count:view and inventory:cycle_count:initiate, for the Tier 0 catalog packs
catalog:service:ingest, catalog:labor_standard:import and catalog:service_package:manage, and
for the labor-rate packs pricing:labor_rate:manage).
"""

import argparse
import base64
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
    # Roles first: users name the roles they are assigned (#1613 D8). Grants follow the roles,
    # and both come before users so an account is never provisioned against a role that does not
    # exist yet.
    ("security/roles.csv", "SECURITY_ROLE"),
    ("security/role-permissions.csv", "SECURITY_ROLE_PERMISSION"),
    ("security/users.csv", "SECURITY_USER"),
    ("location/locations.csv", "LOCATION"),
    ("location/storage-locations.csv", "STORAGE_LOCATION"),
    ("location/site-defaults.csv", "@site-defaults"),
    ("location/bays.csv", "BAY"),
    ("location/mobile-units.csv", "MOBILE_UNIT"),
    ("people/employees.csv", "PERSON"),
    ("people/staffing-assignments.csv", "STAFFING_ASSIGNMENT"),
    ("security/user-person-links.csv", "USER_PERSON_LINK"),
    ("shop-manager/mechanic-skills.csv", "MECHANIC_SKILL"),
    ("customer/person-customers.csv", "CUSTOMER"),
    ("customer/commercial-customers.csv", "COMMERCIAL_CUSTOMER"),
    ("vehicle/vehicles.csv", "VEHICLE"),
    ("catalog/products.csv", "CATALOG_PRODUCT"),
    # Tier 0 service data (#1575). Operations first: the labor standards, the packages and the
    # package members all name operations by their operation code, and a code the catalog has not
    # heard of fails its row. Packages before members for the same reason.
    ("catalog/tier0-services.csv", "CATALOG_SERVICE"),
    ("catalog/tier0-labor-standards.csv", "SERVICE_LABOR_STANDARD"),
    ("catalog/tier0-service-packages.csv", "SERVICE_PACKAGE"),
    ("catalog/tier0-service-package-members.csv", "SERVICE_PACKAGE_MEMBER"),
    ("price/base-prices.csv", "BASE_PRICE"),
    ("price/labor-rates.csv", "LABOR_RATE"),
    ("price/labor-rate-adjustments.csv", "LABOR_RATE_ADJUSTMENT"),
    ("inventory/putaway-rules.csv", "PUTAWAY_RULE"),
    ("inventory/on-hand.csv", "INVENTORY_STOCK_COUNT"),
    ("inventory/cycle-count-plans.csv", "CYCLE_COUNT_PLAN"),
]

# The catalog pack, reused by the putaway-rules pack to resolve category and
# subcategory names (see catalog_exemplar_skus).
CATALOG_PRODUCTS_PACK = "catalog/products.csv"

POLL_INTERVAL_SECONDS = 5

# The tenant every job loads into (ADR-0062, plan WS8), from --tenant-id, else the token's own
# tenant (its tid claim). None omits tenantId from the create request, which the loader accepts only
# while its transitional default tenant is configured (it then loads into that default and logs a
# WARN).
TARGET_TENANT_ID = None

PLATFORM_TENANT_ID = "01900000-0000-7000-8000-000000000000"

# The packs that make sense in the platform tenant: the role template (docs/OPERATIONS_RUNBOOK.md,
# "Reconciling the role template"). Every other pack is tenant data.
PLATFORM_PACK_FILES = {"security/roles.csv", "security/role-permissions.csv"}


def token_tenant_id(token):
    """The tid claim of a JWT, or None when the token carries none (unverified: this only picks
    the tenant the loader will bind, the gateway verifies the signature)."""
    parts = token.split(".")
    if len(parts) < 2:
        return None
    payload = parts[1] + "=" * (-len(parts[1]) % 4)
    try:
        claims = json.loads(base64.urlsafe_b64decode(payload))
    except (ValueError, UnicodeDecodeError):
        return None
    tid = claims.get("tid") if isinstance(claims, dict) else None
    try:
        return str(uuid.UUID(tid)) if tid else None
    except (ValueError, AttributeError, TypeError):
        # A claim that is not a UUID string at all -- a number, a list, an object -- reaches
        # uuid.UUID() as the wrong type and raises AttributeError or TypeError rather than
        # ValueError. Any of the three means the same thing here: this token names no tenant.
        return None
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


API_PACKS = {
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

    body = {"fileName": file_name, "domainType": domain_type, "locationId": location_id}
    if TARGET_TENANT_ID:
        body["tenantId"] = TARGET_TENANT_ID
    _, job = gateway.post_json("/bulk-loader/bulk-jobs", body)
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
    parser.add_argument("--tenant-id", default=os.environ.get("SEED_TENANT_ID"),
                        help="Tenant every job loads into (ADR-0062; default: $SEED_TENANT_ID, else the token's "
                             "tid claim). Must be the token's own tenant: the loader's upload, process and status "
                             "endpoints are scoped to the token's tenant, so a job created elsewhere could not be "
                             "continued. A PLATFORM_ADMIN token with the platform tenant "
                             "01900000-0000-7000-8000-000000000000 makes security/roles.csv the role template.")
    parser.add_argument("--poll-timeout", type=int, default=600,
                        help="Seconds to wait for each job to finish (default: 600)")
    parser.add_argument("--dry-run", action="store_true", help="List planned actions without calling the gateway")
    args = parser.parse_args()

    global TARGET_TENANT_ID
    if args.tenant_id:
        try:
            TARGET_TENANT_ID = str(uuid.UUID(args.tenant_id))
        except ValueError:
            parser.error(f"--tenant-id is not a UUID: {args.tenant_id}")
    token_tenant = token_tenant_id(args.token) if args.token else None
    if TARGET_TENANT_ID is None:
        TARGET_TENANT_ID = token_tenant
    elif token_tenant is not None and token_tenant != TARGET_TENANT_ID:
        # The loader creates the job in the target tenant, but upload, process and status are
        # tenant-scoped reads under the token's tenant: a job created elsewhere is invisible to
        # the calls that follow. Refuse up front rather than fail after the first job is created.
        parser.error(
            f"--tenant-id {TARGET_TENANT_ID} is not the token's tenant (tid {token_tenant}); the bulk loader's "
            "job endpoints are scoped to the token's tenant, so use a token of the target tenant (a tenant "
            "administrator, or a PLATFORM_ADMIN token for the platform tenant)")

    selected = [(p, d) for p, d in PACK_FILES if args.only is None or p in args.only]
    if args.only:
        unknown = set(args.only) - {p for p, _ in PACK_FILES}
        if unknown:
            parser.error(f"unknown pack file(s): {', '.join(sorted(unknown))}")
    if not selected:
        parser.error("nothing selected")

    if TARGET_TENANT_ID == PLATFORM_TENANT_ID:
        # The platform tenant holds the role template and nothing else: no locations to resolve a
        # code against, and no owning service expects its rows there.
        not_platform = [p for p, _ in selected if p not in PLATFORM_PACK_FILES]
        if not_platform:
            parser.error("only the role template loads into the platform tenant "
                         f"({', '.join(sorted(PLATFORM_PACK_FILES))}); use --only. Not platform data: "
                         f"{', '.join(not_platform)}")
        if not args.location_id:
            parser.error("the platform tenant has no locations to resolve --location-code against; pass "
                         "--location-id explicitly (the role ingest carries the id along and ignores it, so the "
                         "nil UUID 00000000-0000-0000-0000-000000000000 will do)")

    if args.dry_run:
        print(f"dry-run against {args.gateway}; location code {args.location_id or args.location_code}; "
              f"tenant {TARGET_TENANT_ID or '(loader default)'}")
        for path, domain in selected:
            print(f"  would load {path} as {domain}")
        return 0

    if not args.token:
        parser.error("--token or $SEED_BEARER_TOKEN is required")

    gateway = Gateway(args.gateway, args.token)

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
