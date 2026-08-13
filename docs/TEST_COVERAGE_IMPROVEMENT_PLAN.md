# Test Coverage Improvement Plan

Status: Phases 0–3 complete. Phase 4 (ratchet) landed — per-module JaCoCo
`check` floors are enforced at `verify`; see §6. Both §5 structural gaps are
closed: `pos-invoice` and `pos-warranty` have Failsafe IT layers, and
`pos-tax-common` has its first suite. Wave 1c (§3.3) and controller-layer
`@WebMvcTest` coverage remain the known open work (§7).
Date: 2026-08-11 (last updated 2026-08-12)

**That re-measure has now been done — see §6 for the authoritative baseline.**
Every figure in §4.1–§4.5 is unit-only (`-DskipITs`) and is therefore *not*
comparable to §1.5 or §6; those sections record per-module progress, not
thresholds. Phase 4's floors come from §6 only.

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
| 2 | pos-customer | 61.5 | 2,067 | **In progress — 85.9% line / 70.6% branch unit-only** (see §4.3) |
| 3 | pos-order | 53.4 | 1,510 | **In progress — 83.6% line / 67.7% branch unit-only** (see §4.2) |
| 4 | ~~pos-marketing~~ | 28.1 | 889 | **Done — now 87.9% line / 82.9% branch, 150 missed** (see §4.1) |
| 5 | pos-people | 61.5 | 1,101 | **In progress — 79.4% line / 63.6% branch unit-only** (§4.4) |
| 6 | pos-invoice | 63.4 | 1,210 | **In progress — 77.7% line / 64.0% branch unit-only**; still **0 ITs** (§4.4) |
| 7 | pos-workorder | 73.0 | 2,012 | Large absolute gap despite decent ratio |
| 8 | pos-people-contact | 45.6 | 803 | **In progress — 65.2% line / 52.3% branch unit-only** (§4.4) |
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
| Line | 57.5% | **83.6%** |
| Branch | 44.0% | **67.7%** |
| Missed lines | 1,377 | **530** |

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
- **`OrderTaxService`** (53 at 1.9%) — every jurisdiction/tax failure raises
  `TaxUnavailableException` instead of defaulting to zero.
- **`RestInvoicingPortAdapter`** (56 at 6.7%) and **`CatalogPricePricingAdapter`**
  (34 at 19.0%) — the module's two synchronous outbound calls, via
  `MockRestServiceServer`.
- **`ReplicaCustomerPortAdapter`** (16 at 5.9%) and **`OrderNumberService`**
  (19 at 5.0%).

Remaining in `pos-order`, largest first: `SalesOrderServiceImpl` (133 missed,
72.4%), `ReturnOrderServiceImpl` (61, 77.4%), `RegisterSessionServiceImpl` (39,
78.0%), `RegisterSessionController` (27, 3.6%), `ReturnOrderController` (22,
4.3%), the `ReturnOrderResponse`/`SessionReportResponse`/`RegisterSessionResponse`
DTOs (60 combined at 0%, all static `from(...)` mappers), `PaymentEventsListener`
(19, 83.9%), `PriceOverrideServiceImpl` (16, 93.2%). The two controllers want
`@WebMvcTest` slices; the rest are ordinary unit work.

### 4.3 pos-customer progress (2026-08-11)

Measured **unit-only** (`-DskipITs`), so not comparable to the §1.5 61.5%, which
included ITs. The earlier waves (segment/party-tag services, suppression register,
replica listeners, consent resolution, account tier, permission registry, manifest
publisher) had never been re-measured; they had already taken the module to 80.7%
line / 65.9% branch before this pass began.

| | Start of this pass | Now |
|---|---:|---:|
| Line | 80.7% | **85.9%** |
| Branch | 65.9% | **70.6%** |
| Missed lines | 1,034 | **757** |

Delivered this pass:

- **`PersonPartyServiceImpl`** (74 missed at 30.2%) — sort sanitization, directory
  delegation for name/email search, the deliberate non-application of name edits.
- **`CommercialPartyServiceImpl`** (70 at 24.7%) — organization CRUD and the
  email-only search short-circuit.
- **`CrmExceptionHandler`** (38 at 53.1%) — ADR-0017 status/code pairings, the
  422-vs-400 distinction, correlation-id passthrough, null-request tolerance.
- **`OutboxEventWriter`** (21 at 4.5%) — aggregate-keyed row, fatal serialization
  failure.
- **`PartyServiceImpl`** billing rules and duplicate check (~86 of its 99 missed).

**Two defects found and documented, not fixed** (both API-visible, so changing
them is a separate decision):

1. **A partial update wipes a party's VIN list.** `CustomerDTO` declares
   `vehicleVins` `@Builder.Default` with an empty `ArrayList` and initializes it in
   the no-args constructor Jackson uses, so the `vehicleVins != null` guard in
   `updateEntityFromDTO` can never be false. A PUT that simply omits `vehicleVins`
   reaches the `clear()`/`addAll()` branch with an empty list and drops every VIN
   association. Both `PersonPartyServiceImpl` and `CommercialPartyServiceImpl` are
   affected.
