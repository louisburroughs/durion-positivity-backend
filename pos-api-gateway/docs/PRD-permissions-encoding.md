---
title: "PRD: Compact Permission Bitset Encoding (PERM)"
status: "ACCEPTED"
capability: "security-permissions-encoding"
version: "1.0"
created: "2025-07-24"
authors: ["platform-security"]
modules: ["pos-security-service", "pos-api-gateway"]
---

## Product Requirements Document — Compact Permission Bitset Encoding

**Capability ID:** PERM
**Module Scope:** `pos-security-service` (token issuance) · `pos-api-gateway` (token enforce)
**Platform:** Java 25 · Spring Boot 4.0.3 · Spring Cloud Gateway (WebFlux)
**Priority:** Critical — Security Infrastructure

---

## Executive Summary

### Problem

The current authorization path makes **two synchronous HTTP calls to `pos-security-service` for every authenticated API request** routed through the gateway:

1. `GET /v1/auth/validate?token=…` — token validation check
2. `GET /v1/auth/authorities?token=…` — role/authority expansion

This creates:

- **Latency amplification** on every downstream request (2× round-trip to security-service)
- **Availability coupling** — the gateway cannot serve any authenticated request if security-service is degraded
- **Header trust vulnerability** — `X-User` and `X-Authorities` headers are injected by the gateway but downstream services cannot verify their authenticity; a caller who bypasses the gateway can inject arbitrary identity headers
- **Token bloat risk** — the current token embeds a full string list of `authorities` (e.g., `["ROLE_MANAGER", "pricing:price_book:edit", ...]`) which grows with permission count

### Solution

Replace the runtime security-service round-trip with **stateless, in-process permission decoding at the gateway edge**:

1. **`pos-security-service`** encodes the user's effective permissions into a compact `perm_bits` claim (Base64URL BitSet, ~25 bytes for 215 permissions) and a catalog version `perm_ver` at **token issuance time** — no change to validation latency per request
2. **`pos-api-gateway`** validates the JWT signature locally, decodes `perm_bits` using the versioned `PermissionCode` catalog, converts bits to Spring Security authorities (`PERM_{domain}:{resource}:{action}`), and strips all inbound identity headers before generating gateway-controlled `X-User`/`X-Authorities` headers from verified token claims only

### KPIs

| Metric | Current State | Target |
|--------|--------------|--------|
| Security-service calls per auth'd request | 2 | 0 |
| Gateway auth tail latency (p99) | ~80–200 ms | < 10 ms |
| JWT access token size | ~800–1200 bytes | < 600 bytes |
| Spoofable identity headers reaching downstream | Possible | Zero |
| Permission catalog size | 215 permissions (17 modules) | ≥ 215, extensible to 512+ |

---

## Background and Context

### Current Architecture

```
Client Request
     │
     ▼
pos-api-gateway
     │  1. Parse JWT header (Base64 decode — not verified against signing key fully)
     │  2. GET security-service /v1/auth/validate  ◄─────────────────┐
     │  3. GET security-service /v1/auth/authorities ◄───────────────┘
     │  4. Inject X-User, X-Authorities headers
     ▼
Downstream Service  (trusts X-Authorities header)
```

**Files driving the current implementation:**

- `pos-api-gateway`: `SecurityGatewayConfig.java` — `GlobalFilter` authFilter, `WebClient securityWebClient`, `jwtPreValidationRejectionReason()`
- `pos-security-service`: `JwtServiceImpl.java` — `generateTokenPair()` currently embeds `ROLES` + `AUTHORITIES` string lists in JWT; `validateToken()` + `getAuthoritiesFromToken()` used by gateway
- `pos-security-service`: `Permission.java` entity — no `bitIndex` field; permissions are name-only
- `pos-security-service`: `PermissionRegistryServiceImpl.java` — manages permission catalog; no versioning concept

### Target Architecture

```
Client Request
     │
     ▼
pos-api-gateway
     │  1. Strip inbound X-User, X-Authorities headers
     │  2. Validate JWT signature (local JJWT — no network call)
     │  3. Extract perm_bits + perm_ver from verified claims
     │  4. Decode BitSet via PermissionBitsetCodec (in-process)
     │  5. Map bits → Spring authorities via PermissionCode catalog (perm_ver)
     │  6. Generate X-User, X-Authorities from verified token claims
     ▼
Downstream Service  (trusts gateway-generated headers, cannot be spoofed by caller)
```

### Permission Catalog Facts

