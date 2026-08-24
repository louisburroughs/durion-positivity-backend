# SonarQube Remediation Plan

Status: Phases 1, 2 and 3.1 implemented. All 34 `S6809` sites are classified —
9 were real and are fixed, 25 need no change (see §4.1). Phases 3.2–3.6 not
started.
The `new_reliability_rating` fix is in, so the next analysis should return the
quality gate to green — confirm against §7 before treating §1's table as stale.
Date: 2026-08-24
Source: SonarCloud project `louisburroughs_durion-positivity-backend`
(organisation `louisburroughs`, EU region), analysis `0.1.71f8f99-SNAPSHOT`
of 2026-08-24 06:30 UTC.
Machine-readable companion: [`sonarqube-remediation-inventory.csv`](./sonarqube-remediation-inventory.csv)
(all 267 issues in scope, one row each, ordered the same way as the phases below).

## 1. Why this plan exists, and what it is scoped to

The requested ordering is **software-quality reliability issues first, then
BLOCKER and HIGH severity issues**. That ordering happens to match what the
quality gate is asking for: the gate is currently **RED**, and reliability is
the only failing condition.

| Gate condition                  | Actual | Threshold | Status |
| ------------------------------- | -----: | --------- | ------ |
| `new_reliability_rating`        |      2 | > 1       | **ERROR** |
| `new_security_rating`           |      1 | > 1       | OK     |
| `new_maintainability_rating`    |      1 | > 1       | OK     |
| `new_coverage`                  |  80.7% | < 80%     | OK     |
| `new_duplicated_lines_density`  |   1.3% | > 3%      | OK     |
| `new_security_hotspots_reviewed`| 100.0% | < 100%    | OK     |

Two issues — both rule `java:S2637`, both LOW impact — are the *entire* reason
the gate is red. Everything else in this plan is maintainability work that the
gate already tolerates.

Whole-project issue population, for context:

| Software quality | Total | BLOCKER | HIGH | MEDIUM | LOW | INFO |
| ---------------- | ----: | ------: | ---: | -----: | --: | ---: |
| RELIABILITY      |     2 |       0 |    0 |      0 |   2 |    0 |
| MAINTAINABILITY  | 1,990 |       1 |  264 |    852 | 857 |   16 |
| SECURITY         |     0 |       0 |    0 |      0 |   0 |    0 |

**Scope of this plan: the 2 reliability issues + the 1 BLOCKER + the 264 HIGH
= 267 issues.** The remaining 1,725 MEDIUM/LOW/INFO maintainability issues are
explicitly out of scope here.

Note on "new code": SonarCloud currently classifies *all* 1,992 issues as being
in the new-code period, so "new code" and "overall code" are the same set for
this project. There is no pre-existing-debt carve-out to hide behind, but
equally, fixing anything in this plan improves both numbers at once.

Sonar's own remediation-effort estimate for the 267 in-scope issues is
**≈38 hours**, distributed very unevenly — see the phase tables.

## 2. Phase 1 — Reliability (2 issues, ~30 min, turns the gate green) — DONE

Both issues are the same rule and the same code shape: an
`Optional…​.orElse(null)` chain where SonarJava's dataflow concludes a `null`
reaches a parameter it considers `@NonNull`.

Line numbers below are where SonarCloud reported each finding, i.e. against the
pre-fix tree; the fixes have since shifted them.

| # | Module | Location as analysed | Rule |
| - | ------ | -------------------- | ---- |
| 1.1 | `pos-supplier` | `pos-supplier/…/internal/config/SupplierYamlBootstrap.java:158` | `java:S2637` |
| 1.2 | `pos-location` | `pos-location/…/internal/service/PeopleContactEventsListener.java:128` | `java:S2637` |

### 1.1 `SupplierYamlBootstrap` — retry backoff

```java
profile.setRetryBackoff(Optional.ofNullable(retry)
        .map(retrySpec -> retrySpec.backoff())
        .map(SupplierYamlBootstrap::parseBackoff)
        .orElse(null));                                   // ← flagged
```

