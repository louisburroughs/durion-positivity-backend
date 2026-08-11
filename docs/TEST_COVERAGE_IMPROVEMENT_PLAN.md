# Test Coverage Improvement Plan

Status: Phase 0 complete, Phase 1 complete. Phase 2 in progress (pos-event-receiver
and pos-marketing done; pos-order and pos-customer partial). Phases 3–4
outstanding.
Date: 2026-08-11

Method: `.agents/skills/test-coverage-improver` workflow, adapted from its pnpm/JS
assumptions to this Maven reactor; test authoring follows `.agents/skills/java-testing`.

> **Sections 1.1–1.3 below describe the stale reports that motivated this plan.
> They are kept for context but are superseded by the real measurement in
> §1.5 — read that first.**

## 1. Baseline: what the existing reports actually say

### 1.1 The aggregate report is unusable

`pos-coverage-aggregate/target/site/jacoco-aggregate/jacoco.xml` is dated
**2026-07-30** and reports **0.0% line and 0.0% branch coverage across all 29
groups** (64,458 lines, 64,458 missed). That is not a real measurement — it is a
report generated from a reactor run that carried no JaCoCo exec data (tests
skipped). It also contains only 29 of the 37 modules listed as
`pos-coverage-aggregate` dependencies, so several modules contributed no classes
at all.

Consequence: **there is currently no trustworthy repo-wide coverage number.**
Any target or threshold decision must wait for a clean regeneration.

### 1.2 Per-module reports exist for only 6 of ~36 code modules

Fresh (2026-08-10/11) per-module JaCoCo reports exist for:

| Module              | Lines | Line cov | Branch cov | Missed lines |
| ------------------- | ----: | -------: | ---------: | -----------: |
| pos-price           | 1,055 |    94.3% |      81.3% |           60 |
| pos-mcp-server      | 6,137 |    74.8% |      62.2% |        1,544 |
| pos-catalog         | 2,809 |    68.2% |      46.3% |          893 |
| pos-customer        | 5,365 |    39.0% |      29.6% |        3,270 |
| pos-marketing       | 1,238 |    28.0% |      29.8% |          891 |
| pos-bulk-ingest-lib |     3 |     0.0% |          — |            3 |

**No coverage data at all** for the three largest modules — `pos-inventory`
(584 main classes), `pos-accounting` (505), `pos-workorder` (351) — nor for
`pos-order`, `pos-invoice`, `pos-security-service`, `pos-people`,
`pos-shop-manager`, `pos-warranty`, `pos-location`, `pos-tax`, or the remaining
small modules.

### 1.3 Test-density proxy (test classes per main class)

Where coverage data is missing, test density is the best available proxy:

| Module                | Main | Unit tests |       ITs | Ratio |
| --------------------- | ---: | ---------: | --------: | ----: |
| pos-marketing         |   74 |          7 |         0 |  0.09 |
| pos-order             |  197 |         20 |         1 |  0.10 |
| pos-catalog           |  187 |         10 |        12 |  0.05 |
| pos-vehicle-fitment   |   50 |          7 |         0 |  0.14 |
| pos-vehicle-inventory |   58 |          8 |         0 |  0.14 |
| pos-customer          |  250 |         39 |         9 |  0.16 |
| pos-invoice           |  178 |         31 | **0 ITs** |  0.17 |
| pos-shop-manager      |  157 |         22 |         4 |  0.14 |
| pos-warranty          |  144 |         29 | **0 ITs** |  0.20 |
| pos-inventory         |  584 |         86 |        39 |  0.15 |
| pos-accounting        |  505 |        111 |        28 |  0.22 |
| pos-workorder         |  351 |         57 |        30 |  0.16 |
| pos-event-receiver    |   31 |          2 |         0 |  0.06 |
| pos-mcp-server        |  250 |        117 |         4 |  0.47 |

`pos-tax-common` has zero test classes.

### 1.4 Recurring zero-coverage archetypes

The same class shapes are uncovered in every module that has data. These are
cheap, high-count wins because one test template applies across ~30 modules:

1. **`{Module}EventTypes` registries** — `customer` 208 missed lines (0%),
   `marketing` 55 (0%). Pure static data; a single "registry is well-formed"
   test covers all of it.
2. **`{Module}EventTypeInitializer` / permission registrars** — startup
   `ApplicationRunner`s that swallow failures. Untested failure-swallow paths.
3. **`OutboxPublisher` / `ManifestPublisher`** — `catalog` 53 + 88 missed (0%),
   `marketing` 53 (0%). Scheduled/transactional publishers.
4. **Kafka/event listeners** — `InventoryEventsListener` 72 (0%),
   `InventoryManifestListener` 49 (0%), `VehicleEventsListener` 119 (0%),
   `CustomerEventsListener` 145 (0%).
5. **`ServiceImpl` classes at 0–1%** — `SegmentResolutionService` 238,
   `SegmentServiceImpl` 151, `PersonServiceImpl` 137,
   `PartyRelationshipServiceImpl` 129, `PartyTagServiceImpl` 116,
   `MarketingConsentServiceImpl` 115, `CampaignServiceImpl` 161,
   `CampaignSendServiceImpl` 68, `MessageTemplateServiceImpl` 61.
6. **Config classes** — `FlywayConfig`, `GlobalExceptionHandler` partials.

### 1.5 MEASURED BASELINE (2026-08-11, full reactor `verify`, BUILD SUCCESS in 44:21)

This supersedes §1.1–1.3. Produced by the Phase 0 command below, including
Failsafe ITs. The aggregate now reports 36 module groups instead of 29.

| | Baseline |
|---|---:|
| Total lines | 73,833 |
| **Line coverage** | **72.6%** |
| **Branch coverage** | **59.5%** |
| Missed lines | 20,265 |

Per module, ordered by missed lines:

| Module | Lines | Line% | Branch% | Missed |
| --- | ---: | ---: | ---: | ---: |
| pos-customer | 5,364 | 61.5 | 48.6 | 2,067 |
| pos-workorder | 7,465 | 73.0 | 55.0 | 2,012 |
| pos-inventory | 10,055 | 83.2 | 66.9 | 1,686 |
| pos-order | 3,241 | 53.4 | 43.2 | 1,510 |
| pos-accounting | 10,510 | 85.7 | 72.5 | 1,498 |
| pos-mcp-server | 6,152 | 78.5 | 67.4 | 1,324 |
| pos-invoice | 3,308 | 63.4 | 52.5 | 1,210 |
| pos-people | 2,859 | 61.5 | 47.1 | 1,101 |
| pos-catalog | 2,807 | 68.3 | 46.3 | 891 |
| pos-marketing | 1,236 | 28.1 | 29.8 | 889 |
| pos-people-contact | 1,477 | 45.6 | 26.9 | 803 |
| pos-vehicle-inventory | 976 | 28.8 | 25.9 | 695 |
| pos-security-service | 3,501 | 80.4 | 69.6 | 687 |
| pos-shop-manager | 1,933 | 67.3 | 60.2 | 633 |
| pos-location | 2,167 | 75.1 | 64.5 | 539 |
| pos-bulk-loader | 1,770 | 73.9 | 64.9 | 462 |
| pos-event-receiver | 437 | 3.0 | 0.0 | 424 |
| pos-warranty | 2,561 | 85.7 | 79.5 | 367 |
| pos-tax | 984 | 74.8 | 66.3 | 248 |
| pos-document-helper | 374 | 44.4 | 25.9 | 208 |
| pos-vehicle-reference-nhtsa | 189 | 0.0 | 0.0 | 189 |
| pos-domain-events | 511 | 65.8 | 56.1 | 175 |
| pos-vehicle-fitment | 542 | 77.9 | 62.9 | 120 |
| pos-documents | 383 | 74.4 | 51.9 | 98 |
| pos-security-common | 406 | 81.3 | 62.0 | 76 |
| pos-vehicle-reference-carapi | 69 | 0.0 | 0.0 | 69 |
| pos-api-gateway | 938 | 92.9 | 72.2 | 67 |
| pos-image | 61 | 0.0 | 0.0 | 61 |
| pos-price | 1,053 | 94.5 | 81.3 | 58 |
| pos-events | 174 | 81.0 | 64.3 | 33 |
| pos-tax-common | 100 | 79.0 | 46.4 | 21 |
| pos-inquiry | 16 | 0.0 | — | 16 |
| pos-shared-dtos | 34 | 61.8 | 8.8 | 13 |
| pos-openapi-validation | 173 | 93.6 | 82.3 | 11 |
| pos-service-discovery | 4 | 0.0 | — | 4 |
| pos-bulk-ingest-lib | 3 | 100.0 | — | 0 |