- **215 unique permissions** across **17 modules** (as of `scripts/permissions-aggregate.yaml`)
- **Zero duplicate permission names**, zero parse issues in current catalog
- Permission format: `{domain}:{resource}:{action}` (e.g., `workorder:estimate:approve`)
- Modules with largest permission sets: `pos-workorder` (39), `pos-customer` (28), `pos-inventory` (26), `pos-catalog` (18), `pos-shop-manager` (17)
- BitSet size: 215 bits = 27 bytes = ~36 chars Base64URL (well within JWT limits)

---

## Permission Catalog by Module

| Module | Domain | Permission Count |
|--------|--------|-----------------|
| pos-accounting | accounting | 11 |
| pos-catalog | catalog / product | 18 |
| pos-customer | customer / appointment | 28 |
| pos-documents | document | 1 |
| pos-inventory | inventory | 26 |
| pos-invoice | invoice | 3 |
| pos-location | location | 10 |
| pos-mcp-server | mcp | 8 |
| pos-order | order | 13 |
| pos-people | people | 10 |
| pos-price | pricing | 12 |
| pos-security-service | security / role / user / permission | 11 |
| pos-shop-manager | shop / appointments | 17 |
| pos-tax | tax | 2 |
| pos-vehicle-fitment | fitment | 5 |
| pos-vehicle-inventory | vehicle-inventory | 6 |
| pos-workorder | workorder | 39 |
| **Total** | | **215** |

---

## JWT Claim Contract

### Current Claims (access token)

```json
{
  "jti": "018f2a...",
  "sub": "alice",
  "userId": "018f2a...",
  "roles": ["ROLE_SERVICE_WRITER"],
  "authorities": ["workorder:create", "inventory:lookup"],
  "iat": 1711000000,
  "exp": 1711003600
}
```

### Target Claims (access token)

```json
{
  "jti": "018f2a...",
  "sub": "alice",
  "uid": "018f2a...",
  "username": "alice",
  "perm_bits": "AQIDBAUG",
  "perm_ver": 1,
  "iat": 1711000000,
  "exp": 1711003600
}
```

### Claim Specification

| Claim | Type | Required | Description |
|-------|------|----------|-------------|
| `sub` | String | Yes | Canonical identity key — username (preserved for JJWT compatibility) |
| `uid` | String | Yes | Stable user UUID — used for DB joins and audit |
| `username` | String | Yes | Human-readable display name; MUST equal `sub` |
| `perm_bits` | String | Yes | Base64URL-encoded BitSet representing effective permissions |
| `perm_ver` | Integer | Yes | Catalog version; gateway rejects unknown versions |
| `jti` | String | Recommended | Token ID for revocation tracking |
| `iat` | NumericDate | Yes | Issued-at |
| `exp` | NumericDate | Yes | Expiration |

**Removed from token:** `roles`, `authorities` string lists (replaced by `perm_bits`).

### Claim Precedence Rule

Token claims ALWAYS override any caller-supplied request headers. The gateway is the single authority for identity and permission propagation.

---

## Functional Requirements

### FR-1: PermissionCode Catalog Enum

The system MUST define a `PermissionCode` enum that:

- Assigns a **permanent, immutable bit index** to every permission listed in `scripts/permissions-aggregate.yaml`
- Covers all **215 current permissions** with indexes 0–214
- Reserves index 215+ for future permissions
- Groups constants by module domain for readability
- Is annotated with `@deprecated` on retired/unused entries (never remove, never reuse index)
- Exposes: `int bitIndex()`, `String code()` and `static Optional<PermissionCode> fromCode(String)` factory
- Includes a catalog version constant: `public static final int CATALOG_VERSION = 1`

**Location:** `pos-security-service/src/main/java/com/positivity/securityservice/internal/enums/PermissionCode.java`

**Invariant:** Bit indexes are permanent. Once assigned, they are never changed or reused.

---

### FR-2: Permission.bitIndex Column

The `Permission` entity and `permissions` table MUST add a `bitIndex` column:

- Type: `INTEGER`, nullable initially (existing rows), non-null after migration
- Unique constraint: no two active permissions may share a bit index
- Populated via Flyway migration that resolves each `permission.name` against `PermissionCode.fromCode(name)` and writes the resolved `bitIndex`
- Permissions not found in `PermissionCode` are logged as warnings and given index `-1` (excluded from bitset encoding)
- `Permission` entity field: `@Column(name = "bit_index") private Integer bitIndex`

**Migration file:** `pos-security-service/src/main/resources/db/migration/V{next}__add_permission_bit_index.sql`