2. **`CommercialPartyServiceImpl` transposes its two name fields on a round
   trip.** `toEntity` maps `firstName -> legalName` and `lastName -> displayName`;
   `toDTO` maps `legalName -> lastName` and `displayName -> firstName`. A client
   that POSTs a commercial party and reads it back gets the two names swapped.
   `updateEntityFromDTO` follows the write direction, so updates transpose too.

Both are pinned by tests carrying an explicit "documents current behaviour, not
desired behaviour" comment, so a future fix will fail a test rather than pass
silently. Filed as louisburroughs/durion-positivity-backend#1245 (VIN loss) and
louisburroughs/durion-positivity-backend#1246 (name transposition); both issues
name the pinning tests that must be updated alongside the fix.

Remaining in `pos-customer`, largest first: `SegmentResolutionService` (64 missed,
73.1% — almost all of it in the `loadCommercialCandidates` projection lambda),
`CustomerFactPublisher` (42, 68.7% — six publish methods), `InquiryServiceImpl`
(38, 65.8%), `CustomerCommandListener` (31, 64.8%), `SegmentPredicateEvaluator`
(30, 61.5%), `CrmAccountsController` (29, 45.3%), `FollowUpTaskServiceImpl` (27,
58.5%), `SegmentPredicateValidator` (26, 71.4%), and the CRM controllers
(`CustomerController` 26, `CrmPartyRelationshipController` 24, `CrmPersonController`
21 — all want `@WebMvcTest` slices).

### 4.4 pos-people, pos-invoice, pos-people-contact progress (2026-08-11)

All figures **unit-only** (`-DskipITs`). As with `pos-customer`, the §1.5
baselines were stale: measured starting points were `pos-people` 66.2% (not
61.5%), `pos-invoice` 67.5% (not 63.4%), `pos-people-contact` 54.8% (not 45.6%).

| Module | Line | Branch | Missed lines |
|---|---|---|---|
| pos-people | 66.2% → **79.4%** | 53.3% → **63.6%** | 965 → 588 |
| pos-invoice | 67.5% → **77.7%** | 58.2% → **64.0%** | 1,075 → 739 |
| pos-people-contact | 54.8% → **65.2%** | 38.3% → **52.3%** | 667 → 514 |

The dominant find was that **wave 1e is far from closed repo-wide**: every Kafka
listener in `pos-people` (5 classes) and `pos-invoice` (7) was at 0%. Both
modules' listeners fall into two internally-identical families — replica
listeners and reconciliation manifest listeners — so each family's shared
contract is now pinned once in a parameterized contract test, with only per-owner
field mapping covered separately. That pattern (first used for `pos-order`) is
the cheapest way to close the remaining wave-1e surface elsewhere.

One cross-module inconsistency is now pinned rather than "fixed": whether a
replica listener records a `processed_events` row for an event type it ignores
depends on **whether that owner has a manifest listener in the same module**.
`pos-order`'s listeners skip the row; `pos-people`'s and `pos-invoice`'s record
it, because their manifest listeners compare against the owner's count of every
fact in the window. `pos-people`'s workorder listener skips it, correctly, since
that module runs no workorder manifest listener. Aligning these without moving
the manifest listeners would break one side or the other.

**A recurring Jackson 3 hazard, worth a decision.** `FAIL_ON_NULL_FOR_PRIMITIVES`
is on by default, so a payload that simply **omits** a primitive field
(`boolean`, `int`) fails deserialization. In a replica listener that failure
lands in the malformed-payload branch: the fact is **silently dropped and still
marked processed**, so reconciliation will not flag it either. It bit three
event records during this work (`OrderInvoiceResponse.existing`,
`CustomerPartyUpdatedV1.requirementsMet`, `LocationUpdatedV1.active`). Producers
in this repo always send the field, so nothing is broken today — but a producer
that ever omits one gets silent data loss with no signal. Worth deciding whether
to box these fields, or relax the setting on consumer mappers.

Remaining in these three: `pos-people-contact` `PersonServiceImpl` (127 missed),
`PeopleContactCommandListener` (61), `PeopleExceptionHandler` (58),
`LinkCommandHandler` (31); `pos-invoice` `InvoiceArtifactService` (65),
`PaymentEventPublisher` (54), `InvoiceEventPublisher` (47),
`InvoiceExceptionHandler` (40), `TaxServiceClient` (32); `pos-people`
`EmployeeServiceImpl` (38), `PeopleExceptionHandler` (27), and two DTO classes
(66 combined).

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

### 4.5 Phase 3 completion pass (2026-08-12, stacked PRs)

All figures **unit-only**. Worked in a stacked-PR series so each review stays
small: #1249 (vehicle-inventory, document-helper) → #1250 (catalog,
shop-manager) → #1251 (workorder, tax-common) → mcp-server.

