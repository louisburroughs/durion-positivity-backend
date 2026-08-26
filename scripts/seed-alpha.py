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
permissions relayed to downstream services (location:write, crm:party:create).
"""

import argparse
import csv
import io
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

FIXTURE_ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures", "seed", "alpha")

# Dependency-ordered pack files (README run order): locations before anything
# that references them; individual customers before commercial accounts only by
# convention (no hard dependency).
PACK_FILES = [
    ("location/locations.csv", "LOCATION"),
    ("customer/person-customers.csv", "CUSTOMER"),
    ("customer/commercial-customers.csv", "COMMERCIAL_CUSTOMER"),
]

POLL_INTERVAL_SECONDS = 5
TERMINAL_STATUSES = {"COMPLETED", "FAILED", "CANCELLED"}


class Gateway:
    def __init__(self, base_url, token, api_version="1"):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.api_version = api_version

    def _request(self, method, path, body=None, content_type=None):
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
            detail = error.read().decode("utf-8", errors="replace")
            raise SystemExit(f"ERROR: {method} {url} -> HTTP {error.code}: {detail[:500]}") from error

    def get(self, path):
        return self._request("GET", path)

    def post_json(self, path, body):
        return self._request("POST", path, body=body)

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
        print(f"  review failures: GET {gateway.base_url}/bulk-loader/bulk-jobs/{job_id} (review queue)")
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
        all_ok = run_pack_file(gateway, path, domain, location_id, args.poll_timeout) and all_ok

    print("done" if all_ok else "done with failures — inspect the review queue / job counters above")
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
