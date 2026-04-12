## pos-security-service: Permission Encoding (Greenfield)

### Purpose

This module is the source of truth for identity, roles, permissions,
and JWT issuance. Authorization data in access tokens is encoded as a
compact permission bitset.

### JWT Token Contract

Access tokens issued by `pos-security-service` include the following required claims used by the gateway and services:

- `sub`: subject / username
- `uid`: stable user UUID identifier
- `jti`: token identifier
- `iat`, `exp`: issued-at and expiry timestamps
- `perm_bits`: Base64URL-encoded permission BitSet (Base64URL, no padding)
- `perm_ver`: integer permission catalog version (gateway verifies this)
- `roles`: normalized role list for frontend compatibility (`ROLE_` prefix preserved/normalized)

Optional access-token claims:

- `personId`: linked CRM person UUID when available

Notes:

- The token contract is explicit: `perm_bits` and `perm_ver` are required in greenfield PERM mode. `authorities` is not issued by `JwtService` in greenfield mode and should not be relied upon.
- `roles` may be emitted as informational compatibility data for clients, but authorization must use `perm_bits`/`perm_ver`. Legacy `userId` may be accepted for migration where explicitly configured.

### Authentication Flow

- Login uses Spring Security's `AuthenticationManager` + a `UserDetailsService` implementation. Credentials are verified by the configured `AuthenticationProvider` and `UserDetailsService` loads user details and account-state flags.
- On successful authentication `JwtService` issues a token containing the required claims above.

### Account State Flags

- The `User` entity now includes typed account-state flags: `enabled`, `accountNonLocked`, `accountNonExpired`, `credentialsNonExpired` and metadata: `failedAttempts`, `lockedAt`, `lastSuccessfulLogin`.
- Semantics:
  - `enabled`: user allowed to authenticate (admin can enable/disable)
  - `accountNonLocked`: false when the account is locked due to failed logins
  - `accountNonExpired`: false when account is administratively or automatically expired
  - `credentialsNonExpired`: false when password/credentials are expired
- These flags are enforced by Spring Security during authentication and reflected in the token issuance flow.

### Lockout Policy

- A `LockoutService` implements configurable lockout behaviour: failure threshold, rolling window, progressive backoff, automatic cooldown unlock, and admin unlock.
- Configuration keys (example): `security.lockout.threshold`, `security.lockout.windowMinutes`, `security.lockout.cooldownMinutes`, `security.lockout.backoffMultiplier`.
- On lockout the `accountNonLocked` flag is set and `lockedAt` recorded; automatic unlock clears the flag after cooldown; admins may unlock via the admin API.

### Admin Account-State API

- Admin endpoints (secured with `@PreAuthorize("hasRole('ADMIN')")`):
  - `POST /v1/users/{id}/unlock` — unlock account (200 OK)
  - `POST /v1/users/{id}/enable` — enable account (200 OK)
  - `POST /v1/users/{id}/disable` — disable account (200 OK)
  - `POST /v1/users/{id}/expire-account` — mark account expired (200 OK)
  - `POST /v1/users/{id}/expire-credentials` — expire credentials (200 OK)
- Endpoints follow the platform error envelope and HTTP status guidelines from ADR-0017 (e.g., `403` for unauthorized, `404` when user not found).

### Flyway Migrations

- Flyway migrations add the new columns to the `users` table with safe defaults; see `src/main/resources/db/migration/` for migration scripts that backfill existing rows.
- Reference/bootstrap seed data is now managed via repeatable migration `R__seed_reference_security.sql` (generated-source aligned, idempotent `ON CONFLICT` upserts).
- Seeded admin password hash is supplied at runtime via Flyway placeholder `seed_admin_password_hash`, backed by environment variable `SECURITY_SEED_ADMIN_PASSWORD_HASH` (do not commit real hashes in SQL).

### Metrics

- Micrometer counters added for authentication observability:
  - `auth.login.success` — increments on successful login
  - `auth.login.failure` — tagged by `reason` (e.g., `BAD_CREDENTIALS`, `LOCKED`, `DISABLED`, `EXPIRED_CREDENTIALS`)

### Events

- All state-changing operations are annotated with `@EmitEvent` and the service registers `SecurityEventTypes` at startup. The registry contains 29 event types covering login, lockout, admin state changes, and token lifecycle events.

### Exception Handling