| Module | Line | Branch |
|---|---|---|
| pos-vehicle-inventory | 46.5% → **66.4%** | 42.4% → **71.0%** |
| pos-document-helper | 43.1% → **74.0%** | 25.9% → 35.2% |
| pos-catalog | 73.1% → **77.4%** | 51.5% → 53.5% |
| pos-shop-manager | 67.5% → **83.2%** | 60.3% → **67.1%** |
| pos-workorder | 73.1% → **76.6%** | 55.1% → 56.7% |
| pos-tax-common | no suite → **34.0%** | — → 46.4% |
| pos-mcp-server | 78.5% → **80.2%** | 67.5% → 68.5% |

**Wave 1e is now closed reactor-wide**: the listener families in catalog (2),
shop-manager (6), and workorder (4) were the last Kafka listeners at 0%, all
covered with the parameterized contract-test shape. pos-workorder's
`PeopleReplicaEventsListener` is the notable one — a single component on two
topics stamping a different `processed_events` owner per entry point, pinned
because the wrong owner would corrupt both reconciliation windows at once.

**Defect found (pos-tax-common):** `SubdivisionForCountryValidator` rejects every
real Canadian province — the `i18n-subdivision-enums` dataset has no CA data and
there is no CA fallback list (the US has one). A valid Canadian `TaxAddress`
gets a 400. Pinned by `canadianProvincesAreRejected` as documented current
behaviour. Two more `locationCommandsTopic` copy-paste field names found
(catalog's inventory manifest listener, matching pos-invoice's workorder one);
routing correct in both, noted in test comments.

**Still open after this pass:** the §5 Failsafe IT layers for `pos-invoice` and
`pos-warranty` (a different shape of work: DB-backed, `verify`-phase);
controller `@WebMvcTest` slices across vehicle-inventory, customer, marketing,
people-contact; the service-impl branch tails in catalog/workorder/mcp-server;
and the four small 0% modules (§4 filler list). Then Phase 4's ratchet, which
requires the full-reactor re-measure first.

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


## 6. Phase 4 — the ratchet (delivered 2026-08-12)

### 6.1 Authoritative baseline

Full-reactor `verify` including Failsafe ITs, per the Phase 0 command, on `main`
at `683cc6381`. **These are each module's own report** — the figure a per-module
JaCoCo `check` actually reads — not the aggregate, which credits shared
libraries with their consumers' coverage (§1.5).

Repo-wide, summing all 38 per-module reports: **81.1% line, 66.6% branch**
(14,295 missed of 75,661 lines). Against the §1.5 baseline of 72.6% / 59.5% /
20,265 missed, that is **+8.5 points line and ~6,000 fewer missed lines**.

| Module | Lines | Line% | Branch% | `jacoco.line.min` | `jacoco.branch.min` |
|---|---:|---:|---:|---:|---:|
| `pos-accounting` | 10516 | 85.8 | 72.5 | 0.80 | 0.67 |
| `pos-inventory` | 10056 | 84.6 | 68.3 | 0.79 | 0.63 |
| `pos-workorder` | 7465 | 76.6 | 56.7 | 0.71 | 0.51 |
| `pos-mcp-server` | 6153 | 80.2 | 68.5 | 0.75 | 0.63 |
| `pos-customer` | 5362 | 85.9 | 70.6 | 0.80 | 0.65 |
| `pos-security-service` | 3504 | 81.8 | 71.1 | 0.76 | 0.66 |
| `pos-invoice` | 3308 | 78.9 | 65.3 | 0.73 | 0.60 |
| `pos-order` | 3237 | 83.6 | 67.7 | 0.78 | 0.62 |
| `pos-people` | 2859 | 79.4 | 63.6 | 0.74 | 0.58 |
| `pos-catalog` | 2807 | 77.4 | 53.5 | 0.72 | 0.48 |
| `pos-warranty` | 2562 | 90.4 | 80.7 | 0.85 | 0.75 |
| `pos-supplier` | 2351 | 88.0 | 75.3 | 0.82 | 0.70 |
| `pos-location` | 2167 | 81.4 | 70.1 | 0.76 | 0.65 |
| `pos-shop-manager` | 1933 | 83.2 | 67.1 | 0.78 | 0.62 |
| `pos-bulk-loader` | 1770 | 76.8 | 65.3 | 0.71 | 0.60 |
| `pos-people-contact` | 1477 | 65.2 | 52.3 | 0.60 | 0.47 |
| `pos-marketing` | 1236 | 87.9 | 82.9 | 0.82 | 0.77 |
| `pos-price` | 1053 | 94.9 | 81.7 | 0.89 | 0.76 |
| `pos-tax` | 984 | 78.5 | 66.8 | 0.73 | 0.61 |
| `pos-vehicle-inventory` | 977 | 66.4 | 71.0 | 0.61 | 0.66 |
| `pos-vehicle-fitment` | 542 | 78.4 | 63.6 | 0.73 | 0.58 |
| `pos-domain-events` | 511 | 39.3 | 37.2 | 0.34 | 0.32 |
| `pos-event-receiver` | 437 | 77.3 | 87.0 | 0.72 | 0.81 |
| `pos-api-gateway` | 435 | 85.5 | 72.2 | 0.80 | 0.67 |
| `pos-security-common` | 406 | 46.8 | 36.5 | 0.41 | 0.31 |
| `pos-documents` | 383 | 75.2 | 52.9 | 0.70 | 0.47 |
| `pos-vehicle-reference-nhtsa` | 189 | 0.0 | 0.0 | — *(unguarded)* | — |
| `pos-events` | 174 | 59.8 | 57.1 | 0.54 | 0.52 |
| `pos-openapi-validation` | 171 | 93.6 | 82.3 | 0.88 | 0.77 |
| `pos-document-helper` | 162 | 95.7 | 90.0 | 0.90 | 0.80 |
| `pos-tax-common` | 100 | 34.0 | 46.4 | 0.29 | 0.41 |
| `pos-vehicle-reference-carapi` | 69 | 0.0 | 0.0 | — *(unguarded)* | — |
| `pos-image` | 61 | 0.0 | 0.0 | — *(unguarded)* | — |
| `pos-shared-dtos` | 34 | 0.0 | 0.0 | — *(unguarded)* | — |
| `pos-inquiry` | 16 | 0.0 | — | — *(unguarded)* | — |

