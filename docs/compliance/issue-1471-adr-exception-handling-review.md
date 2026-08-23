# ADR Review: Exception Handling vs Issue #1471

**Scope:** Assessment of the platform ADR set (durion repo, `docs/adr/`) against
[issue #1471](https://github.com/louisburroughs/durion-positivity-backend/issues/1471)
("Unhandled exceptions escape as bare 500s with no error code or correlation id").

**Date:** 2026-08-23

---

## Relevant ADRs

| ADR | Relevance |
| --- | --- |
| [ADR-0017: API Controller HTTP Response Codes Standard](https://github.com/louisburroughs/durion/blob/main/docs/adr/0017-api-controller-http-response-codes.adr.md) | The core exception-handling ADR: canonical status matrix, error envelope contract, correlation-id propagation |
| [ADR-0046: Environment Log Level Policy](https://github.com/louisburroughs/durion/blob/main/docs/adr/0046-environment-log-level-policy.adr.md) (§6) | Structured JSON logs must carry the `correlationId` already propagated in the `ApiError` envelope |
| `docs/ERROR_ENVELOPE.md` (this repo, non-ADR) | Concrete `ApiError` schema the envelope decisions refer to |

No other ADR (0001–0055) addresses exception mapping, catch-all handlers, or
`DataIntegrityViolationException`. There is no dedicated "global exception handling" ADR.

---

## Verdict per issue point

### 1. `DataIntegrityViolationException` → 409 / 422

**Partially covered; one open decision.**

- **409 for unique-constraint violations: already decided.** ADR-0017 §2 explicitly lists
  "duplicate-unique constraints" as a `409` case. The issue's proposal is *consistent with the
  existing ADR* — the failure is purely an implementation gap. Verified in code: **no controller
  advice in any module handles `DataIntegrityViolationException`** (all 38 references to it are
  service-layer `try/catch` blocks, e.g. `PartyTagServiceImpl`), so uncaught unique violations fall
  through to Spring's default 500 page — a direct violation of ADR-0017 §2/§3.
- **422 for not-null / check violations: NOT decided, and in tension with the ADR.** ADR-0017 §1
  maps field-validation failures to `400`, and §2 restricts `422` to domain-policy violations
  "explicitly documented in the endpoint contract". A NOT NULL violation reaching the database fits
  neither bucket cleanly. Note that the motivating bug (#1464, `created_by` NOT NULL with no
  `AuditorAware`) was a *server-side* defect the client could not have fixed — for
  server-populated columns, an enveloped `500` is arguably the honest answer, with `422` reserved
  for client-supplied fields. **This needs an ADR decision (amend ADR-0017 or supersede), not just
  code.**

### 2. Catch-all `@ExceptionHandler(Exception.class)` in every service

**Requirement already implied by ADR-0017; mechanism and mandate missing.**

ADR-0017 §3 standardizes **all** non-2xx bodies to the envelope (`code`, `message`, `status`,
`timestamp`, `correlationId`) and §4 requires every error response to carry `X-Correlation-Id`.
A bare Spring whitelabel 500 therefore already violates the accepted ADR — the issue describes an
ADR-compliance failure, not a policy vacuum. However, the ADR's Implementation Notes ("centralize
status-code mapping in ... global handlers") are advisory and name no concrete mechanism, which is
how the gap arose. Verified in code: only 7 modules have an `Exception.class` handler
(pos-inventory, pos-people, pos-people-contact, pos-security-service, pos-supplier, pos-warranty,
pos-mcp-server); at least 14 controller-bearing modules with advices have none (pos-accounting,
pos-bulk-loader, pos-catalog, pos-customer, pos-documents, pos-image, pos-invoice, pos-location,
pos-marketing, pos-order, pos-price, pos-shop-manager, pos-vehicle-inventory, pos-workorder).
The issue's count (2 of 7 services checked) is confirmed for the services it names.

### 3. Shared `@ControllerAdvice` in a common module

**No ADR covers this.** ADR-0017's Implementation Notes say to apply the standard "for each `pos-*`
service" — per-service duplication is the (implicit) status quo, and seven copies is how the drift
happened. No ADR governs where cross-cutting web concerns live (ADR-0026, the service-contract
boundary policy, is silent on shared libraries). The issue's suggestion (pos-security-common, with
service-specific advices keeping `@Order` precedence) is unopposed by any ADR but also unbacked by
one. **Needs a decision** — including whether the shared advice lives in pos-security-common or a
new/existing web-commons module, since pos-shared-dtos (home of `ApiError`) currently ships DTOs
only.

### 4. Enforcement (ArchUnit rule requiring a catch-all)

**No ADR and no existing rule.** pos-archunit contains no rule about exception handlers or
controller advices (verified). Precedent for enforcing ADRs via ArchUnit exists (package layout,
ADR-0013 identifier rules), so the issue's "enforced rather than remembered" acceptance criterion
fits the house style but requires a new decision and a new rule.

### 5. Identical envelope across services

**Fully covered.** ADR-0017 §3 + the shared `ApiError` record
(`pos-shared-dtos/src/main/java/com/positivity/shared/error/ApiError.java`) already give one
canonical shape, including `fieldErrors[]` as the field-level collection. Existing advices that do
handle a type comply (e.g. `CrmExceptionHandler` resolves/echoes `X-Correlation-Id` per §4). The
gap is only the unmapped-exception path.

---

## Summary

| Issue #1471 ask | ADR status |
| --- | --- |
| 409 for unique-constraint violation | ✅ Decided (ADR-0017 §2) — implementation gap only |
| 422 for not-null / check violation | ❌ Undecided; conflicts with ADR-0017 §1/§2 boundary — needs ADR change |
| Catch-all with envelope + correlation id | ⚠️ Implied by ADR-0017 §3/§4 but mechanism never mandated |
| Shared advice in a common module | ❌ No ADR; location undecided |
| ArchUnit-enforced catch-all | ❌ No ADR, no rule |
| One envelope shape for all services | ✅ Decided (ADR-0017 §3 + `ApiError`) |

## Recommendation

Author a new platform ADR (next free number, "Platform Global Exception Handling and Persistence
Error Mapping") rather than growing ADR-0017 further. It should decide:

1. Every controller-bearing `pos-*` module MUST register a catch-all `@ExceptionHandler(Exception.class)`
   returning the ADR-0017 envelope, logging the stack trace at ERROR keyed by the `correlationId`
   (ties into ADR-0046 §6 structured logging).
2. Central `DataIntegrityViolationException` mapping: `409` for unique violations (restating
   ADR-0017 §2); an explicit choice for not-null/check violations — recommended: `422` when the
   violated column is client-supplied, enveloped `500` when it is server-populated (audit columns),
   distinguished by constraint name from the nested `ConstraintViolationException`.
3. The shared-advice home (e.g. `@AutoConfiguration` in pos-security-common or a new
   pos-web-common), with service advices taking `@Order` precedence.
4. An ArchUnit rule in pos-archunit requiring the catch-all (or the shared advice import) for every
   module containing `@RestController`s, satisfying the issue's "enforced rather than remembered"
   criterion.

ADR-0017 then needs only a small changelog amendment cross-referencing the new ADR for the
not-null/check mapping.
