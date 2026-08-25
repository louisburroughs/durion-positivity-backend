# SonarQube Remediation Plan

Status: Phases 1, 2, 3.1–3.5 implemented. Phase 3.6 (`S3776`, 62 findings)
in progress: **54 of 62 findings addressed** (45 distinct classes) across
#1498–#1506 (merged) and stacked parts 6–7 (open at the time of writing);
8 remain, all in stacked part 8.
Per-finding status is now a `status` column in
`docs/sonarqube-remediation-inventory.csv`, mapping every row of every phase to
the PR that resolved it or its documented no-action reason.
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
      quality gate flips to **Green**. Note this does **not** happen on merge:
      per `.github/workflows/ci.yml` (`code-quality-full`), the only job that
      publishes a branch-level analysis runs on `schedule` (nightly, 06:00 UTC)
      or `workflow_dispatch`. Until one of those runs, the project gate keeps
      reporting the last nightly value, which predates the fix. The PR-mode scan
      on #1487 reported zero reliability issues before it merged.

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

### 3.2 `java:S1948` (1 issue) — DONE

`pos-shop-manager/…/internal/exception/SchedulingConflictException.java:11` —
`SchedulingConflictException extends RuntimeException` (hence `Serializable`) but
held a non-serializable `ConflictResponse conflictResponse`.

The plan called for confirming first whether the exception is ever serialized
across a boundary, and preferring `Serializable` over `transient` if the payload
matters. It is never serialized — it is thrown by `ConflictDetectionServiceImpl`
and handled in-process, mapped to an HTTP 409 body. By the letter of the rule that
makes `transient` the smaller change, but `transient` would leave
`getConflictResponse()` able to return null after a deserialization that never
happens: a footgun guarding a case that does not exist.

So: `ConflictResponse` and its two nested types, `Conflict` and
`SuggestedAlternative`, now implement `Serializable` with explicit
`serialVersionUID`. They hold only `String`, `Instant` and `List` of each other,
so nothing else was needed, the accessor stays total, and the 409 payload would
survive a round trip if one ever occurred.

### 3.3 `java:S3252` (4 issues) — DONE

Mechanical, and both cases were static members reached through a subtype rather
than their declaring class.

- `pos-customer/…/internal/service/PartyServiceImpl.java:721,733,735` —
  `PARTY_ID` is declared on `AbstractParty_`, not on the `CommercialParty_`
  metamodel the code went through. Now `AbstractParty_.PARTY_ID`. The
  neighbouring `LEGAL_NAME` uses are untouched: that constant really is declared
  on `CommercialParty_`, which is why Sonar flagged three of the five references
  in that method and not the other two.
- `pos-security-service/…/internal/config/HttpToHttpsRedirectConfig.java:46` —
  `DEFAULT_PROTOCOL` is declared on `TomcatWebServerFactory` in Spring Boot 4, and
  was reached through `TomcatServletWebServerFactory`. Now references the
  declaring class; the `TomcatServletWebServerFactory` import stays, since
  `@ConditionalOnClass` and the customizer's type parameter still need it.

Verified: `pos-customer` 660, `pos-security-service` 488, `pos-shop-manager` 229
tests green with Spotless, Checkstyle and SpotBugs enabled.

### 3.4 `java:S1186` — empty methods (15 issues) — DONE

The investigation this section called for changed the conclusion, in both directions.

**1. `@PrePersist ensureId() {}` — dead, as suspected (4 sites → 2).** `WorkSession:64` and
`WorkSessionBreak:59` each carried an empty `@PrePersist` callback named `ensureId`. Ids are
assigned by `@GeneratedValue @UUIDv7Id` on the `@Id` field, nothing calls `ensureId`, and JPA
invoked it on every insert to do nothing. Leftover from the UUID v7 migration
(`docs/UUID_V7_MIGRATION.md`); both deleted.

