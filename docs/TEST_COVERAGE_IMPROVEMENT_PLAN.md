# Test Coverage Improvement Plan

Status: proposed (no tests written yet — awaiting approval)
Date: 2026-08-11
Method: `.agents/skills/test-coverage-improver` workflow, adapted from its pnpm/JS
assumptions to this Maven reactor; test authoring follows `.agents/skills/java-testing`.

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

## 4. Phase 2 — Worst-covered domain services

Ordered by missed lines in modules where we have data. Re-rank after Phase 0.

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

## 5. Phase 3 — Modules with no coverage data and thin test suites

Sequenced after Phase 0 reveals actual numbers. Provisional order by
main-class count × test-density deficit:

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
   repo-wide 80% bar — it would fail ~30 modules on day one.
2. Wire the aggregate XML into the existing SonarCloud step so the coverage
   number is visible per PR.
3. Consider excluding genuinely untestable generated code (JPA metamodel,
   OpenAPI-generated clients, `PosXApplication` mains) via
   `jacoco.excludes`. Note: commit `8dd877415` added exclusion annotations, but
   no `jacoco.excludes` / `<excludes>` configuration is present in the root
   `pom.xml` today — verify whether that work is complete.

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
