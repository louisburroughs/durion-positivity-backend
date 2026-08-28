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
inventory (putaway rules, then on-hand, then cycle count plans). Locations must be
loaded (or already present)
first in any case — bulk-load jobs themselves require a valid `locationId`.

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
- The Flyway seed (`R__seed_people_operational_data.sql`) was deleted in #1554
  along with the location operational seed it referenced by fixed location UUID.

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
| `products.csv` | 501 products (12 categories; 500 from the seed plus WIXF-51394, added in #1554 for the on-hand pack) | `POST /v1/catalog/bulk-ingest` (`domainType: CATALOG_PRODUCT`) |

Headers use the ingest record's field names directly, so the catalog job's flexible
reader maps them 1:1. Each successful row publishes a product fact on
`catalog.events.v1`, hydrating the ext_catalog replicas (marketing, warranty,
inventory, supplier) live — the post-seed `facts/replay` step the runbook requires
for Flyway-seeded catalogs is not needed for pipeline-loaded products.

**Category resolution (issue #1514).** `categoryName` and `subcategoryName` are
resolved to ids against the Flyway reference seed
(`pos-catalog/.../R__seed_reference_catalog.sql` — 12 categories, 40 subcategories),
so pipeline-loaded products now land **categorized** and the resolved category and
subcategory travel on the product fact. This is what makes category-based putaway
work on fixture data; previously both columns were carried but ignored and every
product landed uncategorized.

- **Matching:** the name is trimmed and matched case-insensitively. Exact casing is
  not required.
- **Unknown name:** the row **fails** with `CATALOG_INGEST_FAILED` and the rest of
  the batch proceeds. It is *not* created uncategorized, and no category is invented
  — categories are curated reference data, and landing uncategorized would look like
  success while silently producing a product that no category-based putaway rule can
  match. Check `failureCount` and the per-row `results`, not just the HTTP status.
- **Omitted name:** blank or absent is "unclassified" and resolves to null without
  error, so a row may legitimately carry neither.
- All 501 rows in `products.csv` resolve today (verified by
  `AlphaFixtureCategoryNamesResolveTest`, which parses this CSV and the seed SQL at
  build time). Any future edit introducing an unseeded name fails that test rather
  than surfacing as a per-row ingest failure during a reseed.

**Deltas / not yet converted:**

- The CSV carries `manufacturerName`, `manufacturerBrand`, `countryOfOrigin`, and
  `type` (Wave 2) — the manufacturer fields also travel on the product fact, so
  warranty/supplier replicas get them. Still not expressible: `manufacturerId`
  (there is no manufacturer table; the seed's ids were synthetic and are dropped).
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
| `storage-locations.csv` | 190 (38 per site: 3 floors, 2 cages, 7 shelves, 1 truck, 24 bins under the parts shelves, 1 retired bin) | gateway API pack (`POST .../storage-locations` per row, parents resolved in order; `status`/capacity applied by follow-up `PATCH`) |
| `bays.csv` | 21 service bays (6 types, from the seed) | gateway API pack (`POST .../bays` per row; 409 = exists) |
| `mobile-units.csv` | 9 mobile units | gateway API pack (`POST /location/mobile-units`; existing names skipped via the list) |

Columns (`locations.csv`): `name,code,addressLine1,addressLine2,city,stateOrProvince,postalCode,countryCode,phoneNumber,active,locationTypeName,timezone`.
Location types resolve by name (created on the fly if missing, though the reference
seed provides them); timezones are validated by the service (invalid → per-row
failure). Note the run-order chicken-and-egg: bulk-load jobs require a `locationId`,
so the very first location load in an empty alpha needs one location created via the
gateway API first (or use that location's id once the reference/security bootstrap
provides one).

Columns (`storage-locations.csv`): `locationCode,name,type,parentName,storageCategoryCode,hazardContainment,status,maxUnitCount`.
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

`status` and `maxUnitCount` (issue #1554) cannot travel on the create:
`POST .../storage-locations` always creates ACTIVE and uncapped. Rows carrying them
get a follow-up `PATCH` through the same owning-service endpoint an operator would
use (each PATCH republishes the storage-location fact). The capacity map carries
**only the cap** (`maxUnitCount`): a fill is never declared here — fills are real
on-hand stock, created by `inventory/on-hand.csv` through the adjustment flow. The
per-site `Retired Bin` rows (INACTIVE, `GENERAL`) keep "putaway must refuse a
decommissioned destination" testable, and three CLT-MAIN bins carry the capacity
cases that make the capacity paths reachable, with caps set against the totals the
on-hand pack seeds into them: Bin A-01 roomy (363 units against a 500 cap),
Bin A-02 one short of full (416/417), Bin A-03 exactly full (190/190) — the same
trio of cases the retired Flyway seed carried. Editing the on-hand pack's CLT-MAIN
bin rows means recomputing these three caps.
Already-existing rows are never patched, so re-runs converge without overwriting
operator edits; applying a changed status/capacity to a live row is an operator
`PATCH`, not a reseed.

**Known deltas / not yet converted:**

- The storage mix is deliberately uniform across all 5 sites (a richer, realistic
  garage topology replacing the seed's thinner ad-hoc spread); the seed's
  staging/quarantine **back-references on the location row**
  (`default_staging_location_id`/`default_quarantine_location_id`) are not set —
  no API writes them today. #1514 kept that uniformity: the Flyway seed adds its
  oil storage and battery racks to the 3 service centers only, whereas this
  fixture gives all 5 sites the full set.
- **`allowNewProduct` is not a fixture column**, so every row lands on the
  service default `MIXED`. Nothing in the alpha topology needs
  `SAME_PRODUCT_ONLY` or `EMPTY_ONLY` yet; add the column when something does.
- Mobile-unit **capabilities and coverage rules** are intentionally dropped (bays
  and mobile units suffice for alpha), as are the mobile units'
  `travel_buffer_policy_id` references and the 4 `location_parent` hierarchy
  edges (`POST /v1/locations/{id}/parents` exists if wanted later).
- The Flyway seed (`R__seed_location_2_operational_data.sql`) was deleted in
  #1554 — this pack is the only source of the location topology. The INACTIVE
  "Retired Bin" rows and the capacity descriptors it used to carry moved into
  this pack (see the `status`/capacity note above); the location-row
  staging/quarantine back-references it also carried remain unexpressed because
  no API writes them.

### `inventory/` — putaway rules new in #1514; on-hand from `pos-inventory R__seed_reference_inventory.sql` (#1554)

| File | Rows | Target |
|---|---|---|
| `putaway-rules.csv` | 16 rules (12 category rules, 3 subcategory overrides, 1 terminal `ANY`) | gateway API pack (`POST /inventory/inventory/putaway/rules` per row) |
| `on-hand.csv` | 494 initial-stock rows across the three service centers (263 at CLT-MAIN-001, 111 at CLT-SOUTH-001, 120 at CLT-NORTH-001) | gateway API pack (`POST /v1/inventory/bulk-ingest` per site, then `POST .../adjustments/{id}/approve` per row) |
| `cycle-count-plans.csv` | 1 demo cycle count plan | gateway API pack (`POST /inventory/inventory/cycleCountPlans` per row) |

Columns: `priority,matchType,matchName,locationCode,destinationName,destinationStrategy,isEnabled`.

Putaway rules decide which bin a received line is suggested for. They are Tier 2
(`docs/DATA_SEED_STRATEGY.md` §2), not Flyway: they name per-environment storage
location ids and they have an `@EmitEvent` audited lifecycle now that the CRUD
endpoint exists — so a SQL seed would both hardcode ids and skip the audit event.
The *compatibility matrix* they must agree with is the opposite case (service-private,
environment-invariant, no lifecycle) and stays in Flyway,
`pos-inventory V43__storage_compatibility.sql`.

Runs **last**, after `location/` and `catalog/`: every reference is resolved at load
time against data those packs create.

**Nothing in this file is a uuid.** Each row carries business keys and the driver
resolves them:

- `matchName` is a catalog **category or subcategory name** (`matchType` says which),
  matched case-insensitively. pos-catalog exposes no endpoint that lists categories,
  so the driver resolves a name through an **exemplar product**: it picks the first SKU
  in `catalog/products.csv` carrying that name, looks it up
  (`GET /catalog/products/search?sku=…`), reads the product's resolved
  `category`/`subcategory` id back (`GET /catalog/products/{id}`), and checks the name
  it got matches the one asked for. An unresolvable name fails that row with a `WARN`
  naming the cause — it is never defaulted, because a rule that silently lost its match
  value is authored, accepted and never fires.
- `locationCode` + `destinationName` are the destination's business key, resolved
  against the site's storage-location list exactly as `storage-locations.csv` resolves
  `parentName`.
- `ANY` rows carry an empty `matchName`; the endpoint requires `matchValue` to be
  absent for that tier.

Resolution order is `SKU > SUBCATEGORY > CATEGORY > ANY`, then ascending `priority`
inside a tier, so the file is written in that order and priorities are distinct within
each tier (ties are broken arbitrarily by the matcher).

Every destination is one the compatibility matrix accepts for that rule's class — a
rule pointing at a bin the matrix refuses loads cleanly and then fails once per
received line with `LOCATION_NOT_VALID_FOR_SKU`:

| Rule | Destination | `storageCategoryCode` | Strategy |
|---|---|---|---|
| `SUBCATEGORY` Batteries | Battery Rack | `BATTERY_RACK` (containment) | FIXED |
| `SUBCATEGORY` ATF & Gear Oil | Fluids Shelf | `OIL_STORAGE` (containment) | FIXED |
| `SUBCATEGORY` Hydraulic Cylinders & Hoses | Bulk Floor | `BULK_FLOOR` | FIXED |
| `CATEGORY` Tires & Wheels | Tire Rack A | `TIRE_RACK` | FIXED |
| `CATEGORY` Engine Parts | Bin A-01 | `SMALL_PARTS_BIN` | CLOSEST_AVAILABLE |
| `CATEGORY` Brake System | Bin A-05 | `SMALL_PARTS_BIN` | CLOSEST_AVAILABLE |
| `CATEGORY` Electrical System | Bin B-01 | `SMALL_PARTS_BIN` | CLOSEST_AVAILABLE |
| `CATEGORY` Drivetrain & Transmission | Bin B-05 | `SMALL_PARTS_BIN` | LAST_USED |
| `CATEGORY` Suspension & Steering | Bulk Floor | `BULK_FLOOR` | FIXED |
| `CATEGORY` Fluids & Chemicals | Fluids Shelf | `OIL_STORAGE` (containment) | FIXED |
| `CATEGORY` Filters | Bin C-01 | `SMALL_PARTS_BIN` | CLOSEST_AVAILABLE |
| `CATEGORY` Exhaust System | Bulk Floor | `BULK_FLOOR` | FIXED |
| `CATEGORY` HVAC & Climate | Bin C-05 | `SMALL_PARTS_BIN` | LAST_USED |
| `CATEGORY` Body & Lighting | Parts Shelf C | `GENERAL` | FIXED |
| `CATEGORY` Heavy Equipment & Hydraulics | Bulk Floor | `BULK_FLOOR` | FIXED |
| `ANY` (terminal) | Parts Shelf B | `GENERAL` | FIXED |

Three things about that table are load-bearing rather than cosmetic:

- **The three subcategory rules are exactly the three subcategories the matrix
  overrides.** A subcategory with a matrix override but no rule routes by its *parent
  category's* rule to a destination the override refuses — `Batteries` would go to a
  small-parts bin, and the matrix would then refuse acid without a bund. This is the
  reason `SUBCATEGORY` outranks `CATEGORY` at all. Adding a matrix override means
  adding a rule here.
- **The `ANY` destination is `GENERAL`.** An item with no catalog classification is
  accepted only by `GENERAL` storage, so any other destination would make the terminal
  fallback refuse the very brand-new SKU it exists to catch.
- **`CLOSEST_AVAILABLE` is only used on bins that sit under a parts shelf.** The
  strategy ranks every ACTIVE location at the site by topology hops from its anchor and
  takes the first with capacity; it does not consult the matrix. An anchor with no
  parent and no children has nothing to rank, and its overflow degrades to the
  lowest-id location at the site — so standalone racks and floors use FIXED. Where it
  is used, the neighbours it would overflow to (the parent shelf at one hop, sibling
  bins at two) are legal for the same class.

`AlphaFixturePutawayRulesTest` (pos-inventory) parses this CSV, the catalog taxonomy
seed, `location/storage-locations.csv` and `V43__storage_compatibility.sql` at build
time and asserts all of the above, so a contradictory rule fails the build instead of a
reseed.

The pack's token needs `location:read` (the location roster and each site's
storage-location list), `catalog:product:view` (exemplar resolution), and
`inventory:putaway_rule:view` plus `inventory:putaway_rule:manage`. Running
`--only inventory/putaway-rules.csv` with the rule scopes alone gets a 403 on the
storage-location list; the driver reports that HTTP status rather than blaming the
fixture for an unresolved destination.

**Known deltas:**

- **All 16 rules target `CLT-MAIN-001`.** A putaway rule has no site scope — the
  matched rule's `destinationLocationId` is a single bin — so one enabled rule per
  (tier, class) is all the model allows. Rules for a second site would be
  lower-priority dead configuration. `locationCode` is a column anyway so the pack
  says which site it means and can be retargeted by editing one column.
- **Re-runs converge but never update.** The driver lists the configured rules first
  and skips a row whose `(matchType, matchValue)` already exists (and treats the
  endpoint's 409 — a second enabled `ANY` rule — the same way), so a re-run creates
  nothing. It deliberately does **not** `PUT` the existing rule back to the fixture's
  values: an operator who retuned a priority or disabled a rule on alpha keeps that.
  Applying a changed fixture row means deleting the rule (`DELETE
  /inventory/inventory/putaway/rules/{ruleId}`) and re-running. A **disabled** existing
  rule blocks its fixture row the same way — which leaves that class with no reachable
  rule — so the driver emits a `WARN` naming the rule id rather than reporting a clean
  converged run.
- **The `SKU` tier is not exercised.** `matchType: SKU` works and the driver would
  resolve it, but per-SKU slotting is an operator decision about one part, not demo
  topology; tier precedence itself is covered by `PutawayRuleMatcherTest`. Add rows
  with `matchType: SKU` and a SKU in `matchName` if alpha ever needs one — the
  exemplar-product resolution would have to be extended to return the product id
  directly.
- **`isEnabled` is `true` on every row.** The column exists because the endpoint has
  the field and a disabled rule is a legitimate fixture state, but nothing in the alpha
  topology wants one: a disabled rule is unreachable configuration.
- **Priorities are spaced by 10** (and `ANY` sits at 1000) so a rule can be inserted
  between two others without renumbering the file.
- The fixture assumes the storage capabilities in `location/storage-locations.csv`. If
  that file's `storageCategoryCode` mapping changes, these rules must be re-checked
  against the matrix — the build-time test does exactly that.

#### `on-hand.csv` — initial stock (issue #1554)

Columns: `sku,locationCode,storageLocationName,quantity,unitOfMeasure,description`
(`description` is documentation only; the driver does not send it).

Replaces the `inventory_ledger_entry` GOODS_RECEIPT rows the deleted
`R__seed_reference_inventory.sql` sections carried, which were keyed to the deleted
location seed's fixed bin UUIDs. Both of the seed's stock blocks are converted: the
58 curated CLT-MAIN rows (fluids on the Fluids Shelf, filters and plugs spread over
the small-parts bins, batteries on the Battery Rack, rotors on the Bulk Floor, tires
on the tire racks) plus the ~450-row `inv_seed2:` bin-level block across all three
service centers, its Flyway-only storage names mapped deterministically onto this
pack's topology (shelves D/E fold into A/B, bin indexes wrap into 1–8, the named
oil/battery/tire/bulk areas map to their fixture equivalents, and the secured cage
and plain parts shelves land on Parts Shelf C/A). After mapping, duplicate
(sku, site, storage location) keys are summed into one row each, and each SKU
carries one consistent unit of measure file-wide.

Stock enters through the production adjustment flow: the driver files one
`POST /v1/inventory/bulk-ingest` batch per site (each accepted row becomes a PENDING
adjustment request) and then approves each request, which posts the `ADJUSTMENT_IN`
ledger entry — that posting is what creates on-hand and emits the facts the `ext_*`
replicas consume. Runs after `location/` and `catalog/`: destination ids are
resolved against pos-location's live storage-location list, and the SKUs must exist
from the catalog pack. (The adjustment path itself performs no storage-location
validation; the `ext_storage_location` replica is hydrated by the location pack's
facts independently of this ordering.)

Adjustments are deltas, so a naive re-run would double stock; the driver checks
`GET /inventory/inventory/availability/by-sku?productSku=…&storageLocationId=…` first and
skips any SKU already stocked at its destination, making re-runs converge. One edge: if a row's
approve call fails after ingest, its PENDING adjustment is left behind while on-hand stays 0, so a
re-run files a second adjustment for that key — cancel or approve the orphan first (approving it
*after* a successful re-run would double that row's stock).

**Known deltas:**

- Entries post as `ADJUSTMENT_IN` (reason `CYCLE_COUNT_ADJUSTMENT`), not the seed's
  `GOODS_RECEIPT` — the receiving flow is exercised by real receipts, not the seed.
- **Unit cost is not expressible** through the ingest record, so the seed's valuation
  baseline is not carried; receive real stock for costing demos.
- The token additionally needs `inventory:adjustment:create`,
  `inventory:adjustment:approve` and `inventory:availability:read`.

#### `cycle-count-plans.csv` — demo cycle count plan (issue #1554)

Columns: `planName,locationCode,zoneNames,scheduledDaysOut` (`zoneNames` is
pipe-separated).

Replaces the `cycle_count_plan`/`cycle_count_plan_zone` block the Flyway seed
briefly carried on `main`, which referenced invented storage-location UUIDs — the
exact pattern #1554 retires. The one fixture row schedules a PLANNED count of
CLT-MAIN-001's Parts Shelf A bins (`Bin A-01`–`Bin A-08`).

The driver (`run_cycle_count_plans`) resolves `locationCode` against the roster and
each zone name against that site's live storage-location list, computes
`scheduledDate` as today + `scheduledDaysOut` (the endpoint requires a strictly
future date, so a fixed date would rot), and posts
`POST /inventory/inventory/cycleCountPlans`. Runs **last**, after the location pack
(zones must exist). Convergence: the site's existing plans are listed first and a
row whose `planName` already appears is skipped, so re-runs create nothing.

Only the plan is seeded: task generation
(`POST .../cycleCountPlans/{planId}/tasks`) is the demo action itself and is
deliberately left to the demo.

The token needs `inventory:cycle_count:view` (list) and
`inventory:cycle_count:initiate` (create).
