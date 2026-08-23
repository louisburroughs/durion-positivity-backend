# ADR-0056: Platform Global Exception Handling and Persistence Error Mapping

**Status:** ACCEPTED **Date:** 2026-08-23 **Deciders:** Architecture, Backend Lead, API Lead **Affected Issues:** durion-positivity-backend#1471

> **Canonical home:** `durion/docs/adr/0056-platform-global-exception-handling.adr.md`, merged in
> louisburroughs/durion#403 and marked ACCEPTED. This copy lives in durion-positivity-backend because it was authored
> alongside the implementing change, mirroring the existing local copies of ADR-0042/0044.

---

## Context

ADR-0017 requires every non-2xx response to carry the `ApiError` envelope (`code`, `message`, `status`,
`timestamp`, `correlationId`) and to echo/generate `X-Correlation-Id`. In practice the requirement only
held for exceptions someone had explicitly mapped: when an exception reached a controller without a
matching `@ExceptionHandler`, Spring's default error page answered instead — a bare 500 with none of the
envelope. Issue durion-positivity-backend#1471 documented three production bugs in two days that all
presented as this indistinguishable bare 500 (a `@Lob` read outside a transaction, a NOT NULL violation
from a missing `AuditorAware`, and a UNIQUE violation on customer numbers), each costing hours that a
mapped status plus a correlation id would have collapsed into minutes.

Two structural gaps produced this:

1. No service mapped `DataIntegrityViolationException`, so every unique/not-null/check violation became
   an unmapped 500 despite ADR-0017 §2 already classifying duplicate-unique constraints as `409`.
2. Only a minority of services had a catch-all `@ExceptionHandler(Exception.class)`. ADR-0017's
   implementation notes said to centralize mapping in "global handlers" but mandated no mechanism, so
   seven-plus services hand-rolled advices that each covered a different subset.

---

## Decision

### 1. Platform catch-all is mandatory and shared

**Decision:** ✅ **Resolved** — Every controller-bearing `pos-*` servlet module MUST have a catch-all
exception handler that returns the ADR-0017 envelope with a correlation id and logs the stack trace at
ERROR keyed by that correlation id (ADR-0046 §6 structured logging carries the same id).

The platform implementation is `GlobalApiExceptionHandler` in the shared **pos-web-common** module,
registered via Spring Boot auto-configuration at `Ordered.LOWEST_PRECEDENCE`:

- Service-specific `@ControllerAdvice` classes keep precedence for every exception type they map;
  the shared advice only sees what nothing else handled.
- Spring Security exceptions (`AccessDeniedException`, `AuthenticationException`) are rethrown so the
  security filter chain renders its 401/403 — the advice never converts them to 500s.
- Spring MVC's own `ErrorResponse` exceptions (unknown path, unsupported method/media type, malformed
  request) keep their framework status and gain the envelope, rather than collapsing to 500.
- 500 bodies stay generic (`INTERNAL_ERROR` / "Unexpected error occurred"); the correlation id is the
  diagnostic handle. Rejected data values are never echoed; only constraint/column identifiers may be
  named on 409/422.
- Opt-out for tests or special cases: `pos.web.global-exception-handler.enabled=false`, or define a
  module-local `GlobalApiExceptionHandler` bean (the auto-configuration backs off).

### 2. Central `DataIntegrityViolationException` mapping

**Decision:** ✅ **Resolved** — The shared advice classifies integrity violations by the SQLSTATE of the
underlying `SQLException` (class 23 is standardized across PostgreSQL and H2) and maps:

| Violation | SQLSTATE | Status | Code |
| --- | --- | --- | --- |
| Unique | `23505` | **409** | `DUPLICATE_RESOURCE` |
| Not-null, client-supplied column | `23502` | **422** | `MISSING_REQUIRED_VALUE` |
| Not-null, server-populated audit column | `23502` | **500** (enveloped, ERROR-logged) | `INTERNAL_ERROR` |
| Check | `23514`/`23513` | **422** | `CONSTRAINT_VIOLATION` |
| Foreign key | `23503`/`23506` | **409** | `REFERENCE_CONFLICT` |
| Other class-23 | `23xxx` | **409** | `DATA_INTEGRITY_VIOLATION` |
| Unclassifiable | — | **500** (enveloped, ERROR-logged) | `INTERNAL_ERROR` |

