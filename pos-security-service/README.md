# pos-security-service

Identity, authentication, and authorisation service for the Durion Positivity ETSMS platform. Issues JWTs with compact permission bitset claims, manages users, roles, and permission assignments, enforces lockout policy, and provides a self-registration review workflow.

## Responsibilities

- Authenticate users and issue JWTs containing `perm_bits` (Base64URL BitSet) and `perm_ver`
- Manage users, roles, and role-to-permission assignments
- Maintain the canonical permission catalog with stable bit indexes
- Enforce configurable account lockout (threshold, rolling window, cooldown, progressive backoff)
- Handle self-registration requests and admin review/approval
- Provide admin account state endpoints (unlock, enable/disable, expire)
- Register per-module permissions at startup from `permissions.yaml` manifests
- Emit audit events for all state-changing operations via `pos-events`

## Authorization Model

There is one authorization model, and it is the database. A user's authorities are resolved
along a single chain:

```
users -> user_roles -> roles -> role_permissions -> permissions
```

`RoleAuthorityService` reads that chain at login, `JwtService` encodes the resulting permission
names into the `perm_bits` claim, and pos-api-gateway decodes `perm_bits` back into the
`X-Authorities` header that satisfies `@PreAuthorize("hasAuthority('crm:party:view')")` checks in
every downstream service.

Two consequences follow:

- **A role grants exactly what `role_permissions` says it grants.** There is no compiled
  role-to-authority map. Earlier revisions expanded roles through a hardcoded switch in
  `RoleAuthorityServiceImpl`; that map is retired, so changing what a role can do is a data
  change, not a code change and a redeploy.
- **Principals fail closed.** An unknown role, or a role with no rows in `role_permissions`,
  contributes only its `ROLE_` authority and no permissions at all.

Only permissions carrying a `PermissionCode` bit index can travel in a token. A grant for a
permission outside that catalog is silently absent from `perm_bits`, so `PermissionCode` is the
effective ceiling on what a role can be given.

### How role grants are provisioned

Two supported paths, both writing the same `role_permissions` table:

1. **Baseline seed** — `db/migration/R__seed_role_permissions.sql`, a repeatable Flyway
   migration that runs in every environment where Flyway runs. It resolves both role and
   permission by **name** (never by hardcoded UUID), is idempotent via `ON CONFLICT DO NOTHING`,
   and is purely additive: it never deletes a grant, so anything an operator added through the
   admin API survives re-runs. If a baseline role or permission name fails to resolve, the
   migration aborts naming what is missing rather than silently under-granting authority.
2. **Admin API** — `PUT /v1/roles/{roleId}/permissions/{permissionKey}` and the corresponding
   `DELETE`, for per-environment adjustments on top of the baseline.

To change the baseline, edit the seed file. Flyway re-applies a repeatable migration when its
checksum changes, so the new grants land on the next startup. Revocations are **not** picked up
this way — the seed only inserts — so removing a capability means a `DELETE` through the admin
API, or a versioned migration.

`RolePermissionBaselineTest` parses the seed and pins its contents;
`role-authority-legacy-baseline.tsv` is the expansion the retired hardcoded switch produced for
the roles that can actually be assigned, so any drift from the historical grant set fails the
build. It also asserts that every role the seed grants to is created by a migration — granting to
a role that does not exist yet aborts startup, because the join resolves nothing and the seed's
own assertion raises.

Note the ordering trap this guards against: `RoleInitializer` creates `GENERAL_MANAGER` and
`MANAGER` from Java, but it is an `@PostConstruct` bean and runs *after* Flyway. The seed
therefore creates those two roles itself rather than relying on bean lifecycle.

### Role policy

