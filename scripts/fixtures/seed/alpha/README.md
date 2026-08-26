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