---

### FR-3: PermissionBitsetCodec

A pure utility class (no Spring bean, no I/O) MUST implement:

```java
package com.positivity.securityservice.internal.domain;

public final class PermissionBitsetCodec {
    public static String encode(Set<PermissionCode> permissions) { ... }
    public static BitSet decode(String encoded) { ... }
    public static Set<PermissionCode> decodeToPermissions(String encoded, int permVer) { ... }
    public static boolean hasPermission(String encoded, PermissionCode permission) { ... }
}
```

- `encode` produces Base64URL without padding
- `decode` accepts Base64URL with or without padding
- `decodeToPermissions` maps bit positions to `PermissionCode` entries validated against `permVer`; unknown bits are logged and skipped
- Both codec and `PermissionCode` reside in `pos-security-service` (not a shared module) — the gateway will decode using only Base64URL + bit-index math, no enum dependency

**Location:** `pos-security-service/src/main/java/com/positivity/securityservice/internal/domain/PermissionBitsetCodec.java`

---

### FR-4: Updated JWT Token Generation

`JwtServiceImpl.generateTokenPair()` MUST be updated to:

1. Resolve effective permissions: `roleAuthorityService.expandRolesToAuthorities(roles)` → resolve to `Set<PermissionCode>` via `PermissionRegistryService.resolvePermissionCodes(authorityStrings)`
2. Encode the set: `PermissionBitsetCodec.encode(permissionCodes)` → `perm_bits` value
3. Embed in access token: `.claim("perm_bits", permBits)`, `.claim("perm_ver", PermissionCode.CATALOG_VERSION)`, `.claim("uid", userId.toString())`, `.claim("username", username)`
4. **Remove** the `AUTHORITIES` string list claim from access token (reduce token size)
5. **Remove** the `ROLES` string list claim from access token
6. Keep `USER_ID` renamed to `uid` for catalog consistency

**Signature change:** `generateTokenPair(String username, UUID userId, Set<String> roles)` → unchanged externally; internal implementation changes.

**Backward compatibility:** The `getAuthoritiesFromToken()` method in `JwtService` MUST decode `perm_bits` and convert to authority strings when `perm_bits` is present; fall back to the `authorities` list claim for old tokens (to be removed after rollout).

---

### FR-5: Permission Catalog Versioning

`PermissionRegistryService` MUST expose:

- `int getCurrentCatalogVersion()` — returns `PermissionCode.CATALOG_VERSION`
- `Optional<PermissionCode> resolveByName(String permissionName)` — looks up by `PermissionCode.fromCode(name)`

A new `PermissionCatalogVersionService` (or extension of `PermissionRegistryService`) MUST:

- Validate that every `Permission` entity loaded at startup has a matching `PermissionCode` entry
- Log warnings for unresolved permissions (unknown to current catalog)
- Expose `GET /v1/permissions/catalog-version` → `{ "version": 1, "permissionCount": 215 }`

---

### FR-6: Diagnostic Decode Endpoint

`pos-security-service` MUST add a protected diagnostic endpoint:

- `POST /v1/permissions/decode` — accepts `{ "perm_bits": "AQIDBA", "perm_ver": 1 }`, returns list of resolved permission names
- Secured with `security:permission:view` authority
- For debugging expired/revoked tokens and support workflows
- Does NOT accept a raw JWT token (avoids logging secrets); accepts only the already-extracted claim values

---

### FR-7: Gateway Local Bitset Decode

`SecurityGatewayConfig.java` MUST be refactored to:

1. **Remove** `WebClient securityWebClient` and all calls to `security-service` auth endpoints (`/v1/auth/validate`, `/v1/auth/authorities`, `/v1/auth/subject`)
2. **Validate JWT signature locally** using the same HMAC-SHA256 secret (injected via `${security.jwt.secret}`)
3. After successful signature validation, **extract claims** from the verified token payload:
   - `sub` → username/subject
   - `uid` → userId
   - `perm_bits` → Base64URL-encoded bitset
   - `perm_ver` → catalog version integer
4. **Decode** `perm_bits` locally: Base64URL decode → `BitSet.valueOf(bytes)`
5. **Map bits to authority strings**: for each set bit position `i`, look up `PermissionCode` by bit index and produce `"PERM_" + permissionCode.code()`
6. **Reject** tokens where `perm_ver` does not match gateway's known catalog version (return 401)
7. **Reject** tokens where `perm_bits` is missing or malformed (return 401)

