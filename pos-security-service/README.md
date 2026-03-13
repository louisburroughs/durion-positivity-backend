## pos-security-service: Permission Encoding (Greenfield)

### Purpose

This module is the source of truth for identity, roles, permissions,
and JWT issuance. Authorization data in access tokens is encoded as a
compact permission bitset.

### JWT Token Contract

Access tokens issued by `pos-security-service` include the following
claims used by the gateway and services:

- `sub`: subject / username
- `uid`: user UUID (preferred user identifier for downstream services)
- `jti`: token identifier
- `iat`, `exp`: issued-at and expiry timestamps
- `perm_bits`: Base64URL-encoded permission BitSet (Base64URL, no padding)
- `perm_ver`: integer permission catalog version (gateway verifies this)

Optional/legacy claims handled for migration:

- `roles`: informational only; not relied on for permission checks
- legacy `userId` claim is recognised when present for compatibility

Notes:

- Do not include an `authorities` claim for greenfield PERM mode; the
  gateway decodes `perm_bits` and maps bit indexes to canonical
  authority strings.

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

2. Add bitset codec.

- Create `PermissionBitsetCodec` under `internal/security` or `internal/service`.
- Encode/decode using `BitSet` + Base64URL without padding.

3. Add effective permission resolver.

- Resolve `User -> roles -> permissions` from repositories.
- Return permission names and bit indexes for token construction.

4. Rewrite token issuance.

- Update `JwtService` claim constants to include `perm_bits` and `perm_ver`.
- In `JwtServiceImpl`, generate `perm_bits` and `perm_ver` claims.
- Remove legacy token claim generation for `authorities`.

5. Update JWT extraction endpoints.

- Remove `/v1/auth/authorities` in `JwtController` for greenfield mode.
- Keep `/v1/auth/validate`, `/v1/auth/subject`, and `/v1/auth/person-id`.
- Add `/v1/auth/permissions` only if explicitly needed for admin/debug use.

6. Update gateway integration.

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
