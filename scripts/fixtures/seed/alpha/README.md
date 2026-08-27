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

### `people/` — from `pos-people R__seed_people_operational_data.sql`

| File | Rows | Target |
|---|---|---|
| `employees.csv` | 39 employees (all staff — the seed has been employees-only since #875) | `POST /v1/people/bulk-ingest` (`domainType: PERSON`) |
| `staffing-assignments.csv` | 39 role/location assignments | gateway API pack (`POST /people/staffing/assignments` per row) |

The people seed contains no customers: customer/contact identities moved to the
pos-people-contact seed under #875, and this file holds the 39 staff (`EMP-0001`–
`EMP-0039`), their location assignments, and dev-only `ext_*` replica bootstraps.
Employees load through `createEmployee` (status forced ACTIVE, **STRICT duplicate
policy** on employee number/email/phone — this pack genuinely converges on re-run),
publishing the identity upsert command and `people.employee.updated` fact per row.
Assignments have no bulk endpoint, so the driver replays them as an API pack:
`employeeNumber → personId` via `getEmployeeByNumber`, `locationCode → id` via the
roster, then `createStaffingAssignment` — employees and locations must load first.

**Deltas / not converted:**

- `hireDate` is fixed at `2024-01-15` (the seed used `CURRENT_DATE`).
- The seed's `ext_people_contact_person` / `ext_people_contact_user_link` replica
  bootstraps are deliberately **not** converted — replicas hydrate from the identity
  events the ingest path publishes, which is the point of the pipeline. Usernames
  (user links) are pos-security-service data and out of scope here.
- The seed file stays until the alpha reseed is verified (§5.4).

### `shop-manager/` — from `pos-shop-manager R__seed_shop_manager_mechanics.sql`

| File | Rows | Target |
|---|---|---|
| `mechanic-skills.csv` | 23 skills across 7 technicians | gateway API pack (`PUT /shop-manager/mechanics/by-person/{personId}/skills` per mechanic) |

Skills are the seed's one piece of genuine shop-manager data (proficiency/ASE codes
exist nowhere else); the mechanic *rows* themselves are projected from TECHNICIAN
staffing assignments over Kafka and are not seeded. The endpoint routes the edit
through the same HR-feed path as the projection (a synthetic
MECHANIC_SKILLS_UPDATED event), so dedupe/stale-guard/audit apply uniformly and
each PUT replace-sets the mechanic's skills — re-runs converge. **Ordering:** the
pack runs after the staffing assignments, but mechanics materialize
asynchronously from Kafka; a 404 means the projection hasn't caught up — re-run
this pack alone (`--only shop-manager/mechanic-skills.csv`) once it has. Delta:
the seed's `certified_date` is not carried (the skill payload has no such field).

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

### `security/` — from `pos-security-service R__seed_security_operational_data.sql`

| File | Rows | Target |
|---|---|---|
| `users.csv` | 25 users, 16 roles | gateway API pack (`POST /security-service/users` per row) |

**No password material is committed.** The driver generates a random password per
user at run time and appends the credentials it created to a local, gitignored
file (`alpha-seed-credentials.csv`, mode 600 — `--credentials-out` to relocate);
`--passwords-file` supplies a local `username,password` CSV override for teams
that want stable demo logins. Passwords travel plaintext over TLS and are
bcrypt-hashed server-side; an existing username (409) counts as already
provisioned. This retires the seed's shared committed bcrypt hash.

| `user-person-links.csv` | 16 user→person links (employee accounts) | gateway API pack (`PUT /security-service/users/{id}/person-link` per row) |

The links pack runs after the employees pack: usernames resolve to user ids via
the user directory, employee numbers to person ids via `getEmployeeByNumber`, and
each `PUT` queues the people-contact link command — `users.person_id` is a
projection that lands asynchronously when the link fact returns (verify with a
later `GET /security-service/users`). Re-runs re-queue the same link, which the
consumer upserts by username. The two customer personas (`walter.simmons`,
`lena.fischer`) have no employee identity and are not linked (same as the seed).

**Deltas:** the seed's single scoped `role_assignments` row (INVENTORY_CONTROLLER
for raymond.chu) is deliberately not replayed — the same role is already attached
directly in `users.csv` and effective roles are the union of both, so the scoped
row added no authority; the scoped-assignment mechanism itself remains
exercisable via `PUT /v1/users/{userId}/roles/{roleId}`. The seed file stays
until the alpha reseed is verified (§5.4).

### `catalog/` — from `pos-catalog R__seed_reference_catalog_2_products.sql`

| File | Rows | Target |
|---|---|---|
| `products.csv` | 500 products (12 categories) | `POST /v1/catalog/bulk-ingest` (`domainType: CATALOG_PRODUCT`) |

Headers use the ingest record's field names directly, so the catalog job's flexible
reader maps them 1:1. Each successful row publishes a product fact on
`catalog.events.v1`, hydrating the ext_catalog replicas (marketing, warranty,
inventory, supplier) live — the post-seed `facts/replay` step the runbook requires
for Flyway-seeded catalogs is not needed for pipeline-loaded products.

**Deltas / not yet converted:**