`pos-archunit`, `pos-bulk-ingest-lib`, and `pos-service-discovery` are omitted
from the table — 3–4 lines each, nothing to guard. `pos-inquiry` (16 lines, 0%)
is listed but unguarded, with the other all-zero modules.

**Caveat on the aggregate figure.** The run also produced
`jacoco-aggregate/jacoco.xml` reading 81.7% line / 67.6% branch, but over **32 of
38 groups**: recovering from a mid-run failure required `mvn install -DskipTests`
on the shared libraries, which wiped their `jacoco.exec` and dropped them from
the merge. A subsequent isolated re-run of the aggregate goal then overwrote that
XML with an empty one. The aggregate is therefore **not** currently trustworthy on
disk; CI regenerates it on every merge to `main`, and that is the copy to wire
into SonarCloud (§6.3). Nothing in §6.1's per-module table depends on it.

### 6.2 How the ratchet works

Root `pom.xml` gains a `check-ratchet` execution on the `verify` phase asserting
`LINE` and `BRANCH` `COVEREDRATIO` against two properties, defaulted to `0.00`:

```xml
<jacoco.line.min>0.00</jacoco.line.min>
<jacoco.branch.min>0.00</jacoco.branch.min>
```

Each module overrides them in its own `pom.xml`, set **~5 points below its
measured baseline** — enough to catch a real regression, loose enough not to fail
on the noise of a refactor moving a few lines. `haltOnFailure` is true, so a
breach fails the build:

```
Rule violated for bundle pos-tax-common: lines covered ratio is 0.34,
but expected minimum is 0.95
```

**`-DskipTests` is safe, and no `skip` guard is wired.** JaCoCo's `check` goal
no-ops when there is no execution data — `Skipping JaCoCo execution due to
missing execution data file` — so `clean install -DskipTests` passes, and an
`install -DskipTests` over a stale `jacoco.exec` evaluates that prior data and
also passes. Both were verified on this tree. A `<skip>${skipTests}</skip>` guard
was considered and rejected: it would buy nothing over the goal's own behaviour
while turning `-DskipTests` into a documented switch for bypassing the ratchet.
The one narrow edge is a *partial* `jacoco.exec` left by an interrupted test run,
where a later `install -DskipTests` would judge incomplete data — `clean` fixes
that, and CI always runs tests.

**Working rules.**

- Raise a module's floor when its coverage rises — that is what makes it a
  ratchet rather than a fixed bar.
- Never lower a floor without saying why in the commit message.
- The four all-zero modules (`pos-image`, `pos-inquiry`,
  `pos-vehicle-reference-carapi`, `pos-vehicle-reference-nhtsa`) carry **no**
  floor. A `0.00` floor is not a gate, and pretending otherwise would misrepresent
  them as guarded. They need first tests, not thresholds — see §7.
- Deliberately **not** a single repo-wide bar. At 81.1% a uniform 85% would fail
  a third of the reactor on day one, and a uniform 70% would let the best modules
  rot 15 points before anyone noticed.

### 6.3 SonarCloud — already wired (correction)

