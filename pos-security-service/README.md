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

Since #1440, SQL migrations are the only source of the *baseline* roles this seed grants to:
`R__seed_reference_security.sql` inserts every one of them with a pinned UUID (repeatables run
in filename order, so it runs before the grants seed). The retired `RoleInitializer` bean used to
create the manager and inventory roles from Java at `@PostConstruct` — *after* Flyway — which
forced the grants seed to re-create them defensively; both the bean and that defensive block are
gone. Operators can still create additional roles at runtime through the role-management API;
those start with no grants and are outside this baseline.

### Role policy

| Role | Baseline |
| --- | --- |
| `ADMIN` | All domains. The intentional blast-radius role. |
| `SYSTEM_ADMINISTRATOR` | **Security and MCP administration only** — `security:*`, plus MCP administration (`mcp:system_prompt:*`, `mcp:llm_api:*`, `mcp:tool:view`, `mcp:tool:manage`, `mcp:document:ingest`), NLTI audit visibility (`nlti:audit:read`) and the assistant entrypoints. Deliberately *not* a superuser: it holds no accounting, catalog, workorder, inventory, or shop authority, and it does **not** auto-acquire newly registered permissions. Widening it is an explicit edit to the seed. |
| `LOCATION_MANAGER`, `SERVICE_ADVISOR`, `TECHNICIAN`, `DISPATCHER`, `ACCOUNTING_ASSOCIATE`, `ACCOUNT_MANAGER`, `MANAGER`, `GENERAL_MANAGER` | Least privilege, scoped to the role's job function. |
| `ACCOUNTANT`, `AP_CLERK`, `CONTROLLER`, `CSR`, `FLEET_MANAGER`, `GL_ANALYST` | **Not granted, and not created.** The retired hardcoded switch expanded these, but no migration or initializer creates the role, and `user_roles` / `role_assignments` are foreign-keyed to `roles(id)` — so no user could ever hold one. They were unreachable branches, documentation personas rather than security roles. To make one real, create the role first, then grant it. |
| `INVENTORY_LEAD` | The parts-receiving persona (#1439): the receiving surface (`inventory:asn:*`, `inventory:receiving:*`, `inventory:goods_receipt:create/view`, `inventory:issue:parts`, `inventory:putaway:claim/execute/generate/view`, `inventory:shortage:*`, `inventory:on_hand:*`) and purchase-order entry (`order:purchase_order:create/view/availability_view`), plus adjustment requests (`inventory:adjustment:create`, `inventory:adjustment:view`) — it raises adjustments, it does not approve them — and the read-only catalog/order/pricing views and assistant entrypoints. The elevated escape hatches (`inventory:goods_receipt:override`, putaway capacity/compatibility overrides) are deliberately not granted. |
| `INVENTORY_MANAGER`, `INVENTORY_CONTROLLER` | Create, approve, and view inventory adjustments. **Permission-identical on the adjustment surface on purpose**: the "location-scoped" vs "global" distinction is a property of `role_assignments.scope_type`, not of `role_permissions`, so it cannot be expressed by granting different rows. `INVENTORY_CONTROLLER` additionally holds `inventory:adjustment:override`, the negative-stock escape hatch — only a globally scoped approver should drive on-hand below zero. `INVENTORY_MANAGER` (with `LOCATION_MANAGER`) is also a PO-approver persona (#1438): `order:purchase_order:approve/transmit/view/availability_view`. |
| `SHOP_MANAGER` | The shop surface its role description names — `shop:location:view`, `shop:bay:view`, `shop:bay:assign`, `shop:schedule:view`, `shop:schedule:edit`, `shop:technician:view` — plus `invoice:finalize:override` (#1374). No audit grant: the shop domain defines no audit permission, so "audit review" in the V3 description has nothing to map to. |
| `CUSTOMER`, `SELF_SERVICE_CUSTOMER` | **Assistant entrypoints only**, confirmed deliberate on #1373 rather than inherited. External-facing; any domain grant to them is a new product decision. |
| `SECURITY_ADMIN`, `READ_ONLY_SCHEDULER` | **Deleted.** `V3__seed_candidate_roles.sql` created them as unratified "Candidate Roles v0"; nothing in the codebase ever referenced either, and `SECURITY_ADMIN`'s described scope is already held by `SYSTEM_ADMINISTRATOR`. `V23__drop_unratified_candidate_roles.sql` removes them (#1373). V3 is left untouched — it is applied everywhere, so editing it would break its checksum. |

### Assistant baseline

Every role in the baseline seed receives four conversational entrypoints:

| Permission | Meaning |
| --- | --- |
| `mcp:chat:execute` | Synchronous chat request via the Spring AI assistant runtime |
| `mcp:chat:stream` | Streaming SSE chat request |
| `nlti:request:submit` | Submit a natural-language task-interface request |
| `nlti:request:read` | Read submitted NLTI request status |

These grant reach to the assistant, not directly to role-backed data. MCP tool selection now uses
explicit domain permission codes for the order, pricing, and catalog facades, so holding only these
entrypoints no longer qualifies a caller for those data-bearing tools. The remaining caveat is the
synthetic `AUTHENTICATED` tier: when a downstream surface still has no domain permission code for
MCP to mirror, the selection layer must fall back to `AUTHENTICATED` until that domain defines one.
These entrypoints are applied to every role the seed knows about, including the customer-facing
`CUSTOMER` and `SELF_SERVICE_CUSTOMER`, which previously held nothing at all — so external
self-service users can now reach the assistant and submit NLTI requests, but not the explicitly
permission-gated facades above.

This is a list of explicit grants, not a rule the database enforces. A role created later through
`POST /v1/roles` or the role-permission admin API starts with **no** grants at all, assistant
entrypoints included, until something grants them; add it to the seed to make it part of the
baseline. If that is not wanted, remove those two roles from the universal list in
the seed; `RolePermissionBaselineTest.everyRoleReceivesTheAssistantBaseline` pins the current
policy and will need updating alongside.

MCP administration — `mcp:system_prompt:*`, `mcp:llm_api:*`, `mcp:tool:view`, `mcp:tool:manage`
and `mcp:document:ingest` — is restricted to `ADMIN` and `SYSTEM_ADMINISTRATOR`, and a test asserts
no other role holds any of it. `nlti:audit:read` (the NLTI audit ledger) is likewise held only by
those two.

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

## Error Responses

Every non-2xx response this service maps itself (`GlobalExceptionHandler`) carries the platform
`ApiError` envelope (see `docs/ERROR_ENVELOPE.md`) and the same correlation id in both the body's
`correlationId` and the `X-Correlation-Id` response header (ADR-0017 §4). An inbound
`X-Correlation-Id` is echoed; otherwise a UUIDv7 is generated. All handlers build their response
through one helper, and `GlobalExceptionHandlerTest` fails if a new handler is added without
joining its header assertion (#1729).

Two rules constrain what those bodies may contain and where they can come from (#1715):

- **Nothing escapes the filter chain unenveloped.** `JwtAuthenticationFilter` and
  `GatewayHeaderAuthenticationFilter` run before the dispatcher, so no `@ControllerAdvice` — not
  this module's and not `pos-web-common`'s — can see what they throw; the container would answer
  with its own default page instead of the envelope (ADR-0056 §1). `JwtAuthenticationFilter`
  therefore separates the two cases a catch-all must not merge:
  - **The credential is bad → 401.** A token that throws while being read, or that
    `validateToken` refuses, fails closed (below).
  - **The server failed → enveloped 500.** Any other `RuntimeException` — a
    `RedisConnectionFailureException` from the revocation check, a `DataAccessException` from the
    token store or the user lookup, an NPE on a token with no `exp` claim — is a server fault, not
    a bad credential. `validateToken` wraps its body in `catch (JwtException |
    IllegalArgumentException)` only, so these propagate; answering 401 would tell the caller to
    replace a token that is fine. The filter writes the `ApiError` envelope itself and logs at
    ERROR against the same correlation id.

  Both filters fail closed on a bad credential, by different means, and the distinction matters:
  - `JwtAuthenticationFilter` **clears** the security context whenever a bearer token is present
    and does not authenticate — whether it threw (a `perm_bits` claim that no longer decodes, a
    stale `perm_ver`, a subject that no longer resolves to a user, an account that is disabled,
    locked, expired or whose credentials have expired) or `validateToken` simply refused it
    (expired, revoked, logged out, absent
    from the token store). Clearing rather than returning is what stops a refused credential from
    riding on gateway-header authorities: the gateway checks signature, issuer, audience and
    expiry but **not revocation**, so a revoked or logged-out token still arrives here with
    valid-looking `X-Perm-Bits`.
  - `JwtAuthenticationFilter` **enforces account state** on every bearer token on the
    `/v1/auth/**` chain (the only chain that carries the filter) (#1803). No
    `AuthenticationProvider` runs on this path, so Spring's `AccountStatusUserDetailsChecker` —
    which the credential login path gets for free from `DaoAuthenticationProvider` — ran nowhere
    here, and a disabled, locked, expired or credentials-expired account's live access token kept
    authenticating until it expired. The admin state endpoints revoke every token they know about,
    but a lockout raised by `LockoutServiceImpl`, an expiry that has since passed, or a state
    change written outside those endpoints never touched the token store. The filter now runs the
    checker before it builds the authentication; it enforces all four flags (`accountNonLocked`,
    `enabled`, `accountNonExpired`, `credentialsNonExpired` — credentials expiry matters here
    because `issueInternalToken` mints tokens with no password check, so a token minted after the
    credentials expired would otherwise keep working), and the refusal is a `LockedException` /
    `DisabledException` / `AccountExpiredException` / `CredentialsExpiredException`, caught as a
    bad credential and answered with the **same generic 401 `INVALID_CREDENTIALS`** as any other
    rejected token — not `ACCOUNT_LOCKED` / `ACCOUNT_DISABLED`, which would tell the
    unauthenticated holder of a stolen token why it stopped working. The reason is in the filter's
    WARN log against the correlation id the 401 quotes. `CustomUserDetailsService` reports a timed
    lockout whose `lockedUntil` has passed as *not* locked, mirroring
    `LockoutServiceImpl#isLockedOut`, so the bearer path and the login path share one definition
    of locked; an administrative lock (`lockedUntil` null) stays a lock.

    The refresh path (`POST /v1/auth/refresh`) is `permitAll` and carries its token in the body,
    so the filter never sees it; `JwtServiceImpl#refreshAccessToken` runs the same checker on the
    token's user and enforces the same four flags. There the refusal answers the login path's
    explicit `ACCOUNT_LOCKED` / `ACCOUNT_DISABLED` / `ACCOUNT_EXPIRED` / `CREDENTIALS_EXPIRED`
    codes, because a refresh-token holder is a credential-equivalent caller, not an anonymous
    bearer — and without it a `LockoutServiceImpl` lockout, which never revokes, could rotate a
    refresh token into a fresh access token.
  - `GatewayHeaderAuthenticationFilter` **yields no authorities** when `X-Perm-Bits` is present
    but will not decode; it never falls back to `X-Authorities` for that request. It has no
    earlier authentication to clear, being the first of the two to run.

  Either way the chain continues unauthenticated, the authorization filter rejects, and
  `JsonAuthenticationEntryPoint` renders the enveloped, correlated 401. The correlation id on that
  401 is the one `JwtAuthenticationFilter` published on the request (`CORRELATION_ID_ATTRIBUTE`),
  not a freshly minted one — the body says only `INVALID_CREDENTIALS`, so the shared id is the only
  thing joining the response a caller quotes to the log line carrying the actual reason
  (ADR-0017 §4).

  `SecurityConfig` puts `JwtAuthenticationFilter` on the `/v1/auth/**` chain only, and
  `SecurityBeansConfig` disables the servlet-container registration Spring Boot would otherwise
  add for it at `/*`. Without that, the container copy runs *after* `springSecurityFilterChain`
  on every other chain, clearing the context after authorization has already passed.
- **An error message never names the subject that failed to resolve.** The token-issuance
  endpoints (`POST /v1/auth/internal/token`, `POST /v1/auth/token-pair`) answer an unresolvable
  subject with `404 USER_NOT_FOUND` and a generic message ("Token issuance request is invalid")
  that names neither the subject nor the reason; the subject goes to the correlated WARN log via
  `UserNotFoundException.withLogDetail`, never into the body (ADR-0056 §1 — rejected values are
  never echoed), sanitised through `LogSanitizer` because the subject is unvalidated request text
  (CWE-117). Both refusal paths — no such user, and a resolved user record with no id — answer a
  byte-identical body, since a distinct code or phrase would disclose what the generic message
  exists to hide.

  The status is `404`, not `400`, since #1802: ADR-0017 §2 reserves `400` for request shape and
  says it "is never a domain-condition answer", and an unresolvable subject is a domain condition
  — the same one `GET /v1/users/{id}`, `PUT /v1/users/{id}` and `PUT /v1/users/{username}/roles`
  answer, so all of them now throw `UserNotFoundException` and the status is encoded once on that
  class ("one condition, one status"). A *blank* subject is request shape and stays
  `400 VALIDATION_ERROR`; it is decided before the user lookup so it cannot fall through to the
  404. A named role that does not resolve answers `404 ROLE_NOT_FOUND` on every entry point too —
  `createUser`, `updateUser`, `assignUserRolesByUsername` and the role-management endpoints — via
  `RoleNotFoundException` (the same ADR-0017 §2 defect as the user half, fixed in the #1808
  review); role names are a fixed catalogue, so that message does echo the name.

- **A refused token utility lookup is enveloped.** `GET /v1/auth/roles`, `/subject` and
  `/user-id` answer `401 INVALID_TOKEN` through `InvalidTokenException` when `validateToken`
  refuses the `token` query parameter; they used to return a bare 401 with no body and no
  `X-Correlation-Id`, contradicting the `ApiError` body the spec documented for them.

- **A valid token with no user id is not a server fault.** `GET /v1/auth/user-id` answers
  `422 TOKEN_USER_ID_MISSING` when the token passes full validation but carries neither a `uid`
  nor a legacy `userId` claim (#1803). It used to `NullPointerException` into the generic 500;
  the token is genuine, so 401 would misdirect the caller into replacing it, and it parsed, so it
  is not 400 — ADR-0017 §2 question 3.

The published `openapi.yaml` lists only the error statuses an operation can actually produce.
Because the advice is module-wide, springdoc would otherwise attach its 400/401/403/404/409 to
every operation (#1721); `pos-security-common`'s `ProducibleResponsesOperationCustomizer` prunes
them by rule. It is auto-configured platform-wide — this service gets it automatically from
depending on `pos-security-common`, with no customizer code of its own:

| Status | Kept when |
| --- | --- |
| any 2xx / `default` | always |
| declared via `@ApiResponse` / `@Operation(responses)` on the method or class | always |
| `400` | the operation has a parameter or a request body |
| `401` | the `@PreAuthorize` guard is anything but `permitAll()` |
| `403` | the guard uses `hasAuthority` / `hasAnyAuthority` / `hasRole` / a SpEL bean check |
| `404`, `409`, anything else | only when declared |

So an endpoint that can answer 404 or 409 (or a `permitAll()` endpoint that can answer 401/403)
must declare it on the controller method. `OpenApiErrorResponseContractTest` cross-checks the
committed spec against the controllers' declarations and fails on drift in either direction.

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