- The CSV carries `manufacturerName`, `manufacturerBrand`, `countryOfOrigin`, and
  `type` (Wave 2) — the manufacturer fields also travel on the product fact, so
  warranty/supplier replicas get them. Still not expressible: `manufacturerId`
  (there is no manufacturer table; the seed's ids were synthetic and are dropped),
  and `categoryName`/`subcategoryName` remain carried-but-ignored until
  category-by-name resolution lands (products land uncategorized).
- `upc` and `description` are blank (the seed never had UPCs; description defaults
  to the name server-side); `price` is blank (pricing is a separate seed).
- Categories/subcategories (`R__seed_reference_catalog.sql`), services (file 3 — no
  ingest path; `facts/replay` exists), pricing (file 4: `item_cost`,
  `product_msrp`), and `product_uom` (file 5) are **not converted** and their seed
  files stay. The products file itself stays until the alpha reseed is verified
  (§5.4).

### `location/` — from `pos-location R__seed_location_2_operational_data.sql`

| File | Rows | Target |
|---|---|---|
| `locations.csv` | 5 sites (3 service centers, mobile hub, corporate HQ) | `POST /v1/locations/bulk-ingest` (`domainType: LOCATION`) |
| `storage-locations.csv` | 185 (37 per site: 3 floors, 2 cages, 7 shelves, 1 truck, 24 bins under the parts shelves) | gateway API pack (`POST .../storage-locations` per row, parents resolved in order) |
| `bays.csv` | 21 service bays (6 types, from the seed) | gateway API pack (`POST .../bays` per row; 409 = exists) |
| `mobile-units.csv` | 9 mobile units | gateway API pack (`POST /location/mobile-units`; existing names skipped via the list) |

Columns (`locations.csv`): `name,code,addressLine1,addressLine2,city,stateOrProvince,postalCode,countryCode,phoneNumber,active,locationTypeName,timezone`.
Location types resolve by name (created on the fly if missing, though the reference
seed provides them); timezones are validated by the service (invalid → per-row
failure). Note the run-order chicken-and-egg: bulk-load jobs require a `locationId`,
so the very first location load in an empty alpha needs one location created via the
gateway API first (or use that location's id once the reference/security bootstrap
provides one).

Columns (`storage-locations.csv`): `locationCode,name,type,parentName,storageCategoryCode,hazardContainment`.
`type` is the physical topology (FLOOR/SHELF/BIN/CAGE/TRUCK) and is unchanged;
`storageCategoryCode` is the putaway capability added in #1514 — what the location is
fit to *hold* — so a rule can route tires to a tire rack and oil to oil storage.
Both tire racks and bulk pallet areas are FLOOR-or-SHELF topologically, which is why
the capability cannot be derived from `type`. Values: `TIRE_RACK`, `OIL_STORAGE`,
`BATTERY_RACK`, `SMALL_PARTS_BIN`, `BULK_FLOOR`, `STAGING`, `QUARANTINE`, `GENERAL`.
The mapping this fixture uses:

| Row | `type` | `storageCategoryCode` | `hazardContainment` |
|---|---|---|---|
| Receiving Dock, Staging Floor | FLOOR | `STAGING` | false |
| Quarantine Cage, Core Returns Cage | CAGE | `QUARANTINE` | false |
| Tire Rack A/B | SHELF | `TIRE_RACK` | false |
| Fluids Shelf | SHELF | `OIL_STORAGE` | true |
| Battery Rack | SHELF | `BATTERY_RACK` | true |
| Bulk Floor | FLOOR | `BULK_FLOOR` | false |
| Van Stock 01 | TRUCK | `GENERAL` | false |
| Parts Shelf A/B/C | SHELF | `GENERAL` | false |
| Bin A-01 … C-08 | BIN | `SMALL_PARTS_BIN` | false |

`STAGING` and `QUARANTINE` accept nothing by rule: they are putaway sources, not
destinations. `GENERAL` is the permissive default and accepts every catalog
category, so it is what the parts shelves and the van fall back to. Both columns
are only sent when populated — an empty `storageCategoryCode` leaves the capability
undeclared, which the service reports back as `GENERAL`.

**Known deltas / not yet converted:**

- The storage mix is deliberately uniform across all 5 sites (a richer, realistic
  garage topology replacing the seed's thinner ad-hoc spread); the seed's
  staging/quarantine **back-references on the location row**
  (`default_staging_location_id`/`default_quarantine_location_id`) are not set —
  no API writes them today. #1514 kept that uniformity: the Flyway seed adds its
  oil storage and battery racks to the 3 service centers only, whereas this
  fixture gives all 5 sites the full set.
- **No INACTIVE storage location** can be seeded through this pack:
  `POST .../storage-locations` always creates in ACTIVE status, so the Flyway
  seed's per-site "Retired Bin" rows (which exist to make "putaway must refuse a
  decommissioned destination" testable) have no fixture equivalent. Deactivating
  one needs a follow-up `PATCH` this pack does not issue.
- **`allowNewProduct` is not a fixture column**, so every row lands on the
  service default `MIXED`. Nothing in the alpha topology needs
  `SAME_PRODUCT_ONLY` or `EMPTY_ONLY` yet; add the column when something does.
- **Capacity descriptors are not seeded here.** The Flyway seed sets
  `maxUnitCount`/`unitCount` on three CLT-MAIN bins (roomy, near-limit, full) to
  make the capacity paths reachable; this pack leaves capacity unset, which means
  uncapped.
- Mobile-unit **capabilities and coverage rules** are intentionally dropped (bays
  and mobile units suffice for alpha), as are the mobile units'
  `travel_buffer_policy_id` references and the 4 `location_parent` hierarchy
  edges (`POST /v1/locations/{id}/parents` exists if wanted later).
- The seed file stays until alpha is reseeded and verified (§5.4).