**Corrections to the assumptions in §1.2–1.3.** The three largest modules are
not the worst covered — `pos-accounting` (85.7%), `pos-inventory` (83.2%), and
`pos-warranty` (85.7%) are among the best. `pos-customer` is 61.5%, not the
39.0% its stale per-module report showed. The genuinely thin modules are
`pos-event-receiver` (3.0%), `pos-marketing` (28.1%), `pos-vehicle-inventory`
(28.8%), `pos-document-helper` (44.4%), `pos-people-contact` (45.6%), and
`pos-order` (53.4%).

**Aggregate vs per-module numbers differ for shared libraries, and the
difference matters for Phase 4.** `report-aggregate` merges exec data from every
module, so a shared library gets credit for the coverage its consumers' tests
produce: `pos-security-common` reads 81.3% in the aggregate but 29.7% in its own
report; `pos-events` 81.0% vs 31.0%; `pos-shared-dtos` 61.8% vs 0%. A per-module
JaCoCo `check` ratchet reads the **per-module** report, so its thresholds must be
set from those lower numbers, not from this table.

## 2. Phase 0 — Restore a trustworthy baseline (blocking, no test code)

Nothing downstream is worth doing until the numbers are real.

1. Regenerate full-reactor coverage with the CI-equivalent command:

   ```bash
   ./mvnw -DskipTests=false verify \
     org.jacoco:jacoco-maven-plugin:0.8.14:report \
     -Darchunit.skipTests=true \
     -DlowResourceTests=true \
     -T 1C -B -ntp
   ```

   This runs unit tests and Failsafe ITs, writes each module's
   `target/site/jacoco/jacoco.{xml,csv}`, and — because `pos-coverage-aggregate`
   builds last in the same reactor — produces a real
   `jacoco-aggregate/jacoco.xml`. Expect a long run; ITs dominate.

   A faster unit-only variant, if IT runtime is prohibitive on this machine:
   append `-DskipITs`. It undercounts modules whose coverage comes mostly from
   ITs (`pos-inventory`, `pos-workorder`, `pos-location`, `pos-people`), so
   treat those numbers as a floor, not a measurement.

2. Confirm the aggregate is no longer 0% and that all 37 declared modules appear
   as groups. `./scripts/check-coverage-aggregate-drift.sh` already guards module
   membership in CI; run it locally too.

3. Snapshot the resulting per-module table into this document as the **baseline**
   so every later change has a before/after.

**Deliverable of Phase 0:** a real baseline table. No production or test code
changes.

**Decision point:** review the baseline together before Phase 1. The module
ordering below is derived from stale/partial data and will be re-ranked against
the real numbers.

## 3. Phase 1 — Cross-cutting archetype tests (highest lines-per-effort)

One test pattern authored once, then replicated per module. These target the
§1.4 archetypes and are pure unit tests — no Spring context, fast, non-flaky.

