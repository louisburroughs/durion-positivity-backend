# Backend Build Specification

Status: **Proposed** (v1.0, 2026-08-01)
Scope: CI/CD build pipeline for the `durion-positivity-backend` Maven reactor
(`.github/workflows/ci.yml`, `pr-checks.yml`, `build-push-ecr.yml`) and the local build entry points.

This spec is the result of analysing a set of external build recommendations (single-reactor
invocation, reactor parallelism, JUnit parallelism, prebuilt-dependency sharing, Maven Build Cache
Extension, dependency-cache hygiene, build-once/deploy-once) against the pipeline as it exists
today. Each recommendation is dispositioned in §3; the target architecture is specified in §4;
the implementation plan is in §6.

---

## 1. Current state (baseline)

Facts that constrain the design, verified against the repo at the time of writing:

| Item | Value |
|---|---|
| Maven | 3.9.14 via wrapper (`.mvn/wrapper/maven-wrapper.properties`) — Build Cache Extension compatible |
| JDK | 25 (Temurin), `MAVEN_OPTS: -Xmx3072m` in CI |
| Modules | ~35 reactor modules (`pos-*`), most are small Spring Boot services |
| Runners | `ubuntu-latest` (4 vCPU / 16 GB) |
| Surefire/Failsafe | 3.5.5, `forkCount`/`reuseForks` parameterised via `tests.forkCount` / `tests.reuseForks` root properties; `parallel-tests` profile (`-DparallelTests=true`) sets `forkCount=1C`; `low-resource-tests` profile caps heap |
| JUnit parallelism | Not configured (no `junit-platform.properties` anywhere) |
| Build cache extension | Not installed (no `.mvn/extensions.xml`) |

Pipeline shape today (`ci.yml`):

```
detect-test-modules ──► build-reactor (install all, -T 1C, skip tests)
                              │  save ~/.m2/repository to cache key {os}-maven-reactor-{sha}
                              ▼
                        unit-tests matrix: one job PER changed module
                        (restore cache, ./mvnw -pl <module> test -o)
                              ▼
                 integration-tests matrix (main/dispatch only, same shape, `verify`)
                 security-scan / code-quality (Sonar) / compose smoke test / docker builds
```

`build-push-ecr.yml` (separate workflow, also on push to `main`): re-runs
`./mvnw -pl <changed> -am package -DskipTests`, uploads the JARs as a `service-jars` workflow
artifact, and fans out one Docker build-and-push job per service consuming that artifact.

### 1.1 What already works

- **Change detection** exists and gates the pipeline (`detect-test-modules`), with a
  full-reactor fallback when `pom.xml`, `ci.yml`, or `docker-compose.yml` change.
- **Prebuilt reactor sharing** exists: `build-reactor` installs everything once and matrix jobs
  restore `~/.m2/repository` from an SHA-keyed cache and run offline (`-o`). This is the intent of
  recommendation 4, implemented with `actions/cache` instead of workflow artifacts.
- **Build-once between jobs of the ECR workflow**: JARs are built once and passed to the Docker
  matrix via a workflow artifact.