**Performance contract:** The entire auth filter MUST complete in < 5 ms p99 for valid tokens (no network I/O).

---

### FR-8: Gateway Header Trust Boundary Hardening

`SecurityGatewayConfig.java` MUST:

1. **Strip** all inbound `X-User`, `X-Authorities`, `X-User-Id`, and `X-Roles` headers from every request **before** reaching downstream services — regardless of whether the request is authenticated
2. **After** successful token validation, generate these downstream headers from **token-verified claims only**:
   - `X-User` = verified `sub` claim
   - `X-User-Id` = verified `uid` claim
   - `X-Authorities` = comma-separated authority strings derived from decoded `perm_bits`
3. Public paths (actuator, swagger, api-docs, eureka) MUST also strip inbound identity headers even though they bypass auth

**Security invariant:** A downstream service receiving `X-User` or `X-Authorities` MAY trust those headers as gateway-generated from a verified JWT token.

---

### FR-9: Feature Flags

Three boolean Spring Boot properties MUST control the new behavior for staged rollout:

| Property | Default | Effect when true |
|----------|---------|-----------------|
| `auth.token-identity-required` | `false` | Reject requests where token lacks `perm_bits` or `perm_ver` |
| `auth.strip-inbound-identity-headers` | `true` | Strip X-User/X-Authorities inbound (MUST default on) |
| `auth.reject-header-token-mismatch` | `false` | Reject when inbound identity headers are present before stripping |

Include in `application.yml` with documented comments. Feature flags are **temporary** — to be removed once rollout is complete and stable.

---

### FR-10: Observability

Micrometer counters MUST be added to the gateway auth filter:

| Metric Name | Tag | Description |
|-------------|-----|-------------|
| `auth.token.validation.failure` | `reason` | JWT signature/expiry/format validation failure |
| `auth.perm.decode.failure` | `reason` | `perm_bits` Base64URL decode failure |
| `auth.perm.catalog.version.unknown` | `perm_ver` | Token catalog version not recognized by gateway |
| `auth.user.identity.missing` | `claim` | Required claim (`sub`, `uid`) absent in valid token |
| `auth.header.strip.count` | — | Inbound identity headers stripped (informational) |

All auth failures MUST emit a structured log event (Slf4j MDC) at WARN level including: `path`, `reason`, and `jti` (if present) — **never** log token value, raw `perm_bits`, or any PII.

---

## User Stories

### PERM-001 — Define PermissionCode Enum

**As a** platform security engineer,
**I want** a `PermissionCode` enum that maps all 215 current permissions to permanent bit indexes,
**so that** the bitset encoding contract is captured in code and enforced at compile time.

**Acceptance criteria:**

- Enum covers all 215 permissions from `scripts/permissions-aggregate.yaml`
- Each entry: `ENUM_CONSTANT(bitIndex, "domain:resource:action")`
- Bit indexes 0–214 assigned, sequential with no gaps in initial catalog
- `CATALOG_VERSION = 1` constant declared
- Static factory `fromCode(String)` returns `Optional<PermissionCode>`
- Unit test verifies no two enums share a bit index and all 215 codes are unique

**Files:** `PermissionCode.java` (new) · `PermissionCodeTest.java` (new)

---

### PERM-002 — Add bitIndex to Permission Entity

**As a** platform security engineer,
**I want** the `Permission` database entity to record its bit index from `PermissionCode`,
**so that** the registry can validate catalog completeness and bootstrap initial bitset assignments.

**Acceptance criteria:**

- `Permission.bitIndex` (Integer, nullable, unique when non-null)
- Flyway migration auto-populates `bit_index` for all existing rows where `name` matches a `PermissionCode`
- Permissions missing from `PermissionCode` log a WARN and leave `bit_index` null
- Integration test verifies all seeded permissions have assigned bit indexes

**Files:** `Permission.java` (modified) · `V{N}__add_permission_bit_index.sql` (new) · `PermissionRepositoryTest.java` (modified)

---

### PERM-003 — Implement PermissionBitsetCodec

**As a** platform security engineer,
**I want** a tested codec for encoding and decoding permission bitsets,
**so that** the JWT token issuance and gateway decoding are correct and symmetric.

**Acceptance criteria:**

- `encode(Set<PermissionCode>)` → Base64URL without padding
- `decode(String)` → `BitSet`
- `decodeToPermissions(String, int)` → `Set<PermissionCode>` (valid bits only, ignores unknown positions)
- Round-trip test for all 215 permissions
- Edge cases: empty set encodes to `""` or `"AA"` gracefully; malformed Base64 throws `IllegalArgumentException`
- Codec is a pure utility class (no Spring dependencies)