| Wave | Target                                  | Pattern                                                                                                                                                          | Modules                                            |
| ---- | --------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------- |
| 1a   | `{Module}EventTypes`                    | Assert registry non-empty, ids unique, ids match `UPPER_SNAKE`, every id carries a valid threshold preset, ids match the `@EmitEvent` ids present in controllers | every module using `@EmitEvent`                    |
| 1b   | `{Module}EventTypeInitializer`          | MockRestServiceServer/`RestClient` stub: success path PUTs each type; failure path swallows and does not throw (the startup-safety contract)                     | same set                                           |
| 1c   | `{Module}PermissionRegistry`            | Registry well-formed; names match `domain:resource:action` snake_case; registration failure swallowed                                                            | every module with a registry                       |
| 1d   | `OutboxPublisher` / `ManifestPublisher` | Publishes pending rows, marks sent, retries/skips on failure, no-op on empty                                                                                     | catalog, marketing, and any module with an outbox  |
| 1e   | Kafka `*EventsListener`                 | Handle happy path, unknown/malformed payload, idempotent replay                                                                                                  | catalog, customer, marketing, inventory, workorder |

Rationale: in `pos-customer` alone, waves 1a + 1e cover ~327 currently-missed
lines; in `pos-marketing`, ~253. Replicated across ~30 modules this is the single
largest coverage delta available, and the tests are genuinely useful — they lock
in the event/permission registration contract that `CLAUDE.md` marks
non-negotiable.

### 3.1 Phase 1 outcome (delivered 2026-08-11)

**64 test files across 24 modules, all passing.** Waves 1a, 1b, and 1d are done;
waves 1c and 1e were not attempted and remain open (see §3.3).

Coverage delta measured on the first 11 modules verified: **395 missed lines
recovered**. Largest movers:

| Module | Before | After |
| --- | ---: | ---: |
| pos-event-receiver | 3.0% | 19.0% |
| pos-security-common | 29.7% | 46.7% |
| pos-events | 31.0% | 59.8% |
| pos-tax | 74.8% | 78.5% |
| pos-bulk-loader | 73.9% | 76.8% |
| pos-catalog | 68.3% | 70.1% |
| pos-invoice | 63.4% | 65.0% |

The remaining 13 modules' contribution is **not measured** — the confirming
full-reactor run was interrupted. Re-run the Phase 0 command to obtain the true
post-Phase-1 repo-wide figure before setting any Phase 4 thresholds.

The highest-value files are the two shared-library tests, since every module's
startup registration delegates to them: `pos-events`
`EventTypeInitializerSupportTest` (26 lines, previously 0%) and
`pos-security-common` `PermissionRegistrationSupportTest` (77 lines, previously
0%).

### 3.2 Findings surfaced by the new tests

1. **`WORKEXEC_DASHBOARD_TODAY_GET` uses hand-tuned thresholds** (1s/2s/3.5s)
   rather than one of the four presets. Legitimate — it aggregates across every
   open workorder for a location — so it is recorded as a documented exemption in
   `pos-workorder` `EventTypesTest`, paired with a guard test that fails if the
   exemption ever goes stale. Any *new* ad-hoc threshold in any module still
   fails the build.
2. **`pos-vehicle-inventory`'s `EventTypeInitializer` registers on a virtual
   thread**, so `run()` returns before any PUT is issued. It is the only async
   initializer in the reactor. Its tests await completion via Mockito
   `timeout()`; a naive "did not throw" assertion is vacuous there and was
   replaced with one that proves every type was still attempted.
3. **`pos-order`'s `OutboxPublisher` diverges from its 12 siblings**: no
   Micrometer counters, no `attempts` reset on success, and no class-name
   fallback or length cap on `last_error`. The divergences are safe —
   `last_error` is an unbounded `TEXT` column in both schemas — but they are easy
   to erase by copying a sibling over it, so its bespoke test pins each one
   explicitly.

### 3.3 Phase 1 remainder

- **Wave 1c (`{Module}PermissionRegistry`)** — not started. Only three modules
  define a registry class (`pos-inventory`, `pos-customer`, `pos-marketing`); the
  shared `PermissionRegistrationSupport` they extend is now covered, so the
  remaining value here is small.
- **Wave 1e (Kafka `*EventsListener`)** — closed for `pos-customer`,
  `pos-marketing`, and `pos-order` (see §4.1 and §4.2); open elsewhere. It was
  originally recorded as not started, and it is the largest
  remaining archetype: **78 listener classes** across the reactor, many at 0%
  (`CustomerEventsListener` 145 missed lines, `VehicleEventsListener` 119,
  `InventoryEventsListener` 72, `InventoryManifestListener` 49). Unlike the other
  waves these are not uniform enough for one template, so expect per-module work.