- `GlobalExceptionHandler` maps authentication/account state exceptions to typed error codes and standard error envelopes. Handled exceptions include `LockedException`, `DisabledException`, `AccountExpiredException`, `CredentialsExpiredException`, `BadCredentialsException`, and `AuthorizationDeniedException`.
- `JsonAuthenticationEntryPoint` and `JsonAccessDeniedHandler` produce JSON error responses for authentication/authorization failures and are wired into `SecurityConfig.exceptionHandling()`.

### PermissionCode & Catalog

- Canonical permission codes and stable bit assignments live in
  `com.positivity.securityservice.internal.enums.PermissionCode`.
- The system currently exposes an append-only permission catalog (215
  permission codes). Bit indexes are stable and must never be reused.

### Catalog & Decode Endpoints

- `GET /v1/permissions/catalog-version` — returns the active catalog
  version and total permission count. (No auth required.)
- `POST /v1/permissions/decode` — diagnostic endpoint that decodes a
  `perm_bits` value for inspection. Requires `security:permission:view`.

### PermissionBitsetCodec

- Encoding/decoding utilities are implemented in
  `com.positivity.securityservice.internal.domain.PermissionBitsetCodec`.
  The codec converts a set of `PermissionCode` values to a compact
  BitSet and serialises it as Base64URL without padding, and provides
  the reverse decode operation used by `JwtServiceImpl` and admin
  diagnostics.

Example — token issuance (conceptual):

```java
Set<PermissionCode> codes = ...; // resolved from roles/assignments
String permBits = PermissionBitsetCodec.encode(codes);
Jwts.builder()
    .subject(username)
    .claim("uid", userId.toString())
    .claim("perm_bits", permBits)
    .claim("perm_ver", PermissionCode.CATALOG_VERSION)
    .signWith(secretKey)
    .compact();
```

### Greenfield Rules

- Compute effective permissions from persisted assignments only.
- Do not trust caller-supplied role lists for token authorization claims.
- Permission bit indexes are append-only.
- Never reuse retired bit indexes.
- Token permission interpretation is tied to `perm_ver`.

### Data Model Requirements

Extend `permissions` catalog records with:

- `bit_index` (`INT`, unique, not null)
- `catalog_version` (`INT`, not null)

Catalog behavior:

- Assign the next available `bit_index` on registration.
- Keep existing indexes stable forever.
- Increment `catalog_version` only when catalog meaning changes.

### Implementation Checklist

1. Add permission bit metadata.

- Update Flyway migrations in `src/main/resources/db/migration/`.
- Update `Permission` entity in
  `src/main/java/com/positivity/securityservice/internal/entity/Permission.java`.

1. Add bitset codec.

- Create `PermissionBitsetCodec` under `internal/security` or `internal/service`.
- Encode/decode using `BitSet` + Base64URL without padding.

1. Add effective permission resolver.

- Resolve `User -> roles -> permissions` from repositories.
- Return permission names and bit indexes for token construction.

1. Rewrite token issuance.

- Update `JwtService` claim constants to include `perm_bits` and `perm_ver`.
- In `JwtServiceImpl`, generate `perm_bits` and `perm_ver` claims.
- Remove legacy token claim generation for `authorities`.

1. Update JWT extraction endpoints.

- Keep `/v1/auth/roles` as the frontend compatibility endpoint.
- Keep `/v1/auth/authorities` only for controlled legacy compatibility (deprecated for new integrations).
- Keep `/v1/auth/validate`, `/v1/auth/subject`, and `/v1/auth/person-id`.
- Add `/v1/auth/permissions` only if explicitly needed for admin/debug use.

1. Update gateway integration.

- Gateway should decode `perm_bits` from validated JWTs.
- Convert decoded permission bits to canonical authority strings for downstream
  `X-Authorities` propagation.

### Service Notes

- `RoleAuthorityServiceImpl` currently hardcodes mappings. Replace this with
  catalog-backed resolution for token claims.
- `JwtServiceImpl` is the only writer of JWT claims and should remain the
  canonical implementation point for token contract changes.

### Testing Requirements

Update and/or add tests for:

- JWT claim presence: `perm_bits`, `perm_ver`
- Bitset round-trip encoding/decoding correctness
- Effective permission derivation from database assignments
- Token refresh preserving permission semantics
- Controller contract updates after removing authorities endpoint

Suggested files to update first:

- `src/test/java/com/positivity/securityservice/service/JwtServiceImplTest.java`
- `src/test/java/com/positivity/securityservice/ContractBehaviorIT.java`

### Operational Guidance

- Keep token size compact by relying on `perm_bits`.
- For an expected catalog size of around 170 permissions, the raw bitset is
  about 22 bytes before Base64URL encoding.
- Treat catalog index stability as a hard compatibility guarantee.