**Files:** `PermissionBitsetCodec.java` (new) · `PermissionBitsetCodecTest.java` (new)

---

### PERM-004 — Update JWT Token Generation with perm_bits + perm_ver

**As a** platform security engineer,
**I want** the access token to contain `perm_bits` and `perm_ver` instead of role/authority string lists,
**so that** tokens are compact and permissions are self-contained in the signed payload.

**Acceptance criteria:**

- `generateTokenPair()` resolves effective permissions via `PermissionRegistryService`
- Access token includes `perm_bits` (Base64URL), `perm_ver` (integer), `uid` (UUID string), `username`
- Access token does NOT include `roles` or `authorities` string list claims
- Refresh token remains unchanged (only `sub`, `uid`, `type`, timing claims)
- `getAuthoritiesFromToken()` decodes `perm_bits` when present; falls back to legacy `authorities` claim for backward compat during migration
- Token size integration test verifies access token < 600 bytes for a user with 100 permissions

**Files:** `JwtServiceImpl.java` (modified) · `JwtService.java` (interface, possibly updated) · `JwtServiceImplTest.java` (modified)

---

### PERM-005 — Permission Catalog Versioning and Diagnostics

**As a** platform operator,
**I want** the security service to expose its catalog version and support permission decoding for debugging,
**so that** I can diagnose authorization issues without needing raw JWT secrets.

**Acceptance criteria:**

- `GET /v1/permissions/catalog-version` returns `{ "version": 1, "permissionCount": 215 }` (no auth required — informational endpoint)
- `POST /v1/permissions/decode` accepts `{ "perm_bits": "...", "perm_ver": 1 }`, returns `{ "permissions": ["workorder:estimate:approve", ...] }` (requires `security:permission:view` authority)
- Startup validation: logs WARN for any `Permission` entity with null `bitIndex`
- ArchUnit test: `PermissionCatalogVersionService` resides in `internal` package

**Files:** `PermissionController.java` (modified) · `PermissionCatalogVersionService.java` (new) · `PermissionControllerTest.java` (modified)

---

### PERM-006 — Gateway Local JWT Validation

**As a** gateway operator,
**I want** the gateway to validate JWTs locally without calling security-service,
**so that** auth latency is eliminated and the gateway operates independently of security-service availability.

**Acceptance criteria:**

- `SecurityGatewayConfig.authFilter()` validates JWT signature using local HMAC-SHA256 secret
- No `WebClient` calls to security-service during request processing (bean may remain until PERM-007)
- Token validation uses JJWT `JwtParser` with `verifyWith(secretKey)` and `requireExpiry()`
- Invalid signature → 401 (no logging of token value)
- Expired token → 401
- Missing `Authorization: Bearer` header → 401
- Test: mock security-service is offline; gateway still accepts valid tokens and rejects invalid tokens

**Files:** `SecurityGatewayConfig.java` (modified)

---

### PERM-007 — Gateway Bitset Decode and Authority Mapping

**As a** gateway operator,
**I want** the gateway to decode `perm_bits` from validated tokens and produce Spring Security authorities,
**so that** downstream services receive correct authority strings without security-service involvement.

**Acceptance criteria:**

- Gateway extracts `perm_bits` and `perm_ver` from validated token payload
- Rejects token if `perm_ver` != gateway's `PermissionCode.CATALOG_VERSION` (returns 401)
- Rejects token if `perm_bits` is absent (when `auth.token-identity-required=true`)
- Decodes bitset locally: Base64URL → `BitSet.valueOf(bytes)` → iterate set bits → map to `"PERM_{domain}:{resource}:{action}"`
- Gateway uses hard-coded `PermissionCode` knowledge (bit positions 0–214 mapped to authority strings; this mapping is a static array initialized at startup — no Spring bean injection required)
- `X-Authorities` header value = comma-separated decoded authority strings passed to downstream
- Test: token with `perm_bits` for 5 specific permissions → downstream receives exactly those 5 `PERM_*` authorities

**Files:** `SecurityGatewayConfig.java` (modified) · `GatewayPermissionCatalog.java` (new — gateway-side static catalog) · `SecurityGatewayConfigTest.java` (new/modified)

---

### PERM-008 — Header Trust Boundary Hardening

**As a** security engineer,
**I want** the gateway to strip inbound identity headers and regenerate them from verified token claims only,
**so that** downstream services cannot be identity-spoofed by a caller bypassing the gateway.