- **`ManifestPublisher`** (10 classes) was folded into wave 1d's scope in the
  table above but not implemented; only `OutboxPublisher` was covered.

## 4. Phase 2 — Worst-covered domain services

**Re-ranked against the §1.5 measurement.** Phases 2 and 3 as originally written
split "modules with data" from "modules without data"; that split no longer
exists now that every module is measured. The merged priority order is:

| # | Module | Line% | Missed | Why here |
| ---: | --- | ---: | ---: | --- |
| 1 | ~~pos-event-receiver~~ | 3.0 | 424 | **Done** — every service registers against it; was lowest in the reactor |
| 2 | pos-customer | 61.5 | 2,067 | Largest absolute gap; several waves landed, not yet re-measured |
| 3 | pos-order | 53.4 | 1,510 | **In progress — 77.9% line / 60.8% branch unit-only** (see §4.2) |
| 4 | ~~pos-marketing~~ | 28.1 | 889 | **Done — now 87.9% line / 82.9% branch, 150 missed** (see §4.1) |
| 5 | pos-people | 61.5 | 1,101 | Large gap, thin suite |
| 6 | pos-invoice | 63.4 | 1,210 | Large gap, **0 ITs** |
| 7 | pos-workorder | 73.0 | 2,012 | Large absolute gap despite decent ratio |
| 8 | pos-people-contact | 45.6 | 803 | Low ratio and low branch coverage (26.9%) |
| 9 | pos-vehicle-inventory | 28.8 | 695 | Third-lowest coverage overall |
| 10 | pos-catalog | 68.3 | 891 | Branch coverage (46.3%) is the real weakness |
| 11 | pos-document-helper | 44.4 | 208 | Shared library; `DocumentServiceClient` 80 missed at 0% |
| 12 | pos-shop-manager | 67.3 | 633 | |
| 13 | pos-mcp-server | 78.5 | 1,324 | Large absolute gap despite good ratio |

Deliberately **not** prioritized: `pos-accounting` (85.7%), `pos-inventory`
(83.2%), `pos-warranty` (85.7%), `pos-security-service` (80.4%), `pos-price`
(94.5%), `pos-api-gateway` (92.9%), `pos-openapi-validation` (93.6%).

The four small modules at 0% — `pos-vehicle-reference-nhtsa` (189),
`pos-vehicle-reference-carapi` (69), `pos-image` (61), `pos-inquiry` (16) — total
335 missed lines and are cheap; treat them as filler work rather than a priority.

### 4.1 pos-marketing outcome (delivered 2026-08-11)

**28.1% → 87.9% line, 29.8% → 82.9% branch** (889 → 150 missed lines), 183 unit
tests, no ITs added. Every service-layer class is now covered:

| Class | Was | Notes |
| --- | ---: | --- |
| `CampaignServiceImpl` | 0% | Lifecycle, readiness aggregation, audience-type immutability |
| `CampaignSendServiceImpl` | 0% | Dispatch matrix, collision tolerance, page-size clamp |
| `CampaignStatsServiceImpl` | 0% | Cumulative funnel arithmetic, null-SUM handling |
| `MessageTemplateServiceImpl` | 0% | Channel/audience immutability, in-use delete guard |
| `CustomerEventsListener` | 0% | Replay guard, redemption idempotency, mid-send audience protection |
| `MarketingFactPublisher` | 0% | Kafka-disabled no-op, party-keyed send facts |
| `OutboxEventWriter` | 4.5% | Aggregate-keyed row, fatal serialization failure |
| `SegmentResolveRequester` | 5.0% | Correlated resolve command, segment-keyed ordering |
| `MarketingExceptionHandler` | 0% | ADR-0017 status/code pairing, correlation-id passthrough |

Remaining 150 missed lines are controllers (30), Spring config (`OpenApiConfig`,
`RestClientConfig`, `FlywayConfig`, `KafkaErrorHandlingConfig`, 40), and
`LoggingMessageChannel`. Controllers want `@WebMvcTest` slices, which is a
different shape of work; the module's business logic is done.

