# Alpha seed fixture packs

Fixture packs for the API-driven alpha seed pipeline (`docs/DATA_SEED_STRATEGY.md` §3,
Tier 2). Each directory holds the demo data for one domain as CSV files shaped for that
domain's `pos-bulk-loader` job, replacing the corresponding Flyway
`R__seed_*operational*` migration. Loading through the bulk loader means every row goes
through the owning service's application layer: `@EmitEvent` audit events fire, outbox
facts publish on `{domain}.events.v1`, and consumer `ext_*` replicas hydrate exactly as
they would for production traffic — none of which happens for direct SQL seeds.

## Conventions

- One directory per owning domain (`customer/`, `location/`, …), files named for what
  they load.
- CSV headers match the bulk loader's reader column order for that domain
  (`BatchConfiguration` `*CsvReader` `.names(...)`); the loader skips the header row.
- Fields the target endpoint cannot express are dropped at conversion time and the drop
  is recorded under **Known deltas** below — a fixture pack must never silently claim
  more fidelity than the API path provides.
- Fixtures are alpha demo data only. Nothing here runs at service startup or in any
  migration; the pipeline is an operator action against the alpha stack, so later
  environments need no gating.

## Running the packs (alpha only)

The driver runs every pack in dependency order through the gateway:

```bash
scripts/seed-alpha.py --gateway https://<alpha-gateway> --token "$SEED_BEARER_TOKEN"
# subset / rehearsal:
scripts/seed-alpha.py --gateway ... --only customer/person-customers.csv
scripts/seed-alpha.py --gateway ... --dry-run
# empty alpha (no locations yet):
scripts/seed-alpha.py --gateway ... --bootstrap-location
```

Per pack file it creates a bulk-load job (`POST /bulk-loader/bulk-jobs`), uploads the
CSV, starts processing, and polls the job to a terminal state, reporting the row
counters. The token needs `bulkImport:upload:execute` plus the relayed per-domain
create permissions (`location:write`, `crm:party:create`). Bulk-load jobs require a
`locationId`: the driver resolves `--location-code` (default `CLT-MAIN-001`) against
the location roster, and `--bootstrap-location` creates it from `locations.csv` via
the gateway API when the roster is empty (that row then reports one expected
duplicate failure in the LOCATION job).

Manual per-domain flow (what the driver automates): upload the CSV to
`pos-bulk-loader`, create a job with the matching `domainType` and alpha
`locationId`, launch it, and watch the review queue for per-row failures.

Afterwards, verify: row counts on the owner, `replica_drift_total` flat, expected
event volume in pos-event-receiver.

Run order (services must exist before data referencing them): security users/roles →
**location** → people → people-contact → **customer** → vehicle → catalog → price →
inventory. Locations must be loaded (or already present) first in any case — bulk-load
jobs themselves require a valid `locationId`.

## Packs

### `customer/` — from `pos-customer R__seed_customer_operational_data.sql`

| File | Rows | Target |
|---|---|---|
| `person-customers.csv` | 50 individual customers | `POST /v1/customer/bulk-ingest` (`domainType: CUSTOMER`) |
| `commercial-customers.csv` | 20 commercial accounts, each with a primary contact | `POST /v1/customer/commercial/bulk-ingest` (`domainType: COMMERCIAL_CUSTOMER`) |

Columns: `firstName,lastName,email,phoneNumber,primaryAddress,customerNumber`. Names
and primary email addresses are joined from the `01960024-*` identity rows in
`pos-people-contact R__seed_people_contact_operational_data.sql`; the ingest path
creates the canonical person (pos-people-contact) itself, so the fixture carries no
person UUIDs — identity flows through the API, which is the point.

**Known deltas** (not expressible through `/v1/customer/bulk-ingest` today):

- `status` (45 ACTIVE / 4 INACTIVE / 1 ON_HOLD) and `tier` (35 STANDARD / 10 BRONZE /
  3 SILVER / 2 GOLD) — all rows land ACTIVE/STANDARD.