| Role | Baseline |
| --- | --- |
| `ADMIN` | All domains. The intentional blast-radius role. |
| `SYSTEM_ADMINISTRATOR` | **Security and MCP administration only** — `security:*`, plus MCP administration (`mcp:system_prompt:*`, `mcp:llm_api:*`, `mcp:tool:manage`, `mcp:document:ingest`) and the assistant entrypoints. Deliberately *not* a superuser: it holds no accounting, catalog, workorder, inventory, or shop authority, and it does **not** auto-acquire newly registered permissions. Widening it is an explicit edit to the seed. |
| `LOCATION_MANAGER`, `SERVICE_ADVISOR`, `TECHNICIAN`, `DISPATCHER`, `ACCOUNTING_ASSOCIATE`, `ACCOUNT_MANAGER`, `MANAGER`, `GENERAL_MANAGER` | Least privilege, scoped to the role's job function. |
| `ACCOUNTANT`, `AP_CLERK`, `CONTROLLER`, `CSR`, `FLEET_MANAGER`, `GL_ANALYST` | **Not granted, and not created.** The retired hardcoded switch expanded these, but no migration or initializer creates the role, and `user_roles` / `role_assignments` are foreign-keyed to `roles(id)` — so no user could ever hold one. They were unreachable branches, documentation personas rather than security roles. To make one real, create the role first, then grant it. |
| `CUSTOMER`, `SELF_SERVICE_CUSTOMER`, `SHOP_MANAGER`, `SECURITY_ADMIN`, `READ_ONLY_SCHEDULER`, `INVENTORY_LEAD`, `INVENTORY_MANAGER`, `INVENTORY_CONTROLLER` | **Assistant entrypoints only.** No domain capability; granting them any is still a product decision. |

### Assistant baseline

Every role that exists receives four conversational entrypoints:

| Permission | Meaning |
| --- | --- |
| `mcp:chat:execute` | Synchronous chat request via the Spring AI assistant runtime |
| `mcp:chat:stream` | Streaming SSE chat request |
| `nlti:request:submit` | Submit a natural-language task-interface request |
| `nlti:request:read` | Read submitted NLTI request status |

These grant reach to the assistant, not to data: the assistant enforces domain permissions per
request, so a role that cannot read an invoice still cannot read one by asking for it. They are
applied to **all** roles, including the customer-facing `CUSTOMER` and `SELF_SERVICE_CUSTOMER`,
which previously held nothing at all — so external self-service users can now reach the assistant
and submit NLTI requests. If that is not wanted, remove those two roles from the universal list in
the seed; `RolePermissionBaselineTest.everyRoleReceivesTheAssistantBaseline` pins the current
policy and will need updating alongside.

MCP administration — `mcp:system_prompt:*`, `mcp:llm_api:*`, `mcp:tool:manage`,
`mcp:document:ingest`, and for `ADMIN` also `mcp:tool:view` — is restricted to `ADMIN` and
`SYSTEM_ADMINISTRATOR`, and a test asserts no other role holds any of it.

### Role grants vs. role assignments

Three tables are easy to confuse:

| Table | Meaning | Consumed by |
| --- | --- | --- |
| `role_permissions` | **role → permission** grants | Token issuance (`RoleAuthorityService`), `AuthorizationService`, `RoleManagementService` |
| `user_roles` | **user → role**, unscoped | Token issuance, `AuthorizationService.authorizePerson` |
| `role_assignments` | **user → role**, with `scope_type`, optional location scope and effective dating | `RoleManagementService.getUserPermissions` / `check-permission` only |

Location scope and effective dating live on `role_assignments` and are honoured by
`RoleManagementService.userHasPermission`. They do **not** narrow the grants in a JWT: token
issuance takes the union of the roles a user holds and encodes every permission those roles
grant. Location-sensitive decisions must therefore be enforced by the owning service, or asked
of `GET /v1/roles/check-permission`, rather than assumed from the token.

## Key Classes

- `JwtService` — issues and validates JWTs; encodes `perm_bits` via `PermissionBitsetCodec`
- `AuthenticationService` — login flow; delegates to Spring Security `AuthenticationManager`
- `LockoutService` — configurable failed-login lockout with automatic and manual unlock
- `PermissionService` — permission catalog management (bit index assignment)
- `RoleManagementService` — role CRUD and role-to-permission assignment
- `RoleAuthorityService` — resolves a role's authorities from persisted `role_permissions` grants
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