**2. `TimeEntryException:169,175` — a real defect, but not the one expected.** These are not
lifecycle callbacks at all; they are *setters with empty bodies* —
`public void setCreatedAt(Instant createdAt) {}` — accepting an argument and discarding it.

The worry was auditing: the entity is `@EntityListeners(AuditingEntityListener.class)` with
`@CreatedDate`/`@LastModifiedDate`, and both columns are nullable in
`V1__baseline_people_schema.sql`, so silently unstamped rows would never have failed anything.
**That worry is wrong, and it was worth testing rather than asserting.**
`TimeEntryExceptionAuditingTest#persistedExceptionCarriesAuditTimestamps` passes against the
*pre-fix* entity: Spring Data's accessor reaches those fields directly rather than through their
setters, so stamping was always correct. The test is kept to record it, and to catch a future
change that made stamping depend on the setters.

What the empty bodies really were is a trap. No production code called either setter, so nothing
was broken; anything that started to would have silently got nothing. Both now assign, and
`settersAssign` is the assertion that fails against the pre-fix entity.

Adding that test required `spring-boot-starter-data-jpa-test` in `pos-people`, which had JPA
entities and repositories but no persistence-slice coverage. It runs on H2 with Flyway disabled and
the schema generated from the entities — the module's documented default footing, because its
baseline uses Postgres-only SQL (`SPLIT_PART`) that H2 cannot execute. `FlywayMigrationIT` covers
the migrations themselves against real Postgres.

**3. Redundant explicit no-arg constructors (6 sites) — deleted, not documented.**
`pos-customer/…/dto/snapshot/{BillingRuleRef,ContactSummary,CrmSnapshotDTO}.java` and
`pos-workorder/…/dto/BillingRulesDTO.java`.

The first attempt documented these as Jackson-required. That was wrong on every
count, and review caught it: none of the six classes declares any other
constructor, and none carries a Jackson annotation. A `public Foo() {}` in a
class with no other constructors is exactly the constructor Java generates
implicitly, with the same access modifier — so these were not
undocumented-but-necessary, they were redundant.

Deleted. That removes the finding at its root rather than annotating it, and is
behaviour-neutral: the implicit default constructor is identical, so reflective
construction and `new Foo()` are unaffected.

**4. Test fixture stubs (5 sites).**
`pos-security-common/…/RequiredPermissionsOpenApiAutoConfigurationTest.java:21,24,27,30,32` —
deliberately empty methods whose `@PreAuthorize` annotation *is* the fixture, plus one deliberately
unannotated. Same treatment.

Verified: `pos-people` 103, `pos-customer` 660, `pos-workorder` 940, `pos-security-common` 312
tests green with Spotless, Checkstyle and SpotBugs enabled.

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

#### Progress: 54 of 62 findings addressed (45 distinct classes)

| # | PR | Method split | Class branch coverage |
| -: | -- | ------------ | --------------------- |
| 1–5 | #1498 | `EligibilityEvaluationServiceImpl.evaluateSingleRule` (57), `InventoryFactPublisher.publishPending` (52), `PredicateParser` (37), `EligibilityServiceImpl` (34), `AvalaraTaxProvider.mapResponse` (34) | five modules ratcheted |
| 6–10 | #1500 | `SupplierYamlBootstrap.validateBindings` (31), `ClaimServiceImpl.applyLineDecisions` (30), `ReplenishmentServiceImpl` (27), `ReceivingServiceImpl` (26), `EdiwheelC12MktCatCodec.decodeImageRefs` (25) | `pos-warranty` ratcheted |
| 11 | part 3 | `CrmSignalService.assess` (25) | 89.3% (already covered) |
| 12 | part 3 | `AsnServiceImpl.createGoodsReceipt` (25) | coverage-neutral |
| 13 | part 3 | `SegmentResolutionService.loadCommercialCandidates` (24) | 49.0% → 97.4% |
| 14 | part 3 | `PostingRuleEvaluatorImpl` — four findings in one class (22, 21, 19, 18) | 75.0% → 89.8% |
| 15 | part 3 | `ReturnOrderServiceImpl.createReturn` (22) | 61.3% → 87.4% |