**Acceptance criteria:**

- `X-User`, `X-User-Id`, `X-Authorities`, `X-Roles` stripped from ALL inbound requests before any processing
- Strip occurs both on public paths and authenticated paths
- After successful token validation, `X-User` = `sub`, `X-User-Id` = `uid`, `X-Authorities` = decoded authorities
- Gateway integration test: send request with spoofed `X-Authorities: admin:*` header → downstream receives only token-derived authorities, never the spoofed value
- Security regression test verifying gateway cannot be bypassed via header injection

**Files:** `SecurityGatewayConfig.java` (modified) · `SecurityGatewayConfigTest.java` (modified)

---

### PERM-009 — Feature Flags and Rollout Controls

**As a** platform operator,
**I want** feature flags to control staged migration from legacy header-trust to bitset-claim-based auth,
**so that** the rollout can be staged safely and rolled back without code changes.

**Acceptance criteria:**

- Three properties configurable via `application.yml` and environment variables:
  - `auth.token-identity-required` (default: `false`)
  - `auth.strip-inbound-identity-headers` (default: `true`)
  - `auth.reject-header-token-mismatch` (default: `false`)
- Flags are consumed via `@ConfigurationProperties(prefix = "auth")` class bound to `SecurityGatewayConfig`
- Documentation comments in `application.yml` explain each flag's effect and rollout order
- Test verifies each flag independently: flag off → legacy behavior; flag on → new behavior

**Files:** `SecurityGatewayConfig.java` (modified) · `GatewayAuthProperties.java` (new) · `application.yml` (modified)

---

### PERM-010 — Gateway Auth Observability

**As a** platform operator,
**I want** micrometer metrics and structured logs for all auth failure modes,
**so that** I can detect authorization anomalies and debug failures without inspecting token payloads.

**Acceptance criteria:**

- Five counters registered (see FR-10 table)
- All counters use tag key `reason` to distinguish failure sub-types
- Auth filter logs WARN on every rejected request: fields `path`, `reason`, `jti` (if present) — no token value, no perm_bits value, no PII
- Counter names follow convention `auth.{noun}.{noun}.{outcome}` (all lowercase, dot-separated)
- Integration test: trigger each failure path, assert counter increments

**Files:** `SecurityGatewayConfig.java` (modified) · `SecurityGatewayConfigTest.java` (modified)

---

### PERM-011 — Security Regression Test Suite

**As a** QA engineer,
**I want** a comprehensive test suite covering every specified auth failure and success path,
**so that** no regression can silently re-enable legacy header trust or disable permission enforcement.

**Acceptance criteria:**

- Unit tests: bitset encode/decode round-trip for all 215 permissions; catalog version check
- Integration tests (WebTestClient, mock gateway):
  - Valid token + correct `perm_bits` → 200 + correct X-Authorities downstream
  - Invalid signature → 401
  - Expired token → 401
  - Missing `perm_bits` (when flag enabled) → 401
  - Unknown `perm_ver` → 401
  - Malformed Base64 `perm_bits` → 401
  - Spoofed `X-Authorities` header stripped → downstream sees only token-derived authorities
  - Spoofed `X-User` header stripped
  - Public path (actuator) → passthrough without auth (identity headers still stripped)
- ArchUnit: `SecureGatewayConfig` does not import `WebClient` (enforces no-security-service dependency) after migration
- All tests pass in CI without a running security-service instance (mock JWT secret only)

**Files:** `SecurityGatewayConfigTest.java` (new/expanded) · `PermissionBitsetCodecTest.java` · `PermissionCodeTest.java`

---

## Implementation Phases

| Phase | Story | Description | Validation Gate |
|-------|-------|-------------|----------------|
| 1 · Contract | PERM-001 | `PermissionCode` enum + catalog version | All 215 permissions uniquely indexed |
| 1 · Contract | PERM-003 | `PermissionBitsetCodec` utility | Round-trip unit tests pass |
| 2 · Issuance | PERM-002 | `Permission.bitIndex` entity column + migration | All existing permissions have non-null bitIndex |
| 2 · Issuance | PERM-004 | JWT `perm_bits` + `perm_ver` claim in tokens | Token size < 600 bytes; backward-compat mode active |
| 3 · Catalog | PERM-005 | Catalog version endpoint + decode diagnostic | Endpoints return correct data; startup warnings 0 |
| 4 · Gateway | PERM-006 | Gateway local JWT validation (no security-service) | Valid tokens pass; invalid rejected; security-service offline test passes |
| 4 · Gateway | PERM-007 | Gateway bitset decode → authority mapping | Integration tests: correct authorities propagated |
| 5 · Hardening | PERM-008 | Header stripping + gateway-generated headers | Spoofing test fails (cannot inject identity) |
| 6 · Rollout | PERM-009 | Feature flags | Each flag independently validated |
| 7 · Obs. | PERM-010 | Metrics + structured logs | All 5 counters verified in integration test |
| 8 · Regression | PERM-011 | Full security regression suite | All 12+ test scenarios pass; CI green |

