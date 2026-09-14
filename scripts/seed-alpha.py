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
and the LOCATION pack then skips that row rather than re-sending it).

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
for the labor-rate packs pricing:labor_rate:manage). The mobile-units pack additionally needs
location:mobile-unit:manage and location:mobile-unit:read to create and list the units, plus
location:travel-buffer-policy:read and location:service-area:read to resolve the policy and
service area names its fixtures carry.
"""

import argparse
import base64
import collections
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
    ("location/mobile-units.csv", "@mobile-units"),
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

# Row-level error code a service returns when it cannot yet judge a row because state it receives
# asynchronously has not arrived (pos-bulk-ingest-lib's BulkIngestFailures.RETRYABLE_ERROR_CODE).
#
# This is what replaced the blind `--settle-seconds` sleep of #1981. The driver no longer guesses
# that a pack might be racing replication; the owning service says so, per row, and the driver
# re-runs only a pack whose every failure carries this code. A pack that failed for any other
# reason is a real failure and is reported as one on the first attempt.
#
# Re-running is safe for the packs this can fire for, and the property has to be established before
# adding any pack that might see this code: STAFFING_ASSIGNMENT refuses a row overlapping one
# already stored, so a landed row cannot be written twice, and MECHANIC_SKILL replaces a mechanic's
# whole skill set, so a replay converges rather than accumulating. Absent both, a retry duplicates.
REPLICATION_PENDING_CODE = "REPLICATION_PENDING"
MAX_REPLICATION_ATTEMPTS = 4
REPLICATION_BACKOFF_SECONDS = 5

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


MOBILE_UNIT_COVERAGE_RULES_PACK = "location/mobile-unit-coverage-rules.csv"


def named_ids(gateway, path, label):
    """name -> id for a reference collection the gateway returns as a bare list.

    Both /location/travel-buffer-policies and /location/service-areas are
    unpaginated `List<T>` endpoints, so one call is the whole roster. Fetched
    once per run and handed to every row rather than re-resolved per unit: nine
    units naming three policies between them is nine identical round trips
    otherwise."""
    status_code, roster = gateway.get(path, allow_error=True)
    if status_code != 200:
        # Named explicitly: without it every unit would look like a bad fixture name when the
        # cause is the token's permissions or the location service being down.
        print(f"  WARN: cannot list {label} (HTTP {status_code}) — check the token's read permission")
        return {}
    return {entry["name"]: entry["id"] for entry in roster or []}


def total_pages(body):
    """How many pages a Spring `Page` response says it has, under either serialisation.

    Spring Boot 4 serialises a raw `Page` flat by default but nests the metadata under `page` when
    `spring.data.web.pageable.serialization-mode=VIA_DTO`, which is the non-deprecated setting. A
    reader that only knows the flat shape silently stops after page 0; harmless at nine units, and
    a re-run that re-POSTs everything past the first hundred once there are more."""
    body = body or {}
    nested = body.get("page") if isinstance(body.get("page"), dict) else {}
    return body.get("totalPages") or nested.get("totalPages") or 1


def existing_mobile_units(gateway):
    """name -> the unit as the service currently holds it.

    The service rejects a duplicate (baseLocationId, name) with 409 rather than upserting, so a
    re-run has to skip what is already there. But skipping on the name alone is not enough: an alpha
    seeded before #1986 carries all nine names as INACTIVE units with no policy, capabilities or
    coverage, and treating those as done would report a clean run while leaving eligibility empty
    forever. The whole unit is kept so mobile_unit_shortfall can tell the two apart."""
    units, page = {}, 0
    while True:
        status_code, body = gateway.get(f"/location/mobile-units?page={page}&size=100", allow_error=True)
        if status_code != 200:
            print(f"  WARN: cannot list existing mobile units (HTTP {status_code}); "
                  "assuming none and letting duplicates fail per row")
            return {}
        for unit in (body or {}).get("content") or []:
            units[unit["name"]] = unit
        page += 1
        if page >= total_pages(body):
            return units


def mobile_unit_shortfall(gateway, unit, row, rules):
    """Why an already-present unit falls short of what the fixture describes, or None when it does not.

    Only what the fixture asks for is checked: a row the fixture parks INACTIVE is complete as soon
    as it exists. For an ACTIVE row this is the same trio
    MobileUnitServiceImpl.validateCreateMobileUnitRequest demands at create, plus the coverage rules
    the list response does not carry -- fetched per unit, which only happens on a re-run.

    There is deliberately no repair path here. A missing policy could be PATCHed and missing coverage
    PUT, but capabilityIds is not a PATCH key (MobileUnitServiceImpl:58-60), so an incomplete unit
    cannot be completed through the API at all; PATCHing it ACTIVE anyway would use PATCH's lack of
    validation to build the exact state the create path refuses. Saying so and requiring a reset is
    the honest option."""
    if row["status"].strip().upper() != "ACTIVE":
        return None

    missing = []
    if (unit.get("status") or "").strip().upper() != "ACTIVE":
        missing.append("is not ACTIVE")
    if not unit.get("travelBufferPolicyId"):
        missing.append("has no travel buffer policy")
    if not unit.get("capabilityIds"):
        missing.append("has no capabilities")
    if rules and not missing:
        # Only worth a call once the cheap checks pass: a unit failing those needs a reset regardless.
        status_code, current = gateway.get(
            f"/location/mobile-units/{unit['id']}/coverage-rules", allow_error=True)
        if status_code != 200:
            missing.append(f"coverage rules could not be read (HTTP {status_code})")
        elif not current:
            missing.append("has no coverage rules")
    return " and ".join(missing) if missing else None


def coverage_rules_by_unit(service_area_ids):
    """unit name -> coverage rule payloads, in fixture row order.

    Row order is load-bearing for DISTANCE_TIER: MobileUnitServiceImpl.validateDistanceTiers walks
    the list as given and requires strictly ascending maxDistance ending in a single null catch-all
    (and it applies to every rule on the unit once any one of them is DISTANCE_TIER). Sorting or
    regrouping these rows would reject the very fixture that was written to satisfy it."""
    rules = {}
    unresolved = set()
    for row in read_fixture_rows(MOBILE_UNIT_COVERAGE_RULES_PACK):
        unit_name = row["unitName"]
        rules.setdefault(unit_name, [])
        area_name = row["serviceAreaName"]
        area_id = service_area_ids.get(area_name)
        if area_id is None:
            print(f"  WARN: coverage rule for {unit_name}: unknown service area {area_name!r}")
            unresolved.add(unit_name)
            continue
        rule = {"serviceAreaId": area_id, "ruleType": row["ruleType"]}
        if row.get("priority"):
            try:
                rule["priority"] = int(row["priority"])
            except ValueError:
                # Everything else here degrades to a WARN and a failed row; an unguarded int()
                # raised out of main() instead, aborting every pack still queued behind this one.
                print(f"  WARN: coverage rule for {unit_name}: priority {row['priority']!r} is not a number")
                unresolved.add(unit_name)
                continue
        # Left out entirely when the column is blank rather than sent as "": maxDistance is a
        # BigDecimal and the two dates are LocalDate on CoverageRuleRequest, so an empty string is
        # a 400 -- and a blank maxDistance is the null catch-all tier, which "" would not read as.
        if row.get("maxDistance"):
            rule["maxDistance"] = row["maxDistance"]
        for date_field in ("validFrom", "validTo"):
            if row.get(date_field):
                rule[date_field] = row[date_field]
        rules[unit_name].append(rule)
    # One unresolved area drops the whole unit rather than the single rule: the rules that did
    # resolve would otherwise give it narrower coverage than the fixture describes, and nothing
    # downstream would say so. Insertion order is the fixture's row order, which is load-bearing.
    return {name: (None if name in unresolved else built) for name, built in rules.items()}


def run_mobile_units(gateway, relative_path, _location_id):
    """API pack: create each mobile unit complete with its policy, capabilities and coverage (#1986).

    pos-location refuses an ACTIVE unit that arrives without all three
    (MobileUnitServiceImpl.validateCreateMobileUnitRequest), and the bulk loader's MOBILE_UNIT
    strategy carries only name/baseLocationCode/status/notes — so eight of the nine rows failed on
    every run until #1982 parked them all INACTIVE. POST /v1/mobile-units takes the whole unit in
    one call, coverage rules included, so the driver assembles it here rather than the loader
    growing three fields and a second fixture.

    Names, not ids, key both fixtures: the travel buffer policy and the service areas are tier-1
    reference data seeded by R__seed_location_1_reference.sql, and every other fixture in this tree
    names its references the same way."""
    location_ids = location_id_map(gateway)
    policy_ids = named_ids(gateway, "/location/travel-buffer-policies", "travel buffer policies")
    service_area_ids = named_ids(gateway, "/location/service-areas", "service areas")
    rules_by_unit = coverage_rules_by_unit(service_area_ids)
    existing = existing_mobile_units(gateway)

    unit_rows = read_fixture_rows(relative_path)
    # A coverage row whose unitName matches no unit -- a typo -- would otherwise be dropped in
    # silence, and the unit it was meant for posted with no coverage at all: rejected as an opaque
    # HTTP 400 if it is ACTIVE, and silently under-covered if it is not.
    orphans = sorted(set(rules_by_unit) - {row["name"] for row in unit_rows})
    if orphans:
        print(f"  WARN: coverage rules name unit(s) absent from {os.path.basename(relative_path)}: "
              f"{', '.join(orphans)}")

    created, skipped, failures = 0, 0, 0
    for row in unit_rows:
        name = row["name"]
        base_location_id = location_ids.get(row["baseLocationCode"])
        if base_location_id is None:
            print(f"  WARN: mobile unit {name}: base location {row['baseLocationCode']} not found")
            failures += 1
            continue

        policy_name = (row.get("travelBufferPolicyName") or "").strip()
        policy_id = policy_ids.get(policy_name) if policy_name else None
        if policy_name and policy_id is None:
            print(f"  WARN: mobile unit {name}: unknown travel buffer policy {policy_name!r}")
            failures += 1
            continue

        # Absent from the coverage fixture is not the same as unresolvable in it: a parked unit
        # legitimately has no rules (MU-CLT-MAIN-03), and only ACTIVE requires them. The default
        # separates the two -- None is the poison coverage_rules_by_unit sets for a unit whose
        # rules name a service area that does not exist.
        rules = rules_by_unit.get(name, [])
        if rules is None:
            print(f"  WARN: mobile unit {name}: skipped, a coverage rule names an unknown service area")
            failures += 1
            continue

        current = existing.get(name)
        if current is not None:
            shortfall = mobile_unit_shortfall(gateway, current, row, rules)
            if shortfall is None:
                skipped += 1
                continue
            # Counted as a failure rather than a skip: this is the state an alpha seeded before
            # #1986 is in, and reporting it as "skipped" is how eligibility stays quietly empty.
            print(f"  WARN: mobile unit {name} already exists but {shortfall}. It predates #1986 and "
                  "cannot be completed through the API -- capabilityIds is not a PATCH key. Reset the "
                  "database and reseed, or delete this unit, to get an ACTIVE unit with coverage.")
            failures += 1
            continue

        body = {
            "name": name,
            "baseLocationId": base_location_id,
            "status": row["status"],
            "capabilityIds": [code for code in (row.get("capabilityCodes") or "").split(";") if code],
            "coverageRules": rules,
        }
        if policy_id:
            body["travelBufferPolicyId"] = policy_id

        status_code, _ = gateway.post_json("/location/mobile-units", body, allow_error=True)
        if 200 <= status_code < 300:
            created += 1
        else:
            print(f"  WARN: mobile unit {name}: HTTP {status_code}")
            failures += 1

    print(f"  mobile units: created={created} skipped={skipped} failures={failures}")
    return failures == 0


API_PACKS = {
    "@site-defaults": run_site_defaults,
    "@mobile-units": run_mobile_units,
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


def _without_row_coded(file_bytes, code):
    """The CSV minus the row whose `code` column equals `code`.

    --bootstrap-location creates one site up front, because a bulk job has to be scoped to a
    location that exists and a freshly reset database has none. The LOCATION pack then carried that
    same site again and the job reported one failure for a row the driver had itself just created:
    an "expected duplicate" the operator was told to ignore, sitting in the same column as failures
    that are not expected at all. Dropping the row the driver already loaded means a clean run
    reports a clean load.

    Parsed with the csv module rather than split(","): a site legitimately named
    "Service Center, West" carries a quoted comma, and splitting on commas would shift every field
    after it -- reading the wrong column as the code, matching nothing, and sending the bootstrapped
    row anyway. Kept rows are re-emitted by csv.writer, so quoting is normalised rather than
    preserved byte for byte; the upload is parsed as CSV, not compared.
    """
    rows = list(csv.reader(io.StringIO(file_bytes.decode("utf-8"))))
    if not rows:
        return file_bytes
    header = rows[0]
    try:
        code_column = header.index("code")
    except ValueError:
        return file_bytes

    kept = [header]
    for row in rows[1:]:
        if not any(field.strip() for field in row):
            continue
        if len(row) > code_column and row[code_column].strip() == code:
            continue
        kept.append(row)

    buffer = io.StringIO(newline="")
    csv.writer(buffer, lineterminator="\n").writerows(kept)
    return buffer.getvalue().encode("utf-8")


PackOutcome = collections.namedtuple("PackOutcome", "ok job_id")


def failures_are_all_replication_pending(gateway, job_id):
    """Whether every row this job refused was refused for replication lag, and there is one.

    The owning service classifies each row: REPLICATION_PENDING means it could not judge the row
    yet because state it consumes asynchronously had not arrived, which is the one failure worth
    sending again unchanged. Anything else -- a bad value, a duplicate, a server fault -- is an
    answer, and re-running would just produce it a second time.

    A job whose audit cannot be read is treated as not retryable: the driver must not loop on a
    pack it cannot classify.
    """
    try:
        _, records = gateway.get(f"/bulk-loader/bulk-jobs/{job_id}/audit")
    except Exception as exc:  # noqa: BLE001 - any read failure means "do not retry"
        print(f"  could not read audit for job {job_id} ({exc}); not retrying")
        return False

    failed_codes = []
    for record in records or []:
        if record.get("reviewStatus") == "APPROVED":
            continue
        raw_reason = record.get("reasonCodes")
        if not raw_reason:
            return False
        try:
            failed_codes.append(json.loads(raw_reason).get("errorCode"))
        except (TypeError, ValueError):
            return False

    return bool(failed_codes) and all(code == REPLICATION_PENDING_CODE for code in failed_codes)


def run_pack_file(gateway, relative_path, domain_type, location_id, poll_timeout_seconds, skip_code=None):
    csv_path = os.path.join(FIXTURE_ROOT, relative_path)
    file_name = os.path.basename(csv_path)
    with open(csv_path, "rb") as fh:
        file_bytes = fh.read()
    if skip_code:
        file_bytes = _without_row_coded(file_bytes, skip_code)
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
            return PackOutcome(False, job_id)
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
    return PackOutcome(ok, job_id)


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

    bootstrapped_code = None
    location_id = args.location_id or resolve_location_id(gateway, args.location_code)
    if location_id is None:
        if not args.bootstrap_location:
            raise SystemExit(
                f"ERROR: location {args.location_code} not found and --bootstrap-location not given; "
                "bulk-load jobs need an existing location")
        location_id = bootstrap_location(gateway, args.location_code)
        bootstrapped_code = args.location_code
        print(f"  note: {bootstrapped_code} is already loaded; the LOCATION pack will skip that row")

    all_ok = True
    for path, domain in selected:
        print(f"pack {path} -> {domain}")
        if domain in API_PACKS:
            all_ok = API_PACKS[domain](gateway, path, location_id) and all_ok
        else:
            # The driver created this site itself to scope the jobs; sending it again would be a
            # self-inflicted duplicate.
            skip_code = bootstrapped_code if domain == "LOCATION" else None
            outcome = run_pack_file(gateway, path, domain, location_id, args.poll_timeout, skip_code)
            attempt = 1
            while (
                not outcome.ok
                and attempt < MAX_REPLICATION_ATTEMPTS
                and failures_are_all_replication_pending(gateway, outcome.job_id)
            ):
                print(
                    f"  every failed row of {domain} reports {REPLICATION_PENDING_CODE}; the owning service "
                    f"has not caught up. Re-running (attempt {attempt + 1} of {MAX_REPLICATION_ATTEMPTS})"
                )
                time.sleep(REPLICATION_BACKOFF_SECONDS)
                outcome = run_pack_file(gateway, path, domain, location_id, args.poll_timeout, skip_code)
                attempt += 1
            all_ok = outcome.ok and all_ok

    print("done" if all_ok else "done with failures — inspect the review queue / job counters above")
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
