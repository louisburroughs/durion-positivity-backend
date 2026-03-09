## pos-security-service: Permission Encoding (Greenfield)

### Purpose

This module is the source of truth for identity, roles, permissions,
and JWT issuance. Authorization data in access tokens is encoded as a
compact permission bitset.

### JWT Contract

Access tokens must include:

- `sub`: stable subject identifier
- `personId`: stable person identifier for audit lineage
- `jti`: token identifier
- `iat`: issued-at timestamp
- `exp`: expiration timestamp
- `perm_bits`: Base64URL-encoded permission bitset
- `perm_ver`: integer permission catalog version

Optional:

- `roles`: informational only, not used for authorization checks

Do not include an `authorities` claim in the token for greenfield mode.

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
