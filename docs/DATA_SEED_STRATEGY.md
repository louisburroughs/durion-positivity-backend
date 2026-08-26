# Data Seed Strategy

**Status:** Proposed (2026-08-26)
**Supersedes:** the implicit "seed everything via Flyway `R__seed_*` migrations" practice
**Related:** ADR-0044 (event-only domain walls), `docs/OPERATIONS_RUNBOOK.md` ("Replica seeding
and drift repair"), `docs/ARCHITECTURE_GUIDE.md` (event versioning table)

## 1. Problem

Today ~10,700 lines of SQL across 22 `R__seed_*.sql` repeatable Flyway migrations insert both
reference data and demo/operational data (customers, people, vehicles, mechanics, timekeeping
rows) directly into service schemas. This is broken in three independent ways:

1. **Events never fire.** A Flyway `INSERT` bypasses the application layer entirely: no
   `@EmitEvent` audit event reaches `pos-event-receiver`, no outbox row is written, and no fact
   is published on `{domain}.events.v1`. Every consumer that projects an `ext_*` replica from
   Kafka (pos-workorder, pos-order, pos-marketing, pos-warranty, pos-inventory, pos-supplier,
   pos-shop-manager, pos-invoice, …) starts cold against seeded owners. Worse, the
   reconciliation manifests (`{domain}.manifest.v1`) are computed from the owner's *outbox*, so
   drift detection is blind to the gap — owner and consumer agree that "no events happened,"
   while the owner's tables are full and the consumer's replicas are empty. The safety net
   reports green precisely because the data skipped the channel it guards.

2. **Wrong environment scope.** Repeatable migrations run wherever Flyway runs. The demo data
   (Charlotte-metro customers, fleet operators, mechanics) is only appropriate for **alpha**,
   but nothing gates it: a prod bootstrap would apply the same `R__seed_*_operational_*` files.
   Conversely, the data *cannot* "propagate" to later environments in any meaningful sense —
   later environments must be populated by real usage and real imports, not by SQL fixtures.

3. **Hand-maintained cross-service consistency.** Because direct SQL can't ask another service
   for an ID, seed files coordinate identity through reserved UUID namespaces
   (`01960020-*` … `01960029-*`) that must line up across pos-customer, pos-people,
   pos-people-contact, etc. This is exactly the cross-service foreign-key coupling the
   architecture forbids, reintroduced through fixtures (see the accumulated
   `scripts/fix_uuids*.py` one-offs for the maintenance cost).

## 2. The classification rule

A dataset may be seeded via Flyway **only if all three hold**:

- **(a) Environment-invariant.** The rows are identical in alpha and prod (lookup/enum tables,
  RBAC baseline, GL chart-of-accounts, UoM definitions). If a row is "demo," it fails.
- **(b) Never crosses a domain wall.** The entity is not published on any `{domain}.events.v1`
  or fact topic and is never projected into another service's `ext_*` replica. If a consumer
  replicates it, Flyway seeding silently starves that consumer.
- **(c) No event-audited lifecycle.** Creating the row through the API would not be required to
  emit an `@EmitEvent` audit event that operations relies on.

Everything else **must enter through the owning service's application layer** — the same path
production traffic takes — so that `@EmitEvent`, the outbox, Kafka facts, and replica
projection all happen for free.

> **The catalog caveat.** The `R__seed_reference_catalog*.sql` files are named "reference" but
> fail rule (b): products and services are replicated via `catalog.events.v1` into
> pos-marketing, pos-warranty, pos-inventory, and pos-supplier. "Reference vs. operational"
> naming is *not* the classification — publication on a topic is.

## 3. Target architecture

### Tier 1 — Flyway (kept): service-private, environment-invariant configuration

Stays exactly where it is: `R__seed_role_permissions.sql`, `R__seed_reference_security.sql`,
`R__seed_reference_accounting.sql`, `R__seed_reference_invoice.sql`,
`R__seed_reference_people*.sql`, `R__seed_location_1_reference.sql`,
`R__seed_reference_price.sql`, `R__seed_reference_inventory.sql`, and the versioned
config seeds (`V3__seed_labor_overhead_mapping.sql`, `V18__seed_facade_tool_permissions.sql`,
`V34__processing_return_workflow_seed.sql`). These are schema-adjacent configuration each
service owns outright; no other service ever hears about them, so no event is missing.

### Tier 2 — API-driven seed pipeline (new): alpha demo data

All `*_operational_*` seed data (and catalog items — see §2) moves out of Flyway into an
**alpha seed pipeline** that writes through service APIs:

- **Preferred channel: `pos-bulk-loader`.** It already posts CSV-derived chunks to owning
  services' `/v1/{domain}/bulk-ingest` endpoints over load-balanced REST, and those endpoints
  already run in the application layer — `CatalogBulkIngestController` is `@EmitEvent`-annotated
  and publishes a product fact per row. Loader strategies exist today for catalog products,
  base prices, customers/persons, people, vehicles, and vehicle fitment.
- **Fallback channel: plain gateway API calls** (scripted, `X-API-Version: 1`, a dedicated
  `seed-operator` service account) for domains with no bulk-ingest endpoint yet.

The pipeline is:

1. **Fixture packs, not SQL.** Convert each operational seed file to CSV/JSON fixtures under
   `scripts/fixtures/seed/alpha/`, one directory per domain, checked into this repo. The
   fixture content is the current seed data — nothing is lost in the move, it just changes
   transport.
2. **Dependency-ordered orchestration.** A driver script runs domains in DAG order:
   security (users/roles) → location → people → people-contact → customer → vehicle →
   catalog → price → inventory → (optional demo workorders/orders). Downstream domains no
   longer need reserved UUID namespaces: the driver captures the IDs each service returns and
   substitutes them into later fixtures (a seed manifest, written next to the fixtures' run
   output). Where deterministic IDs are genuinely required, the bulk-ingest request may accept
   a client-supplied UUIDv7 — that is an explicit, per-endpoint decision, not a default.
3. **Idempotent by natural key.** Bulk-ingest endpoints upsert on the domain's natural key
   (`customer_number`, product code, VIN, …) so re-running the pipeline converges instead of
   duplicating. This also makes partial-failure recovery "just run it again."
4. **Alpha-only by construction.** The pipeline is an operator action pointed at the alpha
   gateway; it is not wired into any service startup, migration, or image. Nothing has to be
   "gated off" in later environments because nothing runs there.

### Tier 3 — Later environments: no synthetic data

Beta/prod get **no seed pipeline**. Data arrives via real usage and real customer-conversion
imports through `pos-bulk-loader` — which, because it writes through the same bulk-ingest
endpoints, produces the same events and replica projections as the alpha pipeline. Cold
replicas (new consumer, new environment, disaster recovery) are hydrated with the sanctioned
repair tools:

- `POST /{domain}/v1/…/facts/replay` (catalog products #1309, catalog services #1306,
  vehicle registry) — state-derived, paged, idempotent for consumers via `ReplicaVersionGuard`.
- `POST /workorder/v1/outbox/replay?since=…` — outbox-derived; replay history equals outbox
  retention.

**Replay is repair, not seeding.** A fact replay re-announces current state on the fact topic;
it does not produce `@EmitEvent` audit events and (for outbox-bound replays) cannot re-emit
what was never emitted. Using replay to paper over Flyway-seeded data is the current failure
mode wearing a different hat — after the Tier-2 cutover it is only needed for genuinely cold
replicas.

## 4. Disposition of existing seed files

| File | Tier | Action |
|---|---|---|
| pos-security-service `R__seed_role_permissions.sql`, `R__seed_reference_security.sql` | 1 | Keep in Flyway |
| pos-security-service `R__seed_security_operational_data.sql` | 2 | Demo users → seed pipeline (security API) |
| pos-accounting `R__seed_reference_accounting.sql`, `V3__seed_labor_overhead_mapping.sql` | 1 | Keep |
| pos-invoice `R__seed_reference_invoice.sql` | 1 | Keep |
| pos-price `R__seed_reference_price.sql` | 1 | Keep (verify nothing in it is published on a topic) |
| pos-inventory `R__seed_reference_inventory.sql` | 1 | Keep (same verification) |
| pos-location `R__seed_location_1_reference.sql` | 1 | Keep |
| pos-location `R__seed_location_2_operational_data.sql` | 2 | **In progress** — the 5 location rows converted to `scripts/fixtures/seed/alpha/location/` (LOCATION loader job wired, timezone now ingestable); storage locations, bays, mobile units, and parent edges still need a scripted gateway pass or their own ingest wave |
| pos-people `R__seed_reference_people.sql` | 1 | Keep |
| pos-people `R__seed_people_operational_data.sql`, `R__seed_timekeeping_approval_data.sql` | 2 | Seed pipeline (`PersonLoaderStrategy` exists) |
| pos-people-contact `R__seed_reference_people_contact.sql` | 1 | Keep |
| pos-people-contact `R__seed_people_contact_operational_data.sql` | 2 | Seed pipeline |
| pos-customer `R__seed_customer_operational_data.sql` | 2 | **Converted** — individuals and commercial accounts (with primary contacts) in `scripts/fixtures/seed/alpha/customer/`, loadable via the bulk loader (`CUSTOMER`, `COMMERCIAL_CUSTOMER`); file deletion waits on the verified alpha reseed (§5.4) |
| pos-vehicle-inventory `R__seed_vehicle_inventory_operational_data.sql` | 2 | Seed pipeline (`VehicleLoaderStrategy` exists) |
| pos-catalog `R__seed_reference_catalog*.sql` (5 files) | 2* | Replicated via `catalog.events.v1` → move to bulk-ingest (`CatalogLoaderStrategy` exists). Interim: keep Flyway **plus a mandatory post-seed products+services `facts/replay`** documented in the alpha bootstrap runbook |
| pos-shop-manager `R__seed_shop_manager_mechanics.sql` | 2 | **Audit first:** if mechanics are a projection of pos-people events, this file seeds a *replica* by hand — delete it and let the people seed + `PeopleEventsListener` populate it |
| pos-mcp-server `V18`, `V34` config seeds | 1 | Keep |

## 5. Migration plan

1. **Freeze.** No new rows land in `R__seed_*operational*` files; new demo data starts life as
   a fixture pack.
2. **Idempotency pass.** Give each seed-target bulk-ingest endpoint upsert-by-natural-key
   semantics (several already behave this way; verify per domain).
3. **Convert.** Translate operational seed SQL → fixture packs + driver script, domain by
   domain, in the DAG order above. Each conversion PR deletes the corresponding `R__seed_*`
   file in the same change (removing a repeatable migration is safe — Flyway simply stops
   re-applying it; already-applied rows in alpha are then superseded by the reseed below).
4. **Reseed alpha.** Reset alpha schemas (alpha only, announced), boot services, run the
   pipeline, then verify: owner row counts vs. `ext_*` replica counts, `replica_drift_total`
   flat, expected event volume visible in pos-event-receiver. This is the acceptance test that
   the events actually fired.
5. **Guard.** Extend `scripts/check-flyway-hygiene.sh` to fail the build when a file matching
   `R__seed_*operational*` (or inserting into a known replicated table) appears under
   `db/migration/` — the classification rule in §2, enforced.
6. **Document.** Update `docs/OPERATIONS_RUNBOOK.md` alpha-bootstrap section to point at the
   pipeline; retire the UUID-namespace conventions and the `scripts/fix_uuids*.py` family.

## 6. Consequences

- Seeded alpha behaves like a real environment: replicas hydrate through Kafka, audit events
  exist, drift detection actually covers the data, and demos exercise the same code paths as
  production traffic.
- Later environments need no gating logic because synthetic data has no path into them; the
  only ingestion route (bulk loader) is also the production-correct one.
- Cross-service UUID choreography disappears from fixtures; identity flows through APIs.
- Costs: bulk-ingest coverage must grow (location today; workorder/order demo flows if alpha
  wants them), the driver script is new operational surface, and an alpha reseed is a
  one-time disruption.