### 4.2 pos-order progress (2026-08-11)

Measured **unit-only** (`-DskipITs`), so these are not comparable to the §1.5
figure, which included ITs. Unit-only baseline at the start of this pass was
57.5% line / 44.0% branch, 1,377 missed.

| | Unit-only baseline | Now |
|---|---:|---:|
| Line | 57.5% | **77.9%** |
| Branch | 44.0% | **60.8%** |
| Missed lines | 1,377 | **715** |

Delivered:

- **All five `ext_*` replica listeners** (customer, vehicle, product, location,
  workorder — 339 missed lines at 0%). Their shared consumer contract is pinned
  once, parameterized across all five, in `ReplicaListenerContractTest`; the
  per-listener tests cover mapping, the aggregate-version guard, and the
  snapshot-is-a-replacement rule for workorder lines. **This closes wave 1e for
  `pos-order`.**
- **`OrderDomainEventPublisher`** (148 missed at 2.6%) — the module's whole
  outbound contract, including the R7.5 rule that a WORKORDER-sourced line is
  not fulfillable.
- **The four exception advices** (169 missed) — status/code pairings per endpoint
  family, per-line over-cap field errors, correlation-id passthrough.

Remaining in `pos-order`, largest first: `SalesOrderServiceImpl` (133 missed,
72.4%), `ReturnOrderServiceImpl` (61, 77.4%), `RestInvoicingPortAdapter` (56,
6.7%), `OrderTaxService` (53, 1.9%), `RegisterSessionServiceImpl` (39, 78.0%),
`CatalogPricePricingAdapter` (34, 19.0%), `RegisterSessionController` (27),
`ReturnOrderResponse`/`SessionReportResponse`/`RegisterSessionResponse` DTOs
(60 combined at 0%), `OrderNumberService` (19), `ReplicaCustomerPortAdapter`
(16).

The per-module detail below predates the measurement and is kept for the specific
class-level targets it names.

**pos-customer (39.0% → target 70%)** — largest measured gap, 3,270 missed lines.

- `SegmentResolutionService` (238 missed, 0%) — segment predicate evaluation,
  boundary and empty-criteria cases.
- `SegmentServiceImpl` (151, 0%), `PersonServiceImpl` (137, 1%),
  `PartyRelationshipServiceImpl` (129, 1%), `PartyTagServiceImpl` (116, 1%),
  `MarketingConsentServiceImpl` (115, 1%) — CRUD + validation + not-found +
  conflict paths with `@Mock` repositories.
- `VehicleEventsListener` (119, 0%) — covered by wave 1e.

**pos-marketing (28.0% → target 70%)** — 891 missed lines, only 7 test classes,
zero ITs.

- `CampaignServiceImpl` (161), `CampaignSendServiceImpl` (68),
  `MessageTemplateServiceImpl` (61), `CampaignStatsServiceImpl` (40),
  `MarketingFactPublisher` (44).

**pos-catalog (68.2% line / 46.3% branch → target 80/65)** — branch coverage is
the real weakness.

- `CatalogServiceImpl` (108), `ProductDetailServiceImpl` (91),
  `PriceBookServiceImpl` (86), `SupplierItemCostServiceImpl` (43) — parameterized
  tests over the uncovered branches rather than more happy paths.

**pos-mcp-server (74.8% → target 85%)**

- `ToolMetadataRepositoryImpl` (101, 0%), `SiteMapClient` (86, 0%),
  `ToolRegistryService` (77, 47%), `OperationProxyFactory` (70, 25%),
  `SemanticChatMemoryStore` (62, 30%), `OpenApiDocumentFetcher` (70, 66%),
  `OpenApiToolMapper` (61, 74%).

**pos-price (94.3%)** — leave alone. Already the reference standard; use its test
style as the template for other modules.

## 5. Phase 3 — (merged into Phase 2)

Every module now has real coverage data, so the "no data" distinction that
defined this phase is gone; its modules are folded into the §4 priority table.
The list below is retained only for the two structural gaps it records, which the
measurement does not show:

- `pos-invoice` (178 main classes) and `pos-warranty` (144) have **zero
  integration tests**. Both need a Failsafe IT layer, not just more unit tests —
  `pos-warranty` reaching 85.7% on unit tests alone means its persistence and
  transaction boundaries are untested.
- `pos-tax-common` has **zero test classes** of its own (its 79.0% aggregate
  reading comes entirely from consumers' tests).

Original provisional ordering, superseded by §4:

1. `pos-inventory` (584 main) — largest surface in the repo.
2. `pos-accounting` (505 main) — financial posting semantics; highest blast
   radius per defect.
3. `pos-workorder` (351 main).
4. `pos-order` (197 main, 20 tests, 1 IT) — worst density among large modules.
5. `pos-invoice` (178 main, **0 ITs**) — add a Failsafe IT layer, not just units.
6. `pos-warranty` (144 main, **0 ITs**) — same.
7. `pos-shop-manager`, `pos-people`, `pos-location`, `pos-security-service`.
8. Small modules: `pos-event-receiver` (2 tests / 31 classes),
   `pos-tax-common` (zero tests), `pos-vehicle-fitment`, `pos-vehicle-inventory`.

## 6. Phase 4 — Lock the gains in

Only after real numbers exist and the first waves land:

1. Add a JaCoCo `check` rule with per-module minimums set slightly **below**
   each module's measured baseline (ratchet, never a cliff). Do not set a single
   repo-wide 80% bar — at the measured 72.6% it would fail roughly half the
   reactor on day one. Two constraints from §1.5: set thresholds from each
   module's **own** report, not the aggregate (shared libraries read far higher in
   the aggregate), and re-measure after Phase 1 first — the numbers in §1.5 are
   pre-Phase-1 for 13 of the 24 modules that received tests.
2. Wire the aggregate XML into the existing SonarCloud step so the coverage
   number is visible per PR.
3. ~~Consider excluding generated code via `jacoco.excludes`.~~ **Resolved — no
   work needed.** Commit `8dd877415` added
   `com.positivity.shared.annotation.@CoverageGenerated` (`@Retention(CLASS)`),
   whose name ends in `Generated`, so JaCoCo's built-in generated-code filter
   excludes annotated types automatically — no `jacoco.excludes` configuration
   is required. It is currently applied to the 24 `Pos*Application` mains only,
   which matches the annotation's own instruction not to apply it to handwritten
   business behavior. Extend it case by case if genuinely mechanical code shows
   up in a coverage gap; do not use it to hide untested logic.

## 7. Standing conventions for every test written under this plan

Per `.agents/skills/java-testing` and repo conventions:

- JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)`) + AssertJ.
- `@ParameterizedTest` for branch coverage rather than repeated near-identical
  `@Test` methods.
- Unit tests named `*Test.java` (Surefire, `test` phase); anything needing a
  database or Spring context named `*IT.java` (Failsafe, `verify` phase).
- `@WebMvcTest` slices for controllers, not full `@SpringBootTest`.
- Run `./mvnw spotless:apply` before commit; Checkstyle/SpotBugs gate the build.
- Run `./mvnw -pl pos-archunit -am -Dtest=ArchitectureTests test` if any package
  layout is touched.
- No coverage-inflating assertion-free tests. Every added test must assert a
  behavior that could plausibly regress.

## 8. Open questions for the user

1. Is a full-reactor `verify` (with ITs) acceptable to run locally, or should
   Phase 0 use `-DskipITs` and accept an undercount for IT-heavy modules? --> We need to run the full reactor at least once to get a real baseline.
2. Target coverage: a uniform per-module floor, or a per-module ratchet from the
   measured baseline? (Recommendation: ratchet.) --> Ratchet is good
3. Scope of the first implementation wave — Phase 1 archetypes across all
   modules, or depth-first on `pos-customer` + `pos-marketing`?
   (Recommendation: Phase 1 first; it is cheaper per line and the tests encode a
   documented contract.) --> Phase 1 first