The surrounding `else` branch already writes `profile.setRetryBackoff(null)`
unconditionally, so a null backoff is a legitimate, intended state — this is
about *how* the null is produced, not *whether* null is allowed.

Fix applied — drop the `Optional` round-trip for a plain null-guard, which is
both shorter and removes the flagged call entirely:

```java
String backoffSpec = retry == null ? null : retry.backoff();
profile.setRetryBackoff(backoffSpec == null ? null : parseBackoff(backoffSpec));
```

`SupplierYamlBootstrap#parseBackoff` is declared
`parseBackoff(@NonNull String backoff)`, and the guard now makes that contract
locally obvious instead of relying on `Optional.map`'s null-swallowing.

### 1.2 `PeopleContactEventsListener` — primary email lookup

```java
String primaryEmail = payload.contactPoints().stream()
        .filter(cp -> "EMAIL".equals(cp.contactType()) && cp.primary())
        .map(PersonUpdatedV1.ContactPointV1::value)
        .findFirst()
        .orElse(null);                                    // ← flagged
```

`PersonUpdatedV1.ContactPointV1` declares `@NonNull String value`
(`pos-domain-events/…/peoplecontact/PersonUpdatedV1.java:40`), so the stream
element type is non-null and `orElse(null)` contradicts it — while the
destination, `ExtPersonReplica.primaryEmail`, is genuinely nullable (a person
may have no primary email).

Fix applied — a small private helper with an explicit `@Nullable` return, which
states the nullability at the boundary where it actually changes:

```java
@Nullable
private static String primaryEmailOf(List<PersonUpdatedV1.ContactPointV1> contactPoints) {
    for (PersonUpdatedV1.ContactPointV1 contactPoint : contactPoints) {
        if ("EMAIL".equals(contactPoint.contactType()) && contactPoint.primary()) {
            return contactPoint.value();
        }
    }
    return null;
}
```

Note that the `existing` lookup a few lines above performs the same
`repository.findById(...).orElse(null)` and is *not* flagged, because that
`Optional`'s type argument carries no non-null annotation. Do not "fix" it.

### Phase 1 exit criteria

- [x] Neither module had a test pinning the null path, so both were added — they
      are the evidence that these refactors are behaviour-preserving rather than
      bug fixes:
      `SupplierYamlBootstrapTest#absentRetryBlockAndAbsentBackoffLeaveRetryColumnsNull`
      (absent `retry` block, and `maxAttempts` with no `backoff`) and
      `PeopleContactEventsListenerTest#personWithoutPrimaryEmailStoresNullPrimaryEmail`
      (a non-primary email plus a primary phone must not be promoted to
      `primaryEmail`).
- [x] `./mvnw -pl pos-supplier,pos-location,pos-workorder -DskipTests=false test`
      green across all three modules, with Spotless, Checkstyle and SpotBugs
      enabled (no `-Dskip` flags).
- [ ] Next SonarCloud analysis reports `new_reliability_rating = 1` and the
      quality gate flips to **Green**.

## 3. Phase 2 — The single BLOCKER (1 issue, ~10 min) — DONE

| Module | Location as analysed | Rule |
| ------ | -------------------- | ---- |
| `pos-workorder` | `…/internal/service/PromotedWorkorderDemandPublisherTest.java:145` | `java:S2699` |

```java
@Test
@DisplayName("no publisher available is a no-op, not a failure")
void missingPublisherIsANoOp() {
    when(publisherProvider.getIfAvailable()).thenReturn(null);

    demandPublisher.registerPartsDemand(workorder(), List.of(part(PART_ID, PRODUCT_ID, "2", "EA")));
}
```

The test asserts nothing; it only proves no exception was thrown. Its stated
intent — "a no-op" — is testable directly. Fix applied: assert both halves of
the claim.

```java
assertThatCode(() -> demandPublisher.registerPartsDemand(
                workorder(), List.of(part(PART_ID, PRODUCT_ID, "2", "EA"))))
        .doesNotThrowAnyException();
verifyNoInteractions(publisher);
```