| 16 | part 4 | `OpenApiModuleValidator.validate` (22) | 91.7% → 100% (+ one dead guard removed) |
| 17 | part 4 | `TestModeTaxCalculator.resolveRates` (22) | 63.5% → 87.3% |
| 18 | part 4 | `EdiwheelC11OrderStatusCodec` — two findings (21, 17) | 68.3% → 94.1% |
| 19 | part 4 | `PostingRuleDefinitionValidator.validateCondition` (20) | 91.5% (already covered) |
| 20 | part 4 | `AccountTierServiceImpl.calculateTier` (20) | 96.0% → 100% |
| 21 | part 4 | `PartyRelationshipServiceImpl.getContactsForCommercialAccount` (20) | 89.1% → 97.8% |
| 22 | part 4 | `PersonDirectoryService.bestMatch` (20) | 44.8% → 85.4% |
| 23–24 | part 4 | `BankReconciliationServiceImpl` — two findings (19, 18) | 52.2% → 92.2% |

| 25 | part 5 | `SalesOrderServiceImpl.linkSource` (18) | 89.4% → 91.3% (method 100%) |
| 26–27 | part 5 | `PartReturnServiceImpl.update` (19), `EligibilityServiceImpl.checkTerms` (18) | 88.5% → 96.2%; 83.6% → 85.2% |
| 28 | part 5 | `ContactRoleServiceImpl.getContactsWithRoles` (19) | 0% unit → 76.9% (method 100%) |
| 29 | part 5 | `AppointmentsServiceImpl.createAppointment` (19) | 56.0% → 59.2% (method + helpers ~100%) |
| 30–32 | part 5 | `CycleCountScheduleServiceImpl.runDueSchedules` (19), `ReplenishmentSourcingService.resolve` (19), `ReceivingServiceImpl.crossDockLineToWorkorder` (18) | 76.2%; 70.8% → 82.7% (first unit tests); 66.2% → 72.1% |
| 33–34 | part 5 | `LaborOverheadReportServiceImpl.aggregateLeafMonthly` (19), `PostingEngineOrchestrator.processEvent` (19) | 88.6% → 92.9%; 91.7% → 96.4% |

Part 5 was executed by per-module subagents working from a shared plan; note
that the `ReceivingServiceImpl:225` finding is `crossDockLineToWorkorder` —
the low-coverage `resolveRequestScopedSiteId` flagged earlier lives in
`StagingLocationResolver`, a different class, and stays open.

| 35–37 | part 6 | `InvoiceTaxBreakdownWriter.replace` (18), `PaymentReversalServiceImpl.refundPayment` (18), `OrderInvoiceServiceImpl.createInvoiceForOrder` (16) | 66.7% → 100%; 87.8% → 100%; 67.6% → 98.5% |
| 38–40 | part 6 | `DashboardServiceImpl` — two findings (18, 18) — and `EstimateServiceImpl.updateEstimateItem` (17) | 77.9% → 90.0%; 81.0% → 82.8% |
| 41–42 | part 6 | `EdiwheelB21StockReportCodec.decode` (17), `EdiwheelB40PricatCodec.decode` (17) | 66.7% → 97.0%; 68.3% → 100% |

| 43–46 | part 7 | `DistributorFeedServiceImpl.normalizeLeadTime` (17), `ReplenishmentServiceImpl.getReplenishmentNeeds` (17), `SupplierStockHintResolver.runResolutionPass` (17), `LedgerPostingServiceImpl.postAll` (16) | 60.0% → 83.9%; 80.3% → 82.2%; 80.0% → 90.0%; 75.0% → 77.2% (methods 100%) |
| 47–49 | part 7 | `BankStatementCsvParser.splitCsv` (16), `FinancialReportingServiceImpl.generateTaxLiability` (16), `MappingResolutionTestServiceImpl.scanConditions` (16) | methods 42.9%/90.0%/61.5% → 100% each |
| 50 | part 7 | `ProrationService.prorate` (17) | 86.5% → 100% |