---

## Architecture Invariants

| # | Invariant |
|---|-----------|
| 1 | `PermissionCode` bit indexes are **permanent** — never reused or changed after initial assignment |
| 2 | Gateway NEVER makes network calls to `pos-security-service` during request processing after Phase 4 |
| 3 | `X-User`, `X-Authorities` reaching downstream are **ALWAYS** generated from a cryptographically verified JWT — never from caller-supplied headers |
| 4 | A token with unknown `perm_ver` MUST be rejected (fail-closed), not silently treated as empty permissions |
| 5 | A token with malformed `perm_bits` MUST be rejected (fail-closed)  |
| 6 | Auth failure reasons MUST be logged without leaking token values or PII |
| 7 | Feature flags are transitional — all three MUST be removed after rollout stabilization |
| 8 | `PermissionBitsetCodec` MUST be a pure utility (no Spring, no I/O) |
| 9 | `GatewayPermissionCatalog` (gateway-side bit-index→authority mapping) is a static array initialized once at startup — immutable after init |
| 10 | Downstream services MUST NOT be updated as part of this PRD — they already trust `X-Authorities` header |

---

## ADR References

Before implementing, agents MUST review the following ADRs in `durion/docs/adr/`:

- `0011-api-gateway-security-architecture.adr.md` — gateway security model
- `0014-gateway-internal-service-security.adr.md` — internal service-to-service trust boundary
- `0017-api-controller-http-response-codes.adr.md` — 401 vs 403 HTTP response codes for auth failures
- `0018-audit-actor-fields-from-security-context.adr.md` — how services derive actor for audit from security context

---

## Open Questions

| # | Question | Impact | Owner |
|---|----------|--------|-------|
| Q1 | Should `sub` remain the username string, or migrate to UUID for canonical identity? | Changes downstream parsing of X-User header | Security lead |
| Q2 | Which downstream services currently read `X-Authorities` as a string list vs individual values? | May require downstream header format compatibility | Service owners |
| Q3 | Is a short token TTL (currently 1 hour) sufficient revocation strategy, or is `jti` revocation required for sensitive paths? | Affects token security vs complexity tradeoff | Security lead |
| Q4 | After removing `authorities` claim, what is the migration window before old tokens (without `perm_bits`) are rejected? | Determines feature flag duration | Platform ops |
| Q5 | Should `pos-api-gateway` carry its own copy of `PermissionCode` as a static catalog, or share a module dependency? | Affects build coupling; current recommendation: static array, no shared module | Architect |

---

## Agent Task Breakdown

### Lead Coder

- Decompose PERM-001–011 into specialist work packages in sequence order
- Ensure PERM-001 and PERM-003 complete before any other story begins (contract gate)
- Coordinate feature flag naming with ops before PERM-009 implementation
- Enforce architecture invariants in all code review

### Domain Data Coder (pos-security-service)

**PERM-001:** `PermissionCode.java` — enum with all 215 entries, bit indexes 0–214, `CATALOG_VERSION = 1`, `fromCode(String)` factory, `bitIndex()` + `code()` accessors

**PERM-002:** `Permission.java` — add `Integer bitIndex` field, `@Column(name = "bit_index")`, unique constraint annotation. `V{N}__add_permission_bit_index.sql` — `ALTER TABLE permissions ADD COLUMN bit_index INT`, then UPDATE from PermissionCode mapping

**PERM-003:** `PermissionBitsetCodec.java` — encode/decode/hasPermission/decodeToPermissions in `internal/domain`

**PERM-004:** `JwtServiceImpl.java` — update `generateTokenPair()`; inject `PermissionRegistryService`; encode `perm_bits`; add `perm_ver`, `uid`, `username` claims; keep backward-compat `getAuthoritiesFromToken()`

**PERM-005:** `PermissionCatalogVersionService.java` — startup validation, catalog version reporting. `PermissionController.java` — add `GET /v1/permissions/catalog-version`, `POST /v1/permissions/decode`