- `preferredContactMethod` is derived (EMAIL when an email is present, else
  PHONE_CALL); the seed's PHONE_CALL/SMS/NONE spread (10/7/6) is not preserved because
  every row has an email.
- `commercial-customers.csv` carries the 20 commercial accounts with legal/display
  names, tax ids, billing terms, and each account's primary contact (name + email,
  joined from the `01960025-*` identity rows); the ingest path creates the contact
  person and attaches it with the PRIMARY_CONTACT role. Deltas: `status`
  (18 ACTIVE / 1 INACTIVE / 1 ON_HOLD) and `tier` (8/5/4/2/1) land as
  ACTIVE/STANDARD; **all 20 street addresses are dropped** (`createCommercialAccount`
  has no address input — structured org addresses are a pos-people-contact-fed
  replica); customer numbers are service-generated, so the seed's `CUST-CP-*` values
  are not preserved. The 20 billing-contact persons (`01960026-*`) have had no active
  seed rows in pos-customer since V6 dropped the contact table, so nothing was
  converted for them; modeling them as BILLING relationships is open follow-up work.

Both halves of the customer seed are now covered. The Flyway seed file (and its
`scripts/flyway-seed-baseline.txt` line) stays until alpha has been reseeded through
the pipeline and verified (`docs/DATA_SEED_STRATEGY.md` §5.4); delete both in that
change.

### `vehicle/` — from `pos-vehicle-inventory R__seed_vehicle_inventory_operational_data.sql`

| File | Rows | Target |
|---|---|---|
| `vehicles.csv` | 329 vehicles (260 fleet, 69 individual-owned) | `POST /v1/vehicles/bulk-ingest` (`domainType: VEHICLE`) |

The seed was generated from the customer seed and keys every vehicle to a fixed
customer UUID; those ids are regenerated by the pipeline, so the fixture instead
carries stable owner keys — `ownerType` (`INDIVIDUAL`/`ORGANIZATION`) + `ownerName`
(person "First Last" / commercial display name, all 70 unique). The driver resolves
them against the live party directory (`browseParties`, exact name match) and
rewrites the file into the loader's `accountId,vin,…` shape before upload — which is
why the customer packs must run first. Unresolved or ambiguous owners are warned and
land ownerless. Deltas: `trim`, `licensePlate`, and jurisdiction are empty (the seed
never had them); the fixed `vehicle_id`s are regenerated.

The seed file stays until the alpha reseed is verified (§5.4).

### `location/` — from `pos-location R__seed_location_2_operational_data.sql`

| File | Rows | Target |
|---|---|---|
| `locations.csv` | 5 sites (3 service centers, mobile hub, corporate HQ) | `POST /v1/locations/bulk-ingest` (`domainType: LOCATION`) |

Columns: `name,code,addressLine1,addressLine2,city,stateOrProvince,postalCode,countryCode,phoneNumber,active,locationTypeName,timezone`.
Location types resolve by name (created on the fly if missing, though the reference
seed provides them); timezones are validated by the service (invalid → per-row
failure). Note the run-order chicken-and-egg: bulk-load jobs require a `locationId`,
so the very first location load in an empty alpha needs one location created via the
gateway API first (or use that location's id once the reference/security bootstrap
provides one).

**Known deltas / not yet converted:**

- Only the `location` table rows are covered. The seed's storage locations (14, with
  staging/quarantine hierarchy and location back-references), ~21 bays, 3 mobile
  units (+capabilities, coverage rules), and the 4 `location_parent` hierarchy edges
  have no bulk-ingest path — they are managed through pos-location's own APIs (bays,
  mobile units, storage locations, `POST /v1/locations/{id}/parents`) and need a
  scripted gateway pass or their own ingest wave.
- The seed file stays until that remainder is converted and alpha is reseeded and
  verified (§5.4).