The 409-for-unique row restates ADR-0017 §2 (duplicate-unique constraints are stateful collisions). The
not-null split is the new decision this ADR adds: a NOT NULL violation on a server-populated audit column
(`created_by`, `updated_at`, …) means the *server* failed to populate it (e.g. a missing `AuditorAware`,
the #1464 bug) — telling the client `422` would misdirect the retry, so it stays a 500, but an enveloped,
correlated, ERROR-logged one. Client-supplied columns get `422`, consistent with ADR-0017's boundary
(the payload was structurally valid; the domain data policy rejected it). Constraint and column names are
extracted best-effort from the driver message; data values are never included.

### 3. Shared advice lives in pos-web-common; pos-security-common re-exports it

**Decision:** ✅ **Resolved** — The advice lives in a new shared library **pos-web-common**
(`com.positivity.web.common`), not pos-security-common, because services without Spring Security
(pos-event-receiver, the vehicle-reference proxies) must be able to consume it without inheriting
Spring Boot's security auto-configuration lockdown. pos-security-common declares a compile dependency on
pos-web-common, so all gateway-secured services (the overwhelming majority) receive the catch-all with no
pom change; the few non-secured servlet services depend on pos-web-common directly.

### 4. Enforced rather than remembered

**Decision:** ✅ **Resolved** — pos-archunit's `GlobalExceptionHandlerEnforcementTest` fails the build for
any module that declares `@RestController` endpoints but neither depends on pos-web-common (directly or
via pos-security-common) nor declares its own `@ExceptionHandler(Exception.class)` advice.
**pos-api-gateway is exempt**: it is a WebFlux application where the servlet advice does not apply, and
gateway error rendering remains its own concern (ADR-0011).

---

## Alternatives Considered

1. **Copy a catch-all advice into every service**: Rejected — seven independent copies are how the
   current drift happened; the copies already disagree on envelope shape (ProblemDetail vs ApiError).
2. **Advice in pos-security-common directly**: Rejected — pulls `spring-boot-starter-security`
   transitively into services that have no security configuration, activating Boot's default lockdown.
3. **Advice in pos-shared-dtos**: Rejected — that module is deliberately dependency-light DTOs; web MVC
   machinery does not belong there.
4. **Map all not-null violations to 422**: Rejected — a NOT NULL failure on a server-populated audit
   column is a server defect; a 4xx would tell the client to fix a request that was correct.
5. **Enforce via Spring context tests per service**: Rejected — heavier and slower than a single
   reactor-level source/pom scan, with no additional guarantee.

---

## Consequences

### Positive ✅

- ✅ No exception can leave a servlet service as Spring's bare default page: worst case is an enveloped,
  correlated, ERROR-logged 500
- ✅ Unique-constraint collisions surface as retryable 409s; not-null/check violations as 422s — SDK
  integration suites can assert exact statuses on negative paths
- ✅ One implementation to evolve; service advices keep full precedence for domain-specific mapping
- ✅ The requirement is build-enforced, not convention-enforced

### Negative ⚠️

- ⚠️ Services relying on Spring's default 500 body shape (none should, per ADR-0017) see a body change
- ⚠️ The audit-column list for the 422/500 not-null split is maintained centrally and must track any new
  server-populated columns
- ⚠️ SQLSTATE-based classification is driver-dependent for identifier extraction (best-effort names in
  messages), though status mapping itself relies only on standardized class-23 codes

### Neutral

- pos-api-gateway (WebFlux) is out of scope; its error rendering is governed by ADR-0011
- Existing per-service advices continue to work unchanged; migrating them onto shared codes is
  incremental cleanup, not part of this decision

---

## Implementation Notes

- Module: `pos-web-common` (`GlobalApiExceptionHandler`, `DataIntegrityViolations`,
  `WebCommonErrorAutoConfiguration`), auto-configured for servlet web applications only.
- Enforcement: `pos-archunit/src/test/java/com/positivity/archunit/GlobalExceptionHandlerEnforcementTest.java`.
- Envelope codes are documented in `docs/ERROR_ENVELOPE.md` ("Platform fallback codes").
- ADR-0017 remains the status-matrix authority; on acceptance, add a changelog line there
  cross-referencing this ADR for the not-null/check mapping.

---

## Sign-Off

| Role         | Name | Date       | Notes |
| ------------ | ---- | ---------- | ----- |
| Architecture | LMB  | 2026-08-23 |       |
| Backend Lead | LMB  | 2026-08-23 |       |
| API Lead     | LMB  | 2026-08-23 |       |

---

## Timeline

- **Proposed**: 2026-08-23
- **Accepted**: 2026-08-23

---

## Changelog

- **2026-08-23**: Initial draft, authored with the implementing change for issue #1471
- **2026-08-23**: Marked ACCEPTED; implementation merged in #1474
