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

## Running a pack (alpha only)

Per domain, in the dependency order below:

1. Upload the CSV to `pos-bulk-loader` (port 8090; file upload or TUS endpoint).
2. Create a bulk-load job for the file with the matching `domainType` (e.g. `CUSTOMER`)
   and the target alpha `locationId`.
3. Launch the job. The loader chunks rows to the owning service's
   `/v1/{domain}/bulk-ingest` endpoint; per-row failures are reported in the job's
   review queue without aborting the batch.
4. Verify: row counts on the owner, `replica_drift_total` flat, expected event volume
   in pos-event-receiver.

Run order (services must exist before data referencing them): security users/roles →
**location** → people → people-contact → **customer** → vehicle → catalog → price →
inventory. Locations must be loaded (or already present) first in any case — bulk-load
jobs themselves require a valid `locationId`.

## Packs

### `customer/` — from `pos-customer R__seed_customer_operational_data.sql`

| File | Rows | Target |
|---|---|---|
| `person-customers.csv` | 50 individual customers | `POST /v1/customer/bulk-ingest` (`domainType: CUSTOMER`) |

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
- The seed's 20 commercial parties and their primary contacts are **not yet
  converted**. The endpoint for them now exists —
  `POST /v1/customer/commercial/bulk-ingest` creates the account (customer number is
  service-generated; the seed's `CUST-CP-*` numbers are not preserved) and optionally
  creates + attaches one PRIMARY_CONTACT person per row — but the fixture CSV and a
  bulk-loader COMMERCIAL job are still to come. The 20 billing-contact persons
  (`01960026-*`) have no active seed rows in pos-customer since V6 dropped the contact
  table; whether to model them as BILLING relationships is a conversion-time decision.

Because coverage is partial, the Flyway seed file (and its
`scripts/flyway-seed-baseline.txt` line) stays until the commercial half is convertible
and alpha has been reseeded and verified (`docs/DATA_SEED_STRATEGY.md` §5.4).