An earlier draft of this section claimed the Sonar step still needed wiring. That
was wrong: `.github/workflows/ci.yml` already uploads every module's
`**/target/site/jacoco/jacoco.xml` as the `jacoco-coverage-reactor` artifact,
downloads it in the Sonar job, and imports it via
`-Dsonar.coverage.jacoco.xmlReportPaths=${{ github.workspace }}/sonar-coverage/**/jacoco.xml`
(absolute by necessity — the scanner resolves a relative value against each
module's own base directory). The aggregate XML is uploaded separately as
`jacoco-coverage-aggregate`.

Importing the **per-module** reports is also the right choice, and matches the
ratchet: Sonar sees each module's own coverage rather than the aggregate's
consumer-credited figure for shared libraries. No work is outstanding here.

## 7. What is still open

1. ~~**Wave 1c** (`{Module}PermissionRegistry`, §3.3)~~ — closed 2026-08-12, see §7.1.
2. ~~**Controller `@WebMvcTest` slices**~~ — closed 2026-08-12, see §7.3.
3. ~~**The four all-zero modules**~~ — closed 2026-08-12, see §7.2.
4. ~~**Branch-coverage tails**~~ — closed 2026-08-12 over two passes, see §7.4 and
   §7.5. `pos-document-helper` is resolved by deletion rather than by tests: its
   35.2% was the untested duplicate under `com.positivity.documents.helper`,
   removed in #1274, leaving the module at 90.0% branch on the surviving
   `com.positivity.documents` copy.
5. **Low-coverage shared libraries** — `pos-tax-common` 34.0%,
   `pos-domain-events` 39.3%, `pos-security-common` 46.8% on their own tests.
   They read far higher in the aggregate because consumers exercise them; their
   own floors are set from the honest per-module number.
6. ~~SonarCloud wiring~~ — already in place; see the §6.3 correction.

### 7.1 Wave 1c closed (2026-08-12)

`pos-marketing` and `pos-inventory` now have a `{Module}PermissionRegistry` test
each. Both read `permissions.yaml` directly (no YAML dependency — the file is
scanned for `- name:` lines) and assert three things about the registry: every
constant matches `domain:resource:action` in snake_case, no name is declared
twice, and the constants and the catalog agree with each other.

The third assertion could not take the same form in both modules, and the
difference is worth recording:

- **pos-marketing** — constants and catalog correspond one-to-one, so the test
  is bidirectional: no constant missing from the catalog, no catalog entry
  without a constant.
- **pos-inventory** — the catalog holds 54 entries against 29 constants, so a
  bidirectional test fails by design. The orphan half was replaced with
  `everyEnforcedAuthorityIsRegistered`, which scans `src/main/java` for
  `hasAuthority("…")` string literals and asserts each one is in the catalog.
  That is the assertion that actually protects production: a `@PreAuthorize`
  naming an authority nobody registered is an endpoint no role can ever reach.
  It found 46 literal-enforced authorities, all present.

The 25 catalog entries with no constant are not a defect — they are permissions
declared ahead of the code that will enforce them. A test that failed on them
would be a test that punishes planning.

### 7.2 The all-zero modules closed (2026-08-12)

| Module | Line before | Line after | Branch after | Floor set |
|---|---|---|---|---|
| `pos-vehicle-reference-carapi` | 0% | 78.3% | 88.9% | 0.73 / 0.83 |
| `pos-vehicle-reference-nhtsa` | 0% | 49.7% | 50.0% | 0.44 / 0.45 |
| `pos-image` | 0% | 31.1% | 100.0% | 0.26 / 0.95 |
| `pos-inquiry` | 0% | 0% | — | none |

`pos-inquiry` was deliberately left alone. It is 16 lines of application
scaffolding with no behaviour of its own — no controller, no service logic. A
test dependency was added and then reverted rather than shipping a test that
asserts the Spring context can start, which pins nothing. It gets a floor when
it gets behaviour.

`pos-image`'s line figure is held down by configuration classes; the controller
itself — the only part with logic — is fully covered, which is why its branch
floor is high and its line floor is low. The tests pin that a database row whose
file is missing from disk returns 404 rather than a 500 or a stream that dies
mid-response. That is the normal state after a restore or a volume remount, not
an exotic one.

The two vehicle-reference modules are 24-hour read-through caches over
third-party APIs (CarAPI, and NHTSA's public vPIC). The tests pin the cache
contract in both directions — fresh cache serves without any outbound call,
stale cache refetches and replaces — and, for NHTSA, the derivation
`UUID.nameUUIDFromBytes("make-" + vpicId)` that makes a refetch update rows
instead of duplicating them.

Writing them surfaced a live defect, filed as
louisburroughs/durion-positivity-backend#1265 and since fixed — see below.

### 7.3 Controller web slices closed (2026-08-12)

| Module | Line before | Line after | Branch after | Floor |
|---|---|---|---|---|
| `pos-vehicle-inventory` | 66.4% | 76.7% | 74.1% | 0.71 / 0.69 |
| `pos-marketing` | 87.4% | 89.7% | 82.9% | 0.84 / 0.77 |
| `pos-people-contact` | 64.1% | 70.1% | 55.9% | 0.65 / 0.50 |
| `pos-customer` | 85.4% | 86.3% | 71.5% | 0.81 / 0.66 |

The slices reuse each module's existing web-slice security config where one
existed; `pos-marketing` needed its first, modelled on `pos-inventory`'s.
Authorities arrive on `X-Authorities` exactly as the gateway supplies them
(ADR-0011/0014), which is what makes per-endpoint permission assertions possible
without standing up the gateway.

**What these tests are for.** Three recurring shapes turned out to carry the risk,
and they are worth naming because they recur across the reactor:

1. *Sibling permissions on adjacent methods.* `pos-marketing`'s campaign
   lifecycle splits across five authorities (create, edit, schedule, manage,
   send) on methods that differ by one word. Nothing structural enforces the
   split — a copy-paste between two of them is invisible after the fact. Each
   action is therefore checked against a neighbouring authority that must *not*
   open it, rather than only against the one that should.
2. *Two implementations of one interface.* `pos-customer`'s `CustomerController`
   holds two `CustomerService` fields, commercial and individual, assigned in the
   opposite order to the constructor's parameters. Correct today; still compiles
   if swapped, and a swap answers every question about the wrong kind of
   customer. Same shape in `pos-people-contact`'s `PostalAddressController`,
   where person and organization endpoints differ by one word in the path, one in
   the `@PreAuthorize`, and one enum constant.
3. *Verbs that disagree about null.* `pos-vehicle-inventory`'s preferences PUT
   and PATCH both accept a null `serviceIntervalMonths` and mean opposite things
   by it (#1175) — clear the override versus leave it alone. Both arrive as an
   absent JSON field, so the verb is the only thing carrying the distinction.

`pos-people-contact`'s `PeopleExceptionHandler` was the largest single uncovered
class in the whole wave (58 missed lines) and is now covered exhaustively. It is
the module's entire error contract — twenty near-identical four-line handlers
where a wrong `HttpStatus` still compiles and still returns a well-formed
ProblemDetail.

Two defects were found and filed by this wave (#1269 and #1270); both have since
been fixed — see below. The two tests that pinned them were written to fail
loudly once the behaviour changed, and are now ordinary assertions.

### 7.4 Branch tails, first pass (2026-08-12)

| Module | Branch before | Branch after | Line after | Floor |
|---|---|---|---|---|
| `pos-catalog` | 53.5% | 57.6% | 78.6% | 0.73 / 0.52 |
| `pos-workorder` | 56.7% | 58.5% | 78.2% | 0.73 / 0.53 |
| `pos-document-helper` | 35.2% | 35.2% (90.0% after #1277) | 74.0% | 0.90 / 0.80 after #1277 |

Attacked the two worst individual classes rather than spreading thinly, on the
view that a branch tail is not uniform — it concentrates in the places where the
code makes a choice the caller cannot see it make.

**`PriceBookServiceImpl.resolvePrice`** (47.1% → 68.8% branch, 90 → 53 missed).
This is the read that decides what a product costs, and almost every branch in it
is silent. It walks two independent precedence chains — which price book applies,
then which rule inside it wins — then falls back to MSRP and finally to "no
price". Every step produces a well-formed answer, so an error does not fail, it
quotes a different number. Only `source` and `fallbackReason` distinguish "this
is the contract price" from "this is list price because nothing matched", and
nothing downstream re-derives them. The tests assert the winning rule and the
source, not that a price came back. Specifics worth keeping:

- Target specificity (SKU > CATEGORY > GLOBAL) is scored before priority, so
  priorities in the fixtures are set to contradict the expected winner.
- `nullsLast` on a reversed comparator decides whether an unprioritised rule
  outranks every deliberately prioritised one; both directions are asserted.
- A fully tied pair is broken by rule id, asserted with the inputs supplied in
  reverse — a tie that resolved by input order would have two servers quoting
  different prices for the same basket.
- Currency selection refuses to guess: an unconfigured requested currency and an
  ambiguous multi-currency rule are both errors, because substituting either
  would hand the caller a number in the wrong denomination.
- Zero and negative amounts are rejected. A rule resolving to nothing or to a
  credit is a data error, and letting it through prices the product at that
  value.

**`WorkorderPickFacadeServiceImpl`** (0% → 100% branch). Turns warehouse scans
into asynchronous inventory commands (ADR-0044, #901). Two families of branch:
the four-way scan grade (MATCHED / SKU_MISMATCH / LOCATION_MISMATCH / NO_MATCH),
whose two mismatch cases are decided by opposite comparisons and are trivially
swappable — a picker acts on that word, and it tells them whether they are at the
wrong bin or holding the wrong part; and the refusal to publish half-formed
commands, which fails closed twice over with a 409 for an incomplete replica and
a 503 for an absent publisher or a broker nack. Those two must stay
distinguishable: a 409 cannot be fixed by retrying, a 503 is exactly what should
be retried.

**`pos-document-helper` is deliberately untouched.** Its entire branch tail — 32
of 35 missed branches — sits in a duplicated copy of the library that no module
consumes (#1274). Testing it would cement a deletion candidate and make the
number look healthy while the duplication stayed. Its floor is unchanged pending
that decision. **Resolved since:** the decision on #1274 kept
`com.positivity.documents` and deleted the `helper` copy, which took the whole
branch tail with it — the module now measures 95.7% line / 90.0% branch and its
floors are 0.90 / 0.80.

### 7.5 Branch tails, second pass (2026-08-12)

| Module | Branch (start of §7) | After pass 1 | After pass 2 | Line | Floor |
|---|---|---|---|---|---|
| `pos-catalog` | 53.5% | 57.6% | **59.7%** | 80.3% | 0.75 / 0.54 |
| `pos-workorder` | 56.7% | 58.5% | **60.4%** | 78.7% | 0.73 / 0.55 |

Same approach as pass 1: the next-worst class in each module, chosen for what its
branches decide rather than for how many there are.

**`ChangeRequestServiceImpl`** (29.5% → 62.1% branch, 93 → 50 missed). A change
request is how extra work gets added to a job the customer already agreed to, so
these branches decide whether someone can be billed for work they never
approved. Two rules carry that:

- *Emergency items must carry evidence.* An emergency/safety item skips the
  normal approval wait, so it needs a photo, or an explicit "photo not possible"
  plus notes. The two checks overlap — the second catches an item that claimed
  photo-not-possible and then supplied nothing — and collapsing them into one
  would let a shop mark anything as an emergency with no record of why. The full
  evidence truth table is asserted, whitespace-only values included on both
  sides.
- *A declined emergency blocks closing the workorder* until the customer has
  acknowledged the denial, on every emergency item, across services **and**
  parts. That is the paper trail for "we told them it was unsafe and they said
  no". The services and parts checks are separate methods with identical bodies,
  so both halves are asserted independently: dropping the parts one leaves the
  services test green.

Also pinned: only a workorder actually in `WORK_IN_PROGRESS` accepts a change
request, checked against every other status via `@EnumSource` exclusion rather
than one sample, because adding work to a job that is finished, invoiced or
cancelled is exactly the case that produces an unagreed bill.

**`ProductDetailServiceImpl`** (37.7% → 54.4% branch, 71 → 52 missed). This view
stitches the catalog row together with live pricing from pos-price and live
availability from pos-inventory, and its whole design is graceful degradation:
when a remote call fails the endpoint still answers, with the parts it could get
and a `confidence` saying how much of it is real. That makes the failure branches
the *normal* operating mode during any partial outage, and invisible from a happy
path because the response still looks complete. The full confidence matrix is
asserted (both up → HIGH, either → MEDIUM, neither → LOW), along with:

- A remote answering successfully with no data is treated exactly like an
  outage — not as a price of zero and not as an error to propagate.
- Null amounts stay null. The BigDecimal-to-double conversions are null-guarded
  on both fields; without the guards this is a NullPointerException, and with a
  naive default it is a free product.
- Unparseable enum values from the wire (`source`, `confidence`) degrade to a
  default rather than throwing, so a new constant added upstream cannot take this
  endpoint down — and an unreadable confidence degrades to MEDIUM, never HIGH.
- A lead-time lookup that throws falls back to the catalog hint while leaving
  availability itself reported as OK, since letting that exception escape would
  turn a working stock read into an outage.

Both modules still have tails below their new floors — the next candidates are
`SupplierItemCostServiceImpl` (44 missed) and `EstimateServiceImpl` (82) — but
item 4's stated goal, moving the three named modules off their branch floors with
parameterized tests over real decisions, is met.

### 7.6 Shared libraries closed (2026-08-13)

| Module | Line before | Line after | Branch before | Branch after | Floor |
|---|---|---|---|---|---|
| `pos-tax-common` | 34.0% | **90.0%** | 46.4% | **75.0%** | 0.85 / 0.70 |
| `pos-security-common` | 46.8% | **79.6%** | 36.5% | **80.7%** | 0.74 / 0.75 |
| `pos-domain-events` | 39.3% | **87.1%** | 37.2% | **82.9%** | 0.82 / 0.77 |

**`pos-security-common` is the trust boundary**, so it went first.
`SecurityContextHelper` (65 lines, 0%) is how every downstream service learns who
the caller is; the tests hold down that it *fails loudly* rather than falling
back — absent, unauthenticated and anonymous contexts all throw, because a helper
returning an empty authority set would make "no security context" look identical
to "this user lacks a permission". Anonymous is checked explicitly: Spring marks
those tokens authenticated, so without the principal check an unauthenticated
request would be written into audit rows as a user named "anonymous".
`PermissionManifestLoader` (44 lines, 0%) fails startup on every malformed input,
which is the right outcome — a partially registered manifest brings the service
up with a few endpoints unreachable for everyone. And the JWT half of
`GatewayAuthoritiesFilter` had no tests at all: it parses the payload without
verifying the signature (the gateway already did), so every malformed token has
to be handled defensively there, and its failure policy is asymmetric — an
unreadable token drops the userId but keeps the authorities, so a regression
produces audit rows with no author rather than a failed request.

**`pos-domain-events` was closed with one sweep instead of sixty test classes.**
Around sixty near-identical records, thirteen with tests. `DomainEventContractTest`
enumerates them off the classpath and asserts what must hold for all: EVENT_TYPE
shape, EVENT_TYPE uniqueness across the module (only visible from the whole set —
a duplicate delivers one fact to another's listener), a positive SCHEMA_VERSION,
and that every record constructs. Four records with cross-field invariants are
named and excluded rather than filtered by a heuristic, and the two of those that
had no test now have one.

Two things the sweep corrected in my own assumptions, worth recording because
they change what the test can claim:

- `@NonNull` in this module is **jspecify** — static analysis, no runtime effect.
  Only 37 of 64 records add explicit guards. An earlier draft asserted universal
  null rejection and produced 62 failures; that was asserting a rule that does
  not exist. The test now verifies guards where they exist and holds a floor on
  how many records have them.
- There are **two** `BillingRulesUpdatedV1` classes, in `invoice` and `customer`.
  Any allowlist here has to key on the fully-qualified name.

**`pos-tax-common`** is enums and one DTO — the wire contract between pos-tax and
its callers. Deliberately kept dependency-free: the module ships
`jackson-annotations` only, so rather than adding databind to a shared library the
serialization contract is asserted through the annotations themselves plus
`fromValue`. Jurisdiction resolution is pinned as case-insensitive but exact:
`"state "` and `"STATES"` are rejected rather than resolving to a neighbouring
level of government.

One defect filed: #1279.

### Defects found by this work, still open

- louisburroughs/durion-positivity-backend#1245 — a partial customer update
  silently deletes every VIN on the party.
- louisburroughs/durion-positivity-backend#1246 — `CommercialPartyServiceImpl`
  transposes `legalName` and `displayName` between read and write.
- louisburroughs/durion-positivity-backend#1254 — `SubdivisionForCountryValidator`
  rejects every real Canadian province.
- louisburroughs/durion-positivity-backend#1255 — replica listeners silently drop
  events whose payload omits a primitive field (Jackson 3).
- ~~louisburroughs/durion-positivity-backend#1265~~ — closed 2026-08-13.
  `pos-vehicle-reference-nhtsa` inverted its own 24h cache: `isCacheExpired`
  computed "is still fresh", and three of six call sites negated it, so those
  methods called vPIC on every request while the cache was warm and then served
  permanently frozen rows once it was not. The helper is now `isCacheFresh` in
  both vehicle-reference modules, with every call site unnegated — including
  `pos-vehicle-reference-carapi`, where the two inversions had cancelled out and
  the behaviour was correct only by accident.
- ~~louisburroughs/durion-positivity-backend#1269~~ — closed 2026-08-13.
  `pos-vehicle-inventory` had no `@ControllerAdvice` at all, so a wrong-length VIN
  on `@Validated` `GET /vin/{vin}` raised `ConstraintViolationException` and
  surfaced as 500, and no error from the module carried the `ApiError` envelope
  required by `docs/ERROR_ENVELOPE.md`. `VehicleExceptionHandler` now maps
  constraint violations, body validation, `EntityNotFoundException` and
  `IllegalArgumentException` into the envelope, and the hand-rolled `try/catch`
  blocks in `VehicleRegistryController` and `VehicleController` are gone.
- ~~louisburroughs/durion-positivity-backend#1270~~ — closed 2026-08-13.
  `POST /v1/vehicles/search` was broken for every request: `SearchVehiclesRequest`
  was `@Builder` with final fields and no `@Jacksonized`, so Jackson had no
  creator and message conversion failed before the controller was entered. The GET
  route builds the object in Java, which is why nothing caught it. `@Jacksonized`
  now wires the builder up as the creator. A sweep for the same shape — a
  `@Builder` class with no Jackson creator used as a `@RequestBody` — found no
  other instance in the reactor.
- ~~louisburroughs/durion-positivity-backend#1274~~ — **fixed** by PR #1277, which
  deleted the duplicate `com.positivity.documents.helper` tree. The module went
  from 35.2% to 90.0% branch coverage by deletion rather than by testing; see
  §7.4.
- louisburroughs/durion-positivity-backend#1279 — 16 of 64 event records in
  `pos-domain-events` declare no `SCHEMA_VERSION`, so their publishers hardcode
  the envelope version in another module. Correct today, but a schema bump now
  requires editing a numeric literal nowhere near the record it describes.
- louisburroughs/durion-positivity-backend#1267 — `pos-vehicle-reference-carapi`
  conflates its own primary key with CarAPI's make id inside one method, so no
  argument to `GET /models/{makeId}` is correct for all three of its uses; the
  `CarApiModelResponse.makeId` field also contradicts its own schema. Raised by
  Copilot on PR #1266, which spotted the DTO half.