### API Surface Coder (pos-security-service)

**PERM-005 (controller layer):**

```
GET  /v1/permissions/catalog-version
     → 200 { "version": 1, "permissionCount": 215 }

POST /v1/permissions/decode
     Request:  { "perm_bits": "AQIDBA", "perm_ver": 1 }
     Response: { "permissions": ["workorder:create", "inventory:lookup"] }
     Auth:     security:permission:view
```

Add `@EmitEvent(id = "PERMISSION_DECODE_EXECUTE", apiVersion = "1")` on the decode endpoint.

### Domain Data Coder (pos-api-gateway)

**PERM-006:** Refactor `SecurityGatewayConfig.authFilter()` — remove WebClient, add local JJWT validation

**PERM-007:** `GatewayPermissionCatalog.java` — static `String[] AUTHORITY_BY_BIT` array (215 entries), initialized from the same bit-index ordering as `PermissionCode`. `SecurityGatewayConfig` — decode `perm_bits`, iterate set bits, map to `PERM_{authority}`

**PERM-008:** `SecurityGatewayConfig` — add inbound header stripping (before filter chain entry), token-derived header generation (after validation)

**PERM-009:** `GatewayAuthProperties.java` — `@ConfigurationProperties(prefix = "auth")` with three boolean fields. Wire into `SecurityGatewayConfig`. Update `application.yml`

**PERM-010:** Inject `MeterRegistry`; register five counters with tags; add Slf4j MDC logging for failures

### Backend Testing Agent

**PERM-001:** `PermissionCodeTest` — verify 215 entries, no duplicate bit indexes, no duplicate codes, `fromCode` round-trips

**PERM-003:** `PermissionBitsetCodecTest` — encode/decode round-trips, empty set, single permission, all 215, malformed input throws

**PERM-004:** `JwtServiceImplTest` — updated token contains `perm_bits`, `perm_ver`, `uid`; does not contain `roles`, `authorities` lists; backward-compat decode works on old-format token; token size < 600 bytes

**PERM-006, PERM-007, PERM-008:** `SecurityGatewayConfigTest` — all 12 scenarios in PERM-011 acceptance criteria using `WebTestClient` + embedded gateway test setup

**PERM-010:** Counter increment tests for each auth failure path

### Documentation Agent

Update `pos-api-gateway/README.md`:

- Add "Authorization Architecture" section describing the bitset approach and claim contract
- Document feature flag properties and rollout order
- Document `GatewayPermissionCatalog` and how to regenerate when new permissions are added

Update `pos-security-service/README.md`:

- Document updated JWT claim schema (before/after)
- Document diagnostic endpoints
- Document `PermissionCode` catalog evolution rules (append-only, never reuse)

---

## Exit Criteria

The capability is complete when ALL of the following are true:

- [ ] `PermissionCode` enum covers all 215 permissions; `CATALOG_VERSION = 1`
- [ ] All existing permissions in database have non-null `bit_index`
- [ ] Access tokens contain `perm_bits` + `perm_ver`; no `roles`/`authorities` lists
- [ ] Gateway validates tokens locally with zero security-service calls
- [ ] Gateway strips inbound `X-User`, `X-Authorities` on all paths
- [ ] All PERM-011 regression tests pass in CI
- [ ] `auth.strip-inbound-identity-headers=true` by default in production config
- [ ] All 5 micrometer auth counters active and visible in observability dashboard
- [ ] `pos-api-gateway` README updated with authorization architecture section
- [ ] `pos-security-service` README updated with JWT claim schema and catalog evolution rules

---

## Rollout Sequence

1. **Deploy `pos-security-service`** with `perm_bits`/`perm_ver` token issuance — old clients continue to receive `perm_bits` in new tokens; `getAuthoritiesFromToken()` backward-compat active
2. **Deploy `pos-api-gateway`** with `auth.strip-inbound-identity-headers=true` (default on) and `auth.token-identity-required=false` — gateway switches to local validation but still accepts tokens without `perm_bits`
3. **Monitor** `auth.token.validation.failure` and `auth.perm.decode.failure` counters for 24h; confirm zero failures
4. **Enable** `auth.token-identity-required=true` — gateway now rejects tokens without `perm_bits` (all newly issued tokens will have it)
5. **Monitor** for 24h; confirm continued zero failures
6. **Remove** feature flags from code; remove legacy `authorities`/`roles` compatibility paths from `JwtServiceImpl`; final PR marks capability complete