**The recurring finding.** In almost every target, the complexity finding names
a method but the *untested* branches are in the helper or lambda it delegates
to — and that is where the business rules live. `SegmentResolutionService`'s
mapper was 0 of 26 branches; `PostingRuleEvaluatorImpl`'s untested arms were
its unbalanced-journal rejection and every split-group invariant;
`ReturnOrderServiceImpl.returnableLines` had no coverage at all. Measuring
before refactoring is what surfaced these; the refactor itself was the cheaper
half of each target.

**Method used for each target**, unchanged since #1498: measure branch
coverage → write characterisation tests against the *unrefactored* method,
aimed at the uncovered branches → refactor → confirm the same tests still pass
→ close the gaps the split makes visible → ratchet the module floor with
`scripts/coverage_floors.py`.

Unreachable guards are documented rather than covered with fixtures that
misrepresent what the code can receive — see `SegmentResolutionService`'s
`personReplicasByPersonId` (`person_id` is `NOT NULL`), `serviceDue`'s
null-months `continue`, and `PostingRuleEvaluatorImpl`'s balance check on the
default-mapping path (balanced by construction).

Note that four of the files above carry a *second, unaddressed* finding in a
different method — `AvalaraTaxProvider:188` (16), `EligibilityServiceImpl:233`
(18), `ReceivingServiceImpl:225` (18), `ReplenishmentServiceImpl:516` (17) —
which the CSV keeps `open`. Resolution is per method, not per file.

Next by complexity: `TestModeTaxCalculator` (22, `pos-tax`),
`OpenApiModuleValidator` (22, `pos-openapi-validation`),
`EdiwheelC11OrderStatusCodec` (21, `pos-supplier`), `PersonDirectoryService`
(20, `pos-people-contact`).

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
| 3.2–3.3 | `S1948`, `S3252` | 5 | 0.8 h | none | done |
| 3.4 | `S1186` empty methods | 15 | 1.2 h | none | done: 8 deleted, 2 fixed, 5 documented |
| 3.5 | `S1192` literals | 148 | 21.0 h | none | done |
| 3.6 | `S3776` complexity | 62 | 11.6 h | none | in progress: 54 of 62 (45 classes) |
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

SonarCloud analysis runs on pull requests (`code-quality`, PR-scoped) and on the
full-coverage job (`code-quality-full`), which is the only one that publishes a
branch-level analysis and runs **only** nightly at 06:00 UTC or via
`workflow_dispatch` — merging to `main` does not refresh the project gate. To re-derive the tables above from the live
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

## Post-baseline findings

Issues registered by analyses after the 2026-08-24 06:30 baseline are tracked
here rather than in the inventory CSV (which is frozen to the analysed
snapshot).

| Registered | Rule | Location | Status |
| ---------- | ---- | -------- | ------ |
| 2026-08-24 18:11 | `javabugs:S2259` (reliability BLOCKER) | `pos-inventory/…/InventoryAvailabilityServiceImpl` — `queryAvailability`'s ATP subtraction | fixed — all four `onHand`/`allocated` assignment arms routed through `Quantities.nz` |

The S2259 finding is the dataflow engine's documented limitation, not a
runtime bug: it models a bare `BigDecimal.ZERO` static-field read as possibly
null (see `Quantities.nz`'s javadoc, which exists for exactly this), and both
the scoped ternaries and the reduce identities assigned from one, so the
engine proved an NPE path into the subtraction. `Quantities.nz` is the one
shape the engine accepts as non-null; behaviour is identical.