`verifyNoInteractions` is the assertion that carries real information: with no
publisher available, nothing may be dispatched. Do **not** silence this by
adding `assertTrue(true)` or by annotating the rule away.

## 4. Phase 3 — HIGH severity (264 issues, ~37 h)

All 264 are MAINTAINABILITY impact. Ordered below by *risk carried per unit of
effort*, not by count — S6809 is last in volume terms but first in consequence.

| Order | Rule | Count | Effort | What it is |
| ----- | ---- | ----: | -----: | ---------- |
| 3.1 | `java:S6809` | 34 | 2.8 h | `@Transactional` method invoked via `this` |
| 3.2 | `java:S1948` | 1 | 0.5 h | Non-serializable field in a `Serializable` type |
| 3.3 | `java:S3252` | 4 | 0.3 h | Static member accessed via subclass/instance |
| 3.4 | `java:S1186` | 15 | 1.2 h | Empty method body |
| 3.5 | `java:S1192` | 148 | 21.0 h | String literal duplicated ≥3× in a file |
| 3.6 | `java:S3776` | 62 | 11.6 h | Cognitive complexity above 15 |

### 3.1 `java:S6809` — self-invoked `@Transactional` (34 issues) — do this first

Spring's transaction advice is proxy-based: a call through `this` bypasses the
proxy, so the callee's `@Transactional` attributes are silently ignored. Sonar
scores this as maintainability, but the failure mode is a **missing or wrong
transaction boundary at runtime** — which is why it leads Phase 3.

Concentrations (full list in the CSV):

| Module | File | Count |
| ------ | ---- | ----: |
| `pos-supplier` | `internal/workorderauth/service/WorkorderCompletionApprover.java` | 5 |
| `pos-customer` | `internal/service/MarketingConsentServiceImpl.java` | 3 |
| `pos-inventory` | `internal/service/LedgerPostingServiceImpl.java` | 3 |
| `pos-customer` | `internal/service/SegmentResolutionService.java` | 2 |
| `pos-customer` | `internal/config/CustomerCommandListener.java` | 2 |
| `pos-inventory` | `internal/service/InventoryAvailabilityServiceImpl.java` | 2 |
| `pos-price` | `internal/service/PromotionOfferServiceImpl.java` | 2 |
| …15 more files | | 1 each |

**Triage each site before changing it** — the correct fix differs by case, and
a blind self-injection refactor would be worse than the finding:

1. **Caller already transactional, callee is plain `REQUIRED`** → behaviour is
   already correct (the caller's transaction is joined). Fix by *removing the
   redundant `@Transactional` from the private/self-called method*, not by
   adding a proxy hop.
2. **Callee declares `REQUIRES_NEW`, `NOT_SUPPORTED`, a different isolation, or
   `readOnly` differing from the caller** → this is a live bug. The intended
   boundary is not being created. Fix by extracting the callee into a
   collaborator bean and injecting it (preferred, keeps the proxy honest), or
   by `TransactionTemplate` where a whole new bean is overkill.
3. **Caller is not transactional at all and the callee is** → also a live bug;
   same fixes as (2).

Deliverable for this step is a per-site classification (1/2/3) recorded in the
PR description, then the corresponding change. Cases in class (2) and (3) each
need a test that proves the boundary — typically asserting rollback of the
inner unit while the outer continues, or vice versa.

Do these module-by-module, one PR per module, so a regression in transaction
semantics is bisectable.

#### Triage results (all 34 sites classified)

Done. Nine sites are real, twenty-five are not. The split is lopsided because
most `S6809` hits in this codebase are a convenience overload delegating to its
full-argument sibling with *identical* `@Transactional` attributes — the caller
has already opened the transaction the callee would have asked for, so `REQUIRED`
joins it whether or not the proxy is involved.

**Class 3 — real: a non-transactional caller self-invoking a `@Transactional`
method, so no transaction is created at all (9 sites).**

| Module | Site | Entry point | Self-invoked | Consequence |
| ------ | ---- | ----------- | ------------ | ----------- |
| `pos-supplier` | `MktCatImporter:152` | `importAll` / `importVariants` (not transactional) → `importOne` (private) | `stageAndPublish` (`@Transactional`) | Stage-write and outbox emit are not atomic |
| `pos-supplier` | `WorkorderCompletionApprover:133,143,163,171,174` | `@Scheduled approveOutstanding` → `approveOne` (private) | `park`, `markApproved`, `recordAttempt` (all `@Transactional`) | Read-modify-write on the authorization row runs untransacted |
| `pos-workorder` | `FleetAuthorizationResourceReleaseRunner:86` | `@Scheduled releaseOverdue` | `releaseOne` (`@Transactional`) | Guard re-read and the release write are not one unit |
| `pos-customer` | `CustomerCommandListener:98,102` | `@KafkaListener onCommand` | `handleSegmentResolveRequested`, `handleSuppressionAddRequested` (both `@Transactional`) | Command handling is not atomic against redelivery |

`MktCatImporter` is the sharpest of these: `stageAndPublish`'s own javadoc states
the invariant being lost — *"Both in one transaction, through the outbox: a
variant recorded as published that was never emitted would be skipped on every
later run, because its hash now matches (ADR-0044 §4)."* Self-invocation means
that transaction does not exist, so the exact interleaving the comment warns
about is reachable today. `FleetAuthorizationResourceReleaseRunner` has the same
tell — `releaseOne` opens with *"Re-read the guard inside the transaction"*,
inside a method that has no transaction when called this way.

Note the negative evidence in `CustomerCommandListener`: the third branch,
`handleOutboxReplayRequested` (line 94), is *not* flagged, because that handler
is private and carries no `@Transactional`. Sonar is discriminating here, not
pattern-matching on self-calls.

**All nine are fixed.** Each transactional writer moved into a collaborator bean
so the call crosses a proxy: `MktCatVariantStager`, `WorkorderApprovalRecorder`,
`FleetAuthorizationResourceReleaser` and `CustomerCommandHandlers`. The splits
follow the data rather than the rule — in every case the moved methods took their
exclusive collaborators with them, so no field is now shared across the seam.

`MktCatVariantStagerTransactionTest` covers the boundary directly, following the
existing `SupplierExchangeAuditPersistenceTest` pattern (`@DataJpaTest` with
`Propagation.NOT_SUPPORTED`, because a test wrapped in its own rolled-back
transaction cannot observe a commit boundary and would pass with the bug
present). It was checked against the bug: with `@Transactional` removed from
`stageAndPublish`, `failedOutboxWriteRollsBackTheStagedRow` fails.

**Class 1 — no action: the caller already holds an equivalent or wider
transaction (25 sites).**

| Shape | Sites |
| ----- | ----- |
| Overload delegating to its full-argument sibling, identical attributes | `AccountingPeriodServiceImpl:108`, `CrmVehicleServiceImpl:98`, `PartyServiceImpl:231`, `PersonServiceImpl:231`, `InventoryAvailabilityServiceImpl:102,159`, `UomConversionServiceImpl:78`, `ServiceAreaServiceImpl:50`, `TravelBufferPolicyServiceImpl:48`, `LedgerPostingServiceImpl:113,119,125`, `InventoryLeadTimeServiceImpl:45`, `SourcingStrategyServiceImpl:89`, `AudienceEligibilityService:137` |
| Private helper inside a transactional entry point, calling a `readOnly` method | `SegmentResolutionService:131,161`, `MarketingConsentServiceImpl:188,221,222` |
| Read-write caller invoking a `readOnly = true` method | `PromotionOfferServiceImpl:103,119`, `VehiclePreferencesServiceImpl:128`, `FleetAuthorizationService:80` |
| `@Transactional` entry point calling another `@Transactional` method | `SupplierYamlBootstrap:93` |

The third row deserves a word, since it looks like a mismatch: `readOnly = true`
is honoured only when a transaction is *created*. An inner `REQUIRED` method
joining an existing read-write transaction does not downgrade it — so routing
these through the proxy would change nothing. The annotation is misleading to a
reader, but it is not wrong at runtime.

No code change is proposed for these 25. Removing the callee's `@Transactional`
is not available either: in every case above the callee is a public API method
that external callers reach through the proxy, where the annotation is load-bearing.

### 3.2 `java:S1948` (1 issue)

`pos-shop-manager/…/internal/exception/SchedulingConflictException.java:11` —
`SchedulingConflictException extends RuntimeException` (hence `Serializable`)
but holds a non-serializable `ConflictResponse conflictResponse`.

Fix: make `ConflictResponse` implement `Serializable`, or mark the field
`transient` and have `getConflictResponse()` cope with a null after
deserialisation. Prefer the former — the exception is mapped to an HTTP 409
body (`docs/ERROR_ENVELOPE.md`), so losing the payload on deserialisation would
silently degrade the response. Confirm the exception is never actually
serialised across a boundary first; if it never is, `transient` is the smaller
change.

### 3.3 `java:S3252` (4 issues)

Mechanical.

- `pos-customer/…/internal/service/PartyServiceImpl.java:721,733,735` — access
  `PARTY_ID` via `AbstractParty_` rather than a subclass metamodel.
- `pos-security-service/…/internal/config/HttpToHttpsRedirectConfig.java:46` —
  access `DEFAULT_PROTOCOL` via `TomcatWebServerFactory`.

No behaviour change, no tests needed beyond the existing suite. Good
first commit of Phase 3 if a warm-up is wanted.

### 3.4 `java:S1186` — empty methods (15 issues)

These split into three genuinely different situations; **one of them may be a
real defect**, so do not batch-fix.

1. **`@PrePersist void ensureId() {}` — investigate before touching.**
   `pos-people/…/entity/WorkSession.java:64`,
   `WorkSessionBreak.java:59`, `TimeEntryException.java:169,175`. An empty JPA
   lifecycle callback named `ensureId` strongly suggests the UUID v7 id
   assignment was moved out (see `docs/UUID_V7_MIGRATION.md`) and the hook was
   emptied rather than deleted. Confirm ids are assigned elsewhere for these
   entities; then delete the dead callback. If ids are *not* assigned
   elsewhere, this is a latent persistence bug and is the highest-value item in
   Phase 3.
2. **Explicit no-arg constructors on DTOs** —
   `pos-customer/…/dto/snapshot/{CrmSnapshotDTO,ContactSummary,BillingRuleRef}.java`,
   `pos-workorder/…/dto/BillingRulesDTO.java` (6 sites). Required by Jackson.
   Fix by documenting intent in the body, e.g.
   `/* Required by Jackson for deserialisation. */`, which satisfies S1186.
3. **Test fixture stubs** —
   `pos-security-common/src/test/java/…/RequiredPermissionsOpenApiAutoConfigurationTest.java:21,24,27,30,32`.
   These are deliberately empty `@PreAuthorize`-annotated methods whose *bodies
   are irrelevant*; the annotation is the fixture. Same fix as (2): a one-line
   comment in each body.

### 3.5 `java:S1192` — duplicated string literals (148 issues, 21 h)

Largest bucket by far and the lowest risk. Each fix is "extract a
`private static final String`". Highest-density files:

| File | Count |
| ---- | ----: |
| `pos-supplier/…/internal/service/AuthReferenceRules.java` | 7 |
| `pos-customer/…/internal/security/CrmPermissionRegistry.java` | 4 |
| `pos-order/…/internal/service/WorkorderEventsListener.java` | 4 |
| `pos-inventory/…/internal/security/InventoryPermissionRegistry.java` | 4 |
| `pos-mcp-server/…/internal/service/OpenApiToolProvider.java` | 4 |
| `pos-workorder/…/internal/service/InventoryEventsListener.java` | 3 |
| `pos-customer/…/internal/service/PeopleContactEventsListener.java` | 3 |
| `pos-order/…/internal/client/RestInvoicingPortAdapter.java` | 3 |

Two sub-cases deserve a decision rather than a reflex:

- **Permission-registry files** (`*PermissionRegistry.java`): the duplicated
  literals are permission-name fragments (`domain:resource:action`). The repo
  convention is that controllers reference constants
  (`SomePermissions.SOME_ACTION`, per `CLAUDE.md`), so the right fix is to
  point the registry at the *same existing* `*Permissions` constants rather
  than minting new private ones. Check for an existing constant before
  extracting.
- **Event-id / error-code literals** (`"VALIDATION_ERROR"`, event type names in
  `*EventsListener.java`): these often already exist in a `{Module}EventTypes`
  registry (`internal/config`). Reuse, don't duplicate the duplication.

Everything else is a straightforward local constant. The 148 issues are
spread over 20 modules; batch by module, one PR per module, mechanical review.

### 3.6 `java:S3776` — cognitive complexity (62 issues, 11.6 h)

Sonar's threshold is 15. The distribution is long-tailed: **23 methods score
20 or above** and 39 sit at 16–19. Highest-complexity offenders:

| Complexity | Location |
| ---------: | -------- |
| 57 | `pos-price/…/internal/service/EligibilityEvaluationServiceImpl.java:131` |
| 52 | `pos-inventory/…/internal/service/InventoryFactPublisher.java:282` |
| 37 | `pos-accounting/…/internal/service/PredicateParser.java:383` |
| 34 | `pos-warranty/…/internal/service/EligibilityServiceImpl.java:71` |
| 34 | `pos-tax/…/internal/service/AvalaraTaxProvider.java:260` |
| 31 | `pos-supplier/…/internal/config/SupplierYamlBootstrap.java:405` |
| 30 | `pos-warranty/…/internal/service/ClaimServiceImpl.java:519` |
| 27 | `pos-inventory/…/internal/service/ReplenishmentServiceImpl.java:308` |
| 26 | `pos-inventory/…/internal/service/ReceivingServiceImpl.java:114` |
| 25 | `pos-supplier/…/internal/adapter/ediwheelc12/EdiwheelC12MktCatCodec.java:231` |
| 25 | `pos-security-service/…/internal/service/CrmSignalService.java:40` |
| 25 | `pos-inventory/…/internal/service/AsnServiceImpl.java:168` |

By module: `pos-accounting` 13, `pos-inventory` 11, `pos-customer` 7,
`pos-supplier` 6, `pos-warranty` 5, then a tail of 12 modules with 1–3 each.

This is the only bucket that changes real control flow, so it is scheduled
**last** and gated on coverage:

- Do **not** refactor a method whose module has no meaningful test coverage —
  cross-check `docs/TEST_COVERAGE_IMPROVEMENT_PLAN.md` §6 floors first, and
  write characterisation tests before extracting anything. `pos-accounting`,
  `pos-inventory` and `pos-warranty` carry 29 of the 62 issues between them and
  are exactly the modules that plan flags as thinnest — sequence coverage work
  ahead of the refactor there, not alongside it.
- Work from the top of the table down. Dropping `EligibilityEvaluationServiceImpl`
  from 57 and `InventoryFactPublisher` from 52 removes more risk than the entire
  16–19 tail combined, and both are single methods.
- `PredicateParser:383` (37) and `AvalaraTaxProvider:260` (34) are parser/adapter
  dispatch methods — expect a table- or strategy-driven rewrite rather than
  incremental extraction.
- The EDI codec cluster in `pos-supplier`
  (`EdiwheelC12MktCat` 25, `EdiwheelC11OrderStatus` 21 and 17,
  `EdiwheelB40Pricat` 17, `EdiwheelB21StockReport` 17) is one shared shape: long
  segment-parsing switches. Refactor them together with a common extraction
  strategy rather than five ad-hoc rewrites.
- The 39 methods at 16–19 are one or two extracted private methods each and can
  be batched per module once the heavy tail is done.

## 5. Sequencing and PR strategy

| Phase | Content | Issues | Effort | Gate impact | Status |
| ----- | ------- | -----: | -----: | ----------- | ------ |
| 1 | Reliability `S2637` | 2 | 0.5 h | **Red → Green** | done |
| 2 | BLOCKER `S2699` | 1 | 0.2 h | none | done |
| 3.1 | `S6809` transactional self-invocation | 34 | 2.8 h | none (real runtime risk) | done: 9 fixed, 25 no-action |
| 3.2–3.4 | `S1948`, `S3252`, `S1186` | 20 | 2.0 h | none | not started |
| 3.5 | `S1192` literals | 148 | 21.0 h | none | not started |
| 3.6 | `S3776` complexity | 62 | 11.6 h | none | not started |
| | **Total** | **267** | **≈38 h** | | |

- Phases 1 and 2 shipped as **one small PR** — 3 issues, and the only change in
  this plan that moves the quality gate. Landing it first means subsequent PRs
  are analysed against a green baseline.
- Phase 3.1 ships **one PR per module** (9 modules, 22 files) with the
  1/2/3 classification in each description.
- Phases 3.2–3.4 can share a single PR (mechanical, 20 issues) *except* the
  `@PrePersist ensureId` investigation in 3.4, which splits out if it turns out
  to be a real defect.
- Phase 3.5 ships one PR per module (20 modules), mechanical review.
- Phase 3.6 ships one PR per method-cluster, each with its characterisation
  tests in the same commit.

Every PR runs the standard gates: Checkstyle, Spotless (`spotless:check` is
bound to `validate`), SpotBugs at threshold `High`, and — after any package
movement — `./mvnw -pl pos-archunit -am -Dtest=ArchitectureTests test`.

## 6. What this plan deliberately does not do

- **No rule suppressions.** No `//NOSONAR`, no `@SuppressWarnings` added to
  silence a finding, no quality-profile edits. Every item above is fixed in the
  code or explained in the PR.
- **No coverage work.** `new_coverage` is at 80.7% against an 80% floor —
  narrow, but passing. Tests added here exist to protect refactors, not to move
  that number; the coverage programme is
  `docs/TEST_COVERAGE_IMPROVEMENT_PLAN.md`.
- **No MEDIUM/LOW/INFO maintainability sweep.** 1,725 issues remain below HIGH
  (dominated by `java:S5778` ×507, `java:S8924` ×242, `java:S7467` ×198). Those
  are a separate decision; several are arguably profile-tuning questions rather
  than code defects, and mixing them in would bury the 37 issues in this plan
  that carry actual runtime risk.
- **No re-baselining of the new-code period.** All 1,992 issues currently fall
  inside it; shrinking that window would turn the gate green without fixing
  anything.

## 7. Re-measuring

SonarCloud analysis runs on pull requests and on the full-coverage job
(`.github/workflows/ci.yml`). To re-derive the tables above from the live
project without a token (the project is public):

```bash
curl -s "https://sonarcloud.io/api/qualitygates/project_status?projectKey=louisburroughs_durion-positivity-backend"
curl -s "https://sonarcloud.io/api/issues/search?componentKeys=louisburroughs_durion-positivity-backend&resolved=false&impactSeverities=BLOCKER,HIGH&ps=100&p=1&facets=rules,impactSoftwareQualities"
curl -s "https://sonarcloud.io/api/issues/search?componentKeys=louisburroughs_durion-positivity-backend&resolved=false&impactSoftwareQualities=RELIABILITY&ps=100"
```

Regenerate `sonarqube-remediation-inventory.csv` from the second and third
calls after each phase lands, and update the Status line at the top of this
document. Every `file.java:NN` coordinate in this plan and in the CSV is the
location SonarCloud reported at the analysis named above; fixing a file shifts
the lines below it, so re-derive rather than trusting a stale number.