- **Reactor parallelism** (`-T 1C`) is already used on the install and per-module test commands.
- The **pos-archunit constraint** is understood and encoded: its cross-module rules need sibling
  `target/classes` on the classpath, so it must build as an `-am` reactor stopped at the `test`
  phase — running it past `package` lets `spring-boot:repackage` swap sibling JARs for fat JARs
  (classes under `BOOT-INF/classes`) and every rule passes vacuously (#909).

### 1.2 Gaps

| # | Gap | Kind | Where |
|---|---|---|---|
| G1 | **Downstream dependents are not tested.** A change to `pos-shared-dtos`, `pos-events`, `pos-security-common`, etc. tests only that module (+ archunit). Dependent services are not rebuilt or re-tested until they next change. The full-reactor trigger list (`pom.xml`, `ci.yml`, `docker-compose.yml`) does not cover shared libraries, `.mvn/**`, or `pos-dependencies`. | Correctness | `ci.yml` detect-test-modules |
| G2 | **One matrix job per module** pays ~1–2 min of fixed overhead each (checkout at `fetch-depth: 0`, JDK setup, full `~/.m2` cache restore) to run test suites that are often shorter than the overhead. A wide PR spawns 20+ such jobs. | Wall-clock / cost | `ci.yml` unit-tests, integration-tests-modules |
| G3 | **Repeated full builds inside one workflow run**: the compose smoke-test job re-runs `clean package` over the whole reactor (no `-T`); `security-scan` re-runs `install`; `code-quality` re-runs `test-compile` over the full reactor for Sonar. | Wall-clock | `ci.yml` |
| G4 | **Deployed binary ≠ tested binary**: `build-push-ecr.yml` rebuilds JARs from source in a separate workflow run rather than reusing output from the run that passed tests. | Provenance | `build-push-ecr.yml` |
| G5 | Per-matrix-job repetition of repo lint scripts (`check-noarg-now.sh`, `check-flyway-hygiene.sh`) — same result computed N times. | Cost | `ci.yml` unit-tests |
| G6 | Duplicate change detection in `pr-checks.yml` (its own diff logic, CSV format) alongside `ci.yml`'s (JSON format). | Maintenance | `pr-checks.yml` |
| G7 | No JUnit intra-module parallelism; no Maven build cache; fan-out jobs are not `cache-read-only`. | Opportunity | repo-wide |

---

## 2. Design principles

1. **Correctness before speed.** G1 is fixed first; a fast pipeline that skips affected modules
   is worse than a slow one.
2. **One reactor invocation per purpose.** Maven's reactor already topologically sorts and builds
   each module exactly once; do not re-derive that with shell loops or job matrices unless a
   measured, dominant test suite justifies a shard.
3. **Bounded effective concurrency.** Total concurrency ≈ reactor threads × Surefire forks ×
   JUnit threads. Exactly one of the three multiplies per pipeline stage; the others stay at 1
   unless a measurement says otherwise.
4. **Build once, promote the artifact.** The bytes that passed the CI test lifecycle are the bytes that ship.
5. **Every optimisation is piloted behind a measurement.** Adopt only what beats the baseline on
   the recorded timings (§6.4).

---

## 3. Disposition of the recommendations

| Rec | Summary | Disposition |
|---|---|---|
| 1 | Replace per-module `-am` loop with one reactor invocation | **Adopt (adapted).** We have no `-am` loop — we have a per-module job matrix with a prebuilt reactor. The same duplicated-overhead argument applies: collapse the unit-test matrix into a single `-pl <set> -amd -am` reactor invocation (§4.2). |
| 1b | Treat `pom.xml`, `.mvn/**`, parent/BOM, shared libraries, plugins as full-reactor changes | **Adopt.** Extends the current trigger list; fixes half of G1. The other half (dependents of a changed service/lib) comes from `-amd` (§4.1). |
| 2 | Reactor parallelism `-T 1C` first, watch non-thread-safe plugins | **Already in place** for install/test commands; extend to the stragglers in G3. Keep `-T 1C`, do not raise to `2C`. |
| 3 | Class-level JUnit parallelism, fixed parallelism 2 | **Pilot, opt-in per module** (§4.4). Most tests here are `@SpringBootTest`-style with shared H2/context state; blanket enablement is unsafe. Note we already have an unused fork-level alternative (`-DparallelTests=true` → `forkCount=1C`) that is safer for Spring tests. |
| 4 | Prebuild dependencies once, share to matrix jobs via artifact | **Already implemented** via SHA-keyed `actions/cache` on `~/.m2/repository`; keep that mechanism (restore is one step and survives matrix retries). No change, except it becomes less load-bearing once the matrix collapses. |
| 5 | Maven Build Cache Extension | **Pilot on PR builds only** (§4.5); `main` joins only after the pilot passes the §4.5 validation gate. Maven 3.9.14 qualifies. Release/ECR builds stay uncached permanently. |
| 6 | Separate dependency cache from build-output cache; `cache-read-only` on fan-outs | **Adopt.** Keep `setup-java` `cache: maven` on the single writer job; all remaining fan-out jobs (Docker matrix) set `cache-read-only: true`. |
| 7 | Build once, deploy the tested artifact; prefer one `verify` over `test`+`package`+`deploy` | **Adopt in two steps** (§4.6). Step 1: a single lifecycle invocation per pipeline stage (no repeated lifecycle prefixes) — closes G3. On `main` that invocation is `install`, not `verify`: downstream jobs consume reactor output via the `~/.m2` cache, and only `install` puts reactor artifacts there (§4.3). Step 2: hand tested JARs from CI to the ECR workflow — closes G4. |

---

## 4. Target architecture

### 4.1 Change detection (single source of truth)

One reusable script, `scripts/ci/detect-modules.sh`, replacing the inline logic in `ci.yml`,
`pr-checks.yml`, and `build-push-ecr.yml`. Output: a single JSON object on stdout (workflows lift
its fields into `$GITHUB_OUTPUT`):

```json
{ "full_reactor": false, "modules": ["pos-order", "pos-price"] }
```

```json
{ "full_reactor": true, "modules": [] }
```

`modules` is the deduplicated list of changed `pos-*` module directories, empty when
`full_reactor` is `true` (the whole reactor builds, so no selection is meaningful). Consumers
must branch on `full_reactor`, never on whether `modules` is empty — an empty list with
`full_reactor: false` means "nothing to build" (docs-only change) and skips the build entirely.

**Full-reactor triggers** (any changed path matching):

```
pom.xml                      # root parent
.mvn/**                      # wrapper, maven.config, extensions.xml
pos-dependencies/**          # internal BOM
build-tools/**               # shared build plugins/config
.github/workflows/ci.yml
```

Everything else resolves to the set of changed `pos-*` module directories. The script does **not**
compute the downstream closure itself — that is delegated to Maven
(`-pl "$CHANGED_MODULES" -am -amd`, selective mode only; see §4.2 for both command forms):

- `-am` builds upstream dependencies of the selection (each exactly once, reactor-ordered).
- `-amd` adds downstream dependents, so a change to `pos-shared-dtos` compiles and tests every
  service that consumes it. **This closes G1** and removes the need to enumerate shared libraries
  in the trigger list (they stay listed anyway as belt-and-braces).

In selective mode, `pos-archunit` is appended to the selection (current behaviour, preserved);
in full-reactor mode it is already part of the reactor and no selection exists to append to.

### 4.2 PR pipeline (the common path)

```
detect-modules
      │
      ▼
build-and-unit-test          ONE job, one reactor invocation
      │
      ▼
quality gates (Sonar PR scan)  [unchanged]
```

The single job runs the lint/policy checks once (not per matrix leg — closes G5), then **three
reactor invocations**:

```bash
./scripts/check-noarg-now.sh
./scripts/check-flyway-hygiene.sh

# 1. Full-reactor install, tests skipped (~2 min warm — measured baseline).
./mvnw -B -ntp -T 1C install -DskipTests -DskipITs -Darchunit.skipTests=true

# 2. Selective tests: changed modules plus every dependent (closes G1).
#    full_reactor=true → same command without -pl/-amd; empty selection → skipped.
./mvnw -B -ntp -T 1C \
  -pl "$CHANGED_MODULES" -amd \
  -DskipTests=false test \
  org.jacoco:jacoco-maven-plugin:0.8.14:report \
  -Darchunit.skipTests=true \
  -DskipITs

# 3. ArchUnit rules: -am reactor stopped at the test phase (#909-safe).
./mvnw -B -ntp -T 1C -pl pos-archunit -am -DskipTests=false test \
  -Dtest='com/positivity/archunit/*' -Dsurefire.failIfNoSpecifiedTests=false -DskipITs
```

**Why three invocations and not the single selective one originally sketched here** (implementation
finding, phase 2): Maven's `-amd` adds the *dependents* of the selection to the reactor but not
those dependents' own upstream dependencies, so a purely selective `-pl <changed> -am -amd` build
cannot resolve on a clean runner (verified: `-pl pos-shared-dtos -am -amd` yields a 29-project
reactor that contains `pos-order` but not `pos-order`'s dependency `pos-events`). And
`pos-archunit` declares dependencies on ~22 modules, so any dependent-closure that includes it is
the full reactor anyway. The cheap full `install -DskipTests` (step 1) makes every internal
artifact resolvable from `~/.m2`, after which the selective `-amd` test pass and the archunit
reactor are both correct. Step 1 is the same build the old `build-reactor` job ran — the savings
come from eliminating the per-module matrix jobs, not from skipping compilation.

Design notes:

- **`test`, not `verify`, on PRs — deliberately.** Failsafe ITs already run only on `main`
  push / dispatch (current policy, kept). `-Darchunit.skipTests=true` is set on invocations 1
  and 2: past the `package` phase (and against installed fat JARs) the ArchUnit rules pass
  vacuously (#909), so they run only in invocation 3 where siblings resolve to `target/classes`.
- `-amd` may pull in a large dependent set for shared-library changes. That is the point (G1).
  For a full-reactor trigger, `-pl` is omitted entirely.
- The separate `build-reactor` job disappears (its install moved into this job); the SHA-keyed
  reactor cache is **not saved on the PR path** (no separate job consumes it). `setup-java`'s
  `cache: maven` keeps external dependencies cached, keyed on `**/pom.xml`.
- Checkout uses `fetch-depth: 0` only in `detect-modules`; the build job checks out at depth 1.
- Memory budget: `-T 1C` on a 4-vCPU runner = up to 4 concurrent module builds, each with
  `forkCount=1` reused test JVM. `MAVEN_OPTS: -Xmx3072m` for the reactor JVM; test JVMs inherit
  defaults (bound via `tests.jvmArgs` / `low-resource-tests` if OOM appears). Concurrency
  product: 4 × 1 × 1.

**Escape hatch — matrix shards.** If measurement (§6.4) shows one or two modules dominating
wall-clock (e.g. a 10-minute suite serializing the whole job), those specific modules — and only
those — move to shard jobs that restore the prebuilt reactor (re-enabling the §1.1 cache
mechanism) and run `-pl <module> test -o`. Shards are balanced by recorded execution time, not by
module count. This is a measured exception, not the default shape.

### 4.3 Main pipeline (push to `main`)

```
detect-modules
      │
      ▼
build-install                same job shape as the PR path (§4.2), three invocations:
      │                       1. full-reactor `install -DskipTests` (populates ~/.m2,
      │                          packages every service JAR)
      │                       2. `-pl <changed> -amd verify` — unit + failsafe ITs for
      │                          changed modules and dependents
      │                       3. pos-archunit rules at the test phase (#909-safe)
      │        ├─ upload service JARs as workflow artifact (tested bytes, all services)
      │        ├─ save ~/.m2/repository to SHA-keyed reactor cache
      │        └─ upload surefire/failsafe/jacoco reports
      ▼
compose-smoke │ security-scan │ sonar    — all consume the build-install output; none re-run
      ▼                                    package/install from scratch (closes G3)
docker-build-and-push        matrix over changed services, consuming the JAR artifact (§4.6)
```

The full-reactor step-1 install (see §4.2 for why it is required) has a main-path bonus: every
service JAR exists for every main commit, so whole-stack deploy tags (ECR `BACKEND_TAG`) can
always be satisfied from the artifact regardless of how narrow the triggering change was.

- **`install`, not `verify`, on `main` — deliberately.** `verify` runs every test and packages
  the JARs but stops one phase short of copying reactor artifacts into `~/.m2/repository`, so an
  `~/.m2` cache saved after a `verify` run would not contain the just-built internal modules and
  downstream jobs could not resolve them (they would silently fall back to stale snapshots or
  fail offline). `install` is the cheapest way to make the reactor output consumable by later
  jobs through the existing cache mechanism; it adds only a local file copy per module on top of
  `verify`, and it is exactly what today's `build-reactor` job does. (The alternative — uploading
  `target/` trees or the internal `com/positivity` subtree of `~/.m2` as workflow artifacts — is
  rejected as a second transport mechanism with no benefit over the cache.)
- On `main` the full lifecycle is required (failsafe ITs, packaged JARs). Because packaging
  repackages siblings into fat JARs, **pos-archunit runs as invocation 3 inside the same job**
  at the `test` phase (`-am` builds siblings from source, so their classes resolve from
  `target/classes`) — the one place the #909 special case survives, matching today's proven
  pattern without paying a separate job's overhead.
- Jobs that only need compiled/packaged output (Sonar, compose smoke test, OWASP) restore the
  SHA-keyed reactor cache written by `build-install` instead of rebuilding (the cache mechanism
  from §1.1 stays, relocated to the main path where multiple consumers still exist).

### 4.4 Test parallelism (pilot, opt-in)

Two independent knobs, introduced in this order, never multiplied blindly:

1. **Fork-level (already available, unused):** `-DparallelTests=true` activates the existing
   `parallel-tests` profile (`forkCount=1C`, reused forks). Safe for Spring tests (each fork is an
   isolated JVM with its own context cache) but memory-expensive: 4 forks × Boot context ≈ several
   GB. Only viable when `-T 1` (concurrency product 1 × 4 × 1). Candidate for a dominant-module
   shard (§4.2 escape hatch), not for the `-T 1C` reactor jobs.
2. **JUnit class-level (new, per-module opt-in):** a module that has verified its tests are
   class-isolated adds `src/test/resources/junit-platform.properties`:

   ```properties
   junit.jupiter.execution.parallel.enabled=true
   junit.jupiter.execution.parallel.mode.default=same_thread
   junit.jupiter.execution.parallel.mode.classes.default=concurrent
   junit.jupiter.execution.parallel.config.strategy=fixed
   junit.jupiter.execution.parallel.config.fixed.parallelism=2
   junit.jupiter.execution.parallel.config.fixed.max-pool-size=2
   ```

   Methods within a class stay sequential. Tests sharing H2 schemas, fixed ports, static state,
   or Kafka containers must be annotated `@ResourceLock` or `@Execution(SAME_THREAD)` before the
   module opts in. Pilot modules: pure-unit-test libraries first (`pos-shared-dtos`,
   `pos-tax-common`, `pos-events`), then measure before touching any service module.

There is **no repo-wide `junit-platform.properties`**. Concurrency product with a JUnit-opted-in
module under the PR reactor: 4 × 1 × 2 = 8 runnable threads on 4 cores — acceptable because most
of the 4 reactor threads are not in test execution simultaneously; revisit if load-related
flakiness appears.

### 4.5 Maven Build Cache Extension (pilot)

`.mvn/extensions.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<extensions>
    <extension>
        <groupId>org.apache.maven.extensions</groupId>
        <artifactId>maven-build-cache-extension</artifactId>
        <version>1.2.3</version>
    </extension>
</extensions>
```

Rollout policy — the pilot scope is **PR builds only** (matching the §3 disposition); `main`
stays uncached until the pilot passes its validation gate:

| Context | During pilot | After pilot passes validation gate |
|---|---|---|
| Pull requests | **Enabled** (local cache persisted via `actions/cache`; remote cache later if warranted) | Enabled |
| `main` | **Disabled** (`-Dmaven.build.cache.enabled=false`) | Enabled, plus a scheduled weekly clean (cache-disabled) verification run |
| ECR / release builds | **Disabled always** (`-Dmaven.build.cache.enabled=false` in `build-push-ecr.yml`) | Disabled always |

Validation gate (evaluated on the PR pilot, and the precondition for touching `main`): run the
same SHA twice, confirm 100% cache restore on the second run; then mutate one file in `pos-order`
and confirm exactly `pos-order` + dependents rebuild. Known risks to audit during the pilot: generated sources (OpenAPI), Flyway resources,
`-Drevision`-stamped `maven.config` (run-number-based revision would defeat the cache — the
revision stamp must move out of hashed input or be normalised; verify with
`-Dmaven.build.cache.debug=true`). If the pilot cannot demonstrate correct invalidation, the
extension is dropped — §4.1–4.3 do not depend on it.

### 4.6 Artifact promotion (build once, deploy tested bytes)

Target end-state for `main`:

1. `build-install` (ci.yml) uploads `service-jars` (the JARs that passed the full test lifecycle) with the commit
   SHA in the artifact name.
2. `build-push-ecr.yml` converts from a parallel `push` trigger to `workflow_run` on Backend
   CI/CD success (or merges into `ci.yml` as a final stage — preferred for artifact locality),
   downloads `service-jars`, and goes straight to the Docker matrix. Its `build-jars` Maven job
   is deleted. **This closes G4**: the image contains the tested bytes, and a red CI run can no
   longer race a green ECR push for the same commit.
3. Lifecycle hygiene everywhere: no `test` + `package` + `install` chains; each pipeline stage
   invokes exactly one lifecycle target (`test` on PRs, `install` on main) and later stages consume
   its outputs.

The `sha-<short>` image-tagging scheme, per-service `scope=` BuildKit caches, and the alpha
deploy flow are unchanged.

### 4.7 Local developer commands (unchanged semantics, documented defaults)

```bash
# Test what you changed plus everything your change can break:
./mvnw -T 1C -pl pos-order -am -amd test

# Full local verify before a big PR:
./mvnw -T 1C verify
```

`CLAUDE.md`/`AGENTS.md` command examples gain `-amd` where the intent is "test my change's blast
radius".

---

## 5. Consolidation cleanups

- `pr-checks.yml` drops its private diff logic and consumes `scripts/ci/detect-modules.sh`
  (closes G6). Dockerfile lint, permission-catalog check, and the PR comment are unchanged.
- Repo policy scripts (`check-noarg-now.sh`, `check-flyway-hygiene.sh`) run exactly once per run
  (closes G5).
- All fan-out jobs that set up Java (Docker matrix) use `cache-read-only: true` so N jobs don't
  race to save near-identical dependency caches.
- `MAVEN_OPTS`/`-ntp`/`-B` and the `-Drevision` stamp move to `.mvn/maven.config` +
  workflow-level env so every invocation is uniform (subject to the §4.5 revision-stamp caveat).

---

## 6. Implementation plan

Ordered so each phase is independently shippable and measurable; a phase lands only if it beats
the recorded baseline.

| Phase | Change | Closes | Risk |
|---|---|---|---|
| 0 | **Record baseline**: per-job and per-module timings from the last 20 CI runs (queue time, setup time, test time) into `docs/build-timings-baseline.md` | — | none |
| 1 | Shared `detect-modules.sh`; add `-amd` + expanded full-reactor triggers to the existing matrix pipeline (matrix untouched) | G1, G6 | Wider PR runs for shared-lib changes — correct, but visible |
| 2 | Collapse PR unit-test matrix into the single `test`-phase reactor job (§4.2); remove archunit special-casing on the PR path; lint scripts run once | G2, G5 | Wall-clock regression on wide changes → escape hatch §4.2 |
| 3 | Main path: single `install` + downstream consumers on cache/artifacts; stop rebuilds in smoke/Sonar/OWASP jobs | G3 | Job wiring |
| 4 | Artifact promotion into ECR workflow (§4.6) | G4 | Trigger-model change (`workflow_run`/merge) needs a dry run |
| 5 | JUnit class-level parallelism pilot on 3 library modules (§4.4) | G7 | Flakiness — revert per module |
| 6 | Build Cache Extension pilot on PRs (§4.5) with the two-run validation gate | G7 | Cache correctness — hard gate, easy removal |
| 7 | Time-balanced matrix shards **only if** post-phase-2 measurements show a dominant module | — | Only added on evidence |

### 6.4 Acceptance measurements

- **Correctness:** a synthetic PR touching only `pos-shared-dtos` must run tests for its
  dependents (fails today; the release gate for phase 1).
- **PR wall-clock:** median PR pipeline time (detect → green) for 1-module, 3-module, and
  shared-lib PRs, phase 2 vs baseline. Target: ≥30% median reduction for 1–3-module PRs;
  shared-lib PRs may be slower than baseline (they were under-testing).
- **Cost:** total runner-minutes per PR run. Target: ≥40% reduction (matrix overhead removal).
- **Provenance (phase 4):** ECR image digest for a commit is produced from the `service-jars`
  artifact of the CI run for that commit — verifiable from the workflow logs.

---

## 7. Non-goals / rejected

- **`-T 2C` or higher:** rejected per recommendation 2 — memory-bound test JVMs on 4-vCPU
  runners; `1C` is the ceiling until runners grow.
- **Repo-wide JUnit parallelism:** rejected; Spring context + H2 + fixed-port coupling makes
  blanket enablement unsafe. Opt-in per module only.
- **Module-per-job matrix as the default shape:** rejected for this repo's profile (many small
  modules); retained only as a measured shard mechanism for dominant suites.
- **Remote build cache infrastructure:** deferred until the local-cache pilot proves correctness
  and shows restore-time is the bottleneck.
- **Raising `forkCount` inside `-T 1C` reactor jobs:** rejected — unbounded concurrency product.
