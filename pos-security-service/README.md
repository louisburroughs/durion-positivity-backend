# pos-security-service

Identity, authentication, and authorisation service for the Durion POS platform. Issues JWTs with compact permission bitset claims, manages users, roles, and permission assignments, enforces lockout policy, and provides a self-registration review workflow.

## Responsibilities

- Authenticate users and issue JWTs containing `perm_bits` (Base64URL BitSet) and `perm_ver`
- Manage users, roles, and role-to-permission assignments
- Maintain the canonical permission catalog with stable bit indexes
- Enforce configurable account lockout (threshold, rolling window, cooldown, progressive backoff)
- Handle self-registration requests and admin review/approval
- Provide admin account state endpoints (unlock, enable/disable, expire)
- Register per-module permissions at startup from `permissions.yaml` manifests
- Emit audit events for all state-changing operations via `pos-events`

## Key Classes

- `JwtService` — issues and validates JWTs; encodes `perm_bits` via `PermissionBitsetCodec`
- `AuthenticationService` — login flow; delegates to Spring Security `AuthenticationManager`
- `LockoutService` — configurable failed-login lockout with automatic and manual unlock
- `PermissionService` — permission catalog management (bit index assignment)
- `RoleManagementService` — role CRUD and role-to-permission assignment
- `SelfRegistrationService` / `SelfRegistrationReviewService` — user self-registration and admin review

## API Endpoints

- `POST /v1/auth/login` — authenticate and receive JWT
- `GET /v1/auth/validate` — validate a JWT
- `GET /v1/auth/subject` — extract subject from JWT
- `GET /v1/permissions/catalog-version` — active catalog version (public)
- `POST /v1/permissions/decode` — decode a `perm_bits` value (auth: `security:permission:view`)
- `GET /v1/roles` — list roles
- `POST /v1/roles` — create a role
- `POST /v1/roles/{roleId}/permissions/{permissionKey}` — assign permission to role
- `DELETE /v1/roles/{roleId}/permissions/{permissionKey}` — remove permission from role
- `GET /v1/users/{id}` — retrieve a user
- `POST /v1/users/{id}/unlock` — admin: unlock account
- `POST /v1/users/{id}/enable` / `disable` — admin: enable/disable account
- `GET /v1/auth/authorization/decision` — check if caller has a permission

## Configuration

| Property                            | Default      | Description                           |
| ----------------------------------- | ------------ | ------------------------------------- |
| `SPRING_DATASOURCE_URL`             | required     | PostgreSQL connection URL             |
| `EUREKA_SERVER_URL`                 | required     | Eureka service discovery URL          |
| `SECURITY_SEED_ADMIN_PASSWORD_HASH` | required     | BCrypt hash for seed admin user       |
| `security.lockout.threshold`        | configurable | Failed login threshold before lockout |

## Dependencies

- `pos-security-common` — shared security constants and filter
- `pos-events` — `@EmitEvent` annotation and event registration

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`. Seed admin password hash is injected as a Flyway placeholder; never commit real hashes in SQL files.

## Development

```bash
./mvnw -pl pos-security-service -am spring-boot:run
```
