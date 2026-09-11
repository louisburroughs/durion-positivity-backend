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
users -> role_assignments -> roles -> role_permissions -> permissions
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
| `ACCOUNTANT`, `AP_CLERK`, `CONTROLLER`, `CSR`, `FLEET_MANAGER`, `GL_ANALYST` | **Not granted, and not created.** The retired hardcoded switch expanded these, but no migration or initializer creates the role, and `role_assignments` is foreign-keyed to `roles(id)` — so no user could ever hold one. They were unreachable branches, documentation personas rather than security roles. To make one real, create the role first, then grant it. |
| `INVENTORY_LEAD` | The parts-receiving persona (#1439): the receiving surface (`inventory:asn:*`, `inventory:receiving:*`, `inventory:goods_receipt:create/view`, `inventory:issue:parts`, `inventory:putaway:claim/execute/generate/view`, `inventory:shortage:*`, `inventory:on_hand:*`) and purchase-order entry (`order:purchase_order:create/view/availability_view`), plus adjustment requests (`inventory:adjustment:create`, `inventory:adjustment:view`) — it raises adjustments, it does not approve them — and the read-only catalog/order/pricing views and assistant entrypoints. The elevated escape hatches (`inventory:goods_receipt:override`, putaway capacity/compatibility overrides) are deliberately not granted. |
| `INVENTORY_MANAGER`, `INVENTORY_CONTROLLER` | Create, approve, and view inventory adjustments. **Permission-identical on the adjustment surface on purpose**: the "location-scoped" vs "global" distinction is a property of the role's `location_scope` (`INVENTORY_MANAGER` is `LOCATION`, `INVENTORY_CONTROLLER` is `ALL`; see [Role location scope](#role-location-scope)), not of `role_permissions`, so it cannot be expressed by granting different rows. `INVENTORY_CONTROLLER` additionally holds `inventory:adjustment:override`, the negative-stock escape hatch — only a globally scoped approver should drive on-hand below zero. `INVENTORY_MANAGER` (with `LOCATION_MANAGER`) is also a PO-approver persona (#1438): `order:purchase_order:approve/transmit/view/availability_view`. |
| `SHOP_MANAGER` | The shop surface its role description names — `shop:location:view`, `shop:bay:view`, `shop:bay:assign`, `shop:schedule:view`, `shop:schedule:edit`, `shop:technician:view` — plus `invoice:finalize:override` (#1374). No audit grant: the shop domain defines no audit permission, so "audit review" in the V3 description has nothing to map to. |
| `CUSTOMER`, `SELF_SERVICE_CUSTOMER` | **Assistant entrypoints only**, confirmed deliberate on #1373 rather than inherited. External-facing; any domain grant to them is a new product decision. |
| `SECURITY_ADMIN`, `READ_ONLY_SCHEDULER` | **Deleted.** `V3__seed_candidate_roles.sql` created them as unratified "Candidate Roles v0"; nothing in the codebase ever referenced either, and `SECURITY_ADMIN`'s described scope is already held by `SYSTEM_ADMINISTRATOR`. `V23__drop_unratified_candidate_roles.sql` removes them (#1373). V3 is left untouched — it is applied everywhere, so editing it would break its checksum. |

### Role location scope

Since ADR-0061 (#1868) every role carries two columns that say how far its grants reach. They are
properties of the **role**, not of the assignment or the employee: two roles may hold identical
grants and differ only here.

| Column | Values | Meaning |
| --- | --- | --- |
| `location_scope` | `ALL` (default) \| `LOCATION` | `ALL`: the grants apply everywhere — today's behaviour. `LOCATION`: the grants apply only at the location nodes pos-people assigns the holder to (`ext_people_staffing_assignment`, below) and every descendant of those nodes. |
| `location_hierarchy` | `FINANCIAL` \| `OTHER` (default) | Which pos-location parent dimension a `LOCATION` role is evaluated along at check time. `FINANCIAL` is the accounting rollup; `OTHER` is the union of the seven non-financial parent types. |

Seeded values (`R__seed_role_location_scope.sql`, formerly V37, pinned by `RoleLocationScopeSeedTest`):

| Role | `location_scope` | `location_hierarchy` |
| --- | --- | --- |
| `ADMIN`, `SYSTEM_ADMINISTRATOR`, `INVENTORY_CONTROLLER`, `SELF_SERVICE_CUSTOMER` | `ALL` | `OTHER` |
| `CONTROLLER` | `ALL` | `FINANCIAL` |
| `ACCOUNT_MANAGER`, `ACCOUNTANT`, `GENERAL_MANAGER` | `LOCATION` | `FINANCIAL` |
| `INVENTORY_MANAGER`, `LOCATION_MANAGER`, `SHOP_MANAGER`, `MANAGER`, `SERVICE_ADVISOR`, `TECHNICIAN`, `DISPATCHER` | `LOCATION` | `OTHER` |

`INVENTORY_CONTROLLER` is an inventory role, not an accounting one — it is `OTHER`, and must never
be classified by a name match on "CONTROLLER". `ACCOUNTING_ASSOCIATE`, `INVENTORY_LEAD` and
`CUSTOMER` are not named by the ADR and keep the defaults.

Two things about provisioning:

- **A role created later** — through `POST /v1/roles` or `POST /v1/roles/bulk-ingest` — gets the
  defaults, `ALL` / `OTHER`. Nothing narrows by omission, and nothing widens either: `ALL`
  only continues what every role does today. The role create/response DTOs do not yet expose
  the two columns; changing them is a data change (SQL) until that API surface is extended.
- **A reseed cannot reset these columns.** `R__seed_reference_security.sql` creates only the
  bootstrap floor (`ADMIN`, `SYSTEM_ADMINISTRATOR` — both at the defaults) with
  `ON CONFLICT (name) DO NOTHING`, so an existing row is never rewritten; V37 sets the values
  once, by name. On a *fresh* database the roles the bulk loader provisions after startup arrive
  at the defaults, so `roles.csv`-provisioned `LOCATION` roles need the same UPDATE applied
  (or the loader taught the two columns) before location scope is enforced there.

`LOCATION` has no effect until an endpoint checks the scope claims below (#1870+); until then a
`LOCATION` role behaves exactly as an `ALL` one.

#### Scope claims in the access token

`JwtService.generateTokenPair` composes three **additive** claims next to `perm_bits`
(ADR-0061 §2). `perm_bits` and `CATALOG_VERSION` are unchanged.

| Claim | Value |
| --- | --- |
| `loc_fin_bits` | Base64URL bitset (same `PermissionBitsetCodec`, same bit indexes and `perm_ver` as `perm_bits`) of the permissions that are location-scoped along `FINANCIAL`. Always present; `""` when empty. |
| `loc_oth_bits` | The same along `OTHER`. |
| `loc_scope` | `{"v":1,"nodes":["<uuid>", ...]}` — the holder's assigned location nodes, verbatim and never expanded. Omitted when both bitsets are empty. `v` is a discriminator so a denser encoding can be added later without a catalog bump. |

Composition, per permission, from `RoleAuthorityService.resolveRoleGrants`:

- Granted by **any** `ALL` role → global: in neither bitset. The broader grant wins.
- Otherwise, in `loc_fin_bits` if any granting `LOCATION` role is `FINANCIAL`, and in
  `loc_oth_bits` if any is `OTHER`. A permission may be in **both**.
- **Fail closed.** If either bitset is non-empty but no assigned node resolves (the token has no
  `personId`, or `StaffingAssignmentProjectionService` returns nothing for today), the bitsets
  are emitted and `loc_scope` is **omitted**. Absence denies; it is never substituted with `ALL`.

Refresh tokens never carry any of the three. Readers: `getFinancialLocationScopedPermissionsFromToken`,
`getOtherLocationScopedPermissionsFromToken`, `getLocationScopeFromToken` (absent → `Optional.empty()`).

#### Effective-dating clamp on `exp`

When either scope bitset is non-empty, the access token's `exp` is
`min(now + 3600s, end of the day the earliest contributing staffing assignment ends)` in the
issuer clock's zone (ADR-0061 §4, #1873). Tokens with no location-scoped grant, and holders whose
assignments are open-ended, are unaffected. Refresh tokens keep their own lifetime, and because
`refreshAccessToken` re-enters `generateTokenPair`, the clamp is re-evaluated on every refresh
rather than inherited.

#### Revocation on assignment change

The second ADR-0061 §4 mechanism (#1874). `PeopleEventsListener` compares each
`people.staffing-assignment.updated` fact with the replica row it is about to overwrite, and when
the change **narrows** the person's reach it revokes that person's live tokens through
`PersonTokenRevocationService` — the same `TokenRevocationManager` + `jwt_token` path
`revokeAllTokensForUser` uses. Widening never revokes; the next token simply picks it up.

A fact narrows reach only if the assignment was contributing today (`ACTIVE`, effective on the
issuer-clock date by the same predicate as `findActiveEffectiveOn`) and:

- `status` leaves `ACTIVE` (`ENDED`); or
- `locationId` changes — the old node is no longer covered; or
- `effectiveFrom` moves after today (or is dropped; the projection never matches a null); or
- `effectiveTo` is set where it was open-ended, or moves earlier than it was.

A brand-new assignment, a reactivation, a later or removed `effectiveTo`, an earlier
`effectiveFrom` or a `primary` flip does not revoke. Neither does a stale fact (older
`aggregateVersion`) or a replayed `eventId` (`processed_events`).

Revocation is per person: every `users` row with that `person_id` is looked up, and every
`jwt_token` row of those subjects whose access token is unexpired has its access **and** refresh
JTI written to Redis (`revokeAllTokensForUser` precedent — the refresh token shares the row) and
the row deleted. The affected sessions must log in again. Rows whose access token has already
expired are left alone: there is nothing live to revoke, and their refresh re-enters
`generateTokenPair`, which re-reads the projection. Re-revoking an already-revoked JTI is a
no-op overwrite in Redis, and a second pass finds no row.

**Redis unavailable: fail-open, loudly.** If Redis is disabled, unreachable, or the write fails
after retries, the `jwt_token` rows are still deleted, `security.token-revocation.redis-unavailable`
is incremented by the number of JTIs that missed Redis, a WARN names the person and the count,
and the event completes normally. Fail-closed was rejected because it would refuse every token
platform-wide for the length of a Redis outage, while the `exp` clamp above already bounds the
stale window to the end of the assignment's effective date — fail-open costs at most that
window.

The API gateway now consults the same Redis keys on every authenticated request (#1883) and takes
the same fail-open position, so revocation reaches the boundary rather than stopping at this
module. Two consequences worth holding onto:

- **The key encoding is a cross-process contract.** `jwt:revoked:{jti}` is written with a
  `StringRedisSerializer`, not `RedisTemplate`'s default JDK object serialization. Under the
  default the keys were readable only by the one template that wrote them — the `jwt:revoked:*`
  SCAN in `clearAllRevoked` matched nothing, and the gateway would miss every revocation.
  `GatewayTokenRevocationIT` fails if the two sides stop agreeing.
- **Redis down is now a wider fail-open.** On this module's bearer path `validateToken` also
  checks the `jwt_token` row, so a DB-marked revocation still holds with Redis down. The gateway
  has no such second source — by design, it does not read this module's schema — so during a Redis
  outage a revoked token passes the gateway until `exp`. That is the accepted cost of fail-open,
  bounded by the `exp` clamp above and visible on `auth.token.revocation.degraded`.

#### Role-assignment expiry clamp and revocation (ADR-0061 §4 amendment, 2026-09-09, #1914 phase 3)

The two mechanisms above (location-reach clamp / staffing-assignment revocation) left a gap: the
role assignments a token's `perm_bits` is actually built from could still stay valid for the rest
of the access token's natural lifetime after being ended. Phase 3 closes it with the same two
mechanisms applied to `role_assignments`:

- **Clamp.** `exp` is `min(now + 3600s, the location-reach bound above, the earliest
  `effectiveEndDate` among the assignments that contributed to the token)`, floored at `now`. Login
  (`AuthenticationServiceImpl`) and refresh (`JwtServiceImpl.refreshAccessToken`) both resolve this
  bound via `UserService#getGrantsExpireAt` and pass it into `generateTokenPair`; the internal
  token-pair endpoints (`POST /v1/auth/internal/token`, `POST /v1/auth/token-pair` — client-supplied
  roles, no resolved user) get no assignment clamp, matching how they already sit outside the
  location-reach clamp.
- **Revocation.** Ending a role assignment — `UserRoleGrantServiceImpl.revoke` / `reconcile`
  (covers `DELETE /v1/users/{userId}/roles/{roleId}`, and a `PUT /v1/users/{username}/roles`
  reconcile that drops a role) or `RoleManagementServiceImpl.revokeRoleAssignment`
  (`DELETE /v1/roles/assignments/{assignmentId}`, which may set a past or future end date) — ends
  the holder's live tokens through the same `TokenRevocationManager` + `jwt_token` path
  `revokeAllTokensForUser` uses, every time, regardless of whether the new end date is already
  past or still ahead: a future-dated revocation still changes the record the holder's current
  tokens were minted against, and the token reissued after revocation is then clamped to the
  scheduled end by the mechanism above, which is the correct outcome either way. Granting a role
  never revokes; the next token simply picks it up.

Both writers publish `RoleAssignmentRevokedEvent` rather than calling the token layer directly —
`JwtServiceImpl` depends on `UserService`, and `UserServiceImpl` / `RoleManagementServiceImpl` both
depend on `UserRoleGrantService`, so a direct call back into `JwtService` would close a Spring bean
cycle. `RoleAssignmentTokenRevocationListener` reacts to the event `AFTER_COMMIT` (so a rolled-back
revocation never touches a token) in its own `REQUIRES_NEW` transaction (so the write actually
commits, rather than silently riding along on the just-completed transaction's about-to-be-discarded
resources) and calls `JwtService#revokeAllTokensForUser` by username — the same facility
`AdminAccountStateServiceImpl` already uses for account lockout/disable, now with a second
security-load-bearing trigger. The Redis-unavailable behaviour is unchanged (fail-open, `jwt_token`
row still deleted).

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

Two tables are easy to confuse:

| Table | Meaning | Consumed by |
| --- | --- | --- |
| `role_permissions` | **role → permission** grants | Token issuance (`RoleAuthorityService`), `AuthorizationService`, `RoleManagementService` |
| `role_assignments` | **user → role**, effective-dated (`effective_start_date`, `effective_end_date`, `revoked_at`) | Every decision point, via `EffectiveGrantResolver` — does **not** narrow a JWT |

`role_assignments` is the only store of a user's roles (ADR-0061 amendment, 2026-09-09, #1914
phase 2): the undated `user_roles` join table it used to sit alongside — unioned by
`EffectiveGrantResolver` in phase 1 — was migrated into open-ended assignments and dropped
(by the retired V40 migration; the flattened baseline never creates `user_roles`). Every provisioning path (user creation,
`assignUserRole`, self-registration, the People access page) now writes an assignment; every
decision point — token issuance (`CustomUserDetailsService`, `UserService`),
`AuthorizationService.authorizePerson`, and `RoleManagementService.userHasPermission` /
`getUserPermissions` — reads it through one `EffectiveGrantResolver`, so the same permission set
answers every check.

`role_assignments` carries no location scope: `scope_type` and `role_assignment_scope_locations`
were dropped by `V38__drop_role_assignment_scope.sql` (ADR-0061 §1, #1875), and with them
`GET /v1/roles/check-permission`, the only reader. Location reach is the role's `location_scope`
plus the pos-people staffing assignment, carried as the scope claims described under
[Role location scope](#role-location-scope); `perm_bits` itself still takes the union of every
role a user holds, and location-sensitive decisions are enforced by the owning service from
those claims.

## Key Classes

- `JwtService` — issues and validates JWTs; encodes `perm_bits` and the `loc_fin_bits` / `loc_oth_bits` / `loc_scope` scope claims via `PermissionBitsetCodec`, and clamps `exp` to the earliest contributing staffing assignment
- `AuthenticationService` — login flow; delegates to Spring Security `AuthenticationManager`
- `LockoutService` — configurable failed-login lockout with automatic and manual unlock
- `PermissionService` — permission catalog management (bit index assignment)
- `RoleManagementService` — role CRUD and role-to-permission assignment
- `RoleAuthorityService` — resolves a role's authorities from persisted `role_permissions` grants, and (`resolveRoleGrants`) the same grants per role with each role's location reach
- `SelfRegistrationService` / `SelfRegistrationReviewService` — user self-registration and admin review

## API Endpoints

- `POST /v1/auth/login` — authenticate and receive JWT (tenant from `X-Tenant-Slug` or the form's `tenantSlug`, ADR-0062 §3)
- `POST /v1/auth/activate` — exchange a one-time activation token for the account's first password (unauthenticated; ADR-0062 §7, WS2b-3)
- `POST /v1/platform/tenants/{tenantId}/administrators/{userId}/activation-token` — platform tenant only, `platform:tenant:provision`: mint a first-administrator activation token, returned once
- `GET /v1/tenants/me` — the caller's tenant (`tid`) as the `ext_tenant` replica knows it; 404 while the replica is behind
- `GET /v1/auth/validate` — validate a JWT
- `GET /v1/auth/subject` — extract subject from JWT
- `GET /v1/permissions/catalog-version` — active catalog version (public)
- `POST /v1/permissions/decode` — decode a `perm_bits` value (auth: `security:permission:view`)
- `GET /v1/roles` — list roles
- `POST /v1/roles` — create a role
- `POST /v1/roles/{roleId}/permissions/{permissionKey}` — assign permission to role
- `DELETE /v1/roles/{roleId}/permissions/{permissionKey}` — remove permission from role
- `POST /v1/roles/assignments` / `DELETE /v1/roles/assignments/{assignmentId}` — create / revoke an effective-dated role assignment
- `GET /v1/roles/assignments/user/{userId}` — list a user's role assignments (there is no `check-permission` probe; location scope is decided from the token's scope claims)
- `GET /v1/users/{id}` — retrieve a user
- `POST /v1/users/{id}/unlock` — admin: unlock account
- `POST /v1/users/{id}/enable` / `disable` — admin: enable/disable account
- `GET /v1/users/authorization/person-decision` — off-session check whether the user linked to a personId has a permission

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
    riding on gateway-header authorities. Since #1883 the gateway checks revocation too, so this
    is no longer the only place a revoked token is caught — but it stays the backstop, because the
    gateway's check fails open when Redis is unavailable and reads only Redis, while
    `validateToken` also checks the `jwt_token` row.
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
| `SECURITY_SEED_ADMIN_PASSWORD_HASH` | required     | BCrypt hash for the seed admins (`admin.alpha`, `admin.platform`) |
| `security.lockout.threshold`        | configurable | Failed login threshold before lockout |
| `pos.security-service.kafka.people-events-topic` | `people.events.v1` | Staffing-assignment facts feeding the assigned-node read model (ADR-0061 §1) |
| `pos.security-service.kafka.people-manifest-topic` | `people.manifest.v1` | Reconciliation manifests for that read model; drift requests a replay on `people-commands-topic` |
| `pos.security-service.location-scope.assigned-node-cap` | `8` | Assigned-node count above which `security.location-scope.assigned-nodes.cap-exceeded` fires (WARN + metric, never truncated) |
| `pos.security-service.kafka.tenant-events-topic` | `tenant.events.v1` | Tenant registry facts (pos-tenant, ADR-0062 §7) feeding the `ext_tenant` replica |
| `pos.tenancy.default-tenant-id` | alpha default tenant | Transitional binding for unbound requests and pre-WS2b tokens without `tid`; empty means strict |
| `pos.tenancy.unenforced-paths` | `/v1/auth/` | Paths that run unbound even in strict mode (login resolves the tenant itself) |

## Dependencies

- `pos-security-common` — shared security constants and filter
- `pos-events` — `@EmitEvent` annotation and event registration
- `pos-tenancy-common` — tenant binding, `TenantScopedEntity`, `@TenantGlobal`, Kafka tenant header (ADR-0062)

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`. Seed admin password hash is injected as a Flyway placeholder; never commit real hashes in SQL files.

## Tenancy (ADR-0062, WS2b)

Every row but the global tables (`ext_tenant`, `permissions`, `processed_events`, `event_outbox`,
`user_activation_tokens`; see `db/tenancy-global-tables.txt`) belongs to a tenant and is read under row-level security as `pos_app`, with
Flyway on the owner credential (`SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD`). Usernames are unique per tenant.

- **Login resolves the tenant first** (`LoginTenantResolver`): the gateway's `X-Tenant-Slug` (derived from the
  request host, never trusted from the client), else the form's `tenantSlug`, looked up in `ext_tenant`; an
  unknown or inactive slug is the same 401 as a bad password. No slug at all binds the transitional default.
  The user lookup, lockout bookkeeping and token issue then run under that tenant.
- **Both tokens carry `tid`.** Token validation and the refresh exchange bind the token's own `tid` around
  their lookups, so a refresh cannot change tenant. The gateway injects `X-Tenant-Id` from `tid`.
- **`ext_tenant`** is the replica of pos-tenant's `tenant.events.v1` projection (`TenantEventsListener`,
  idempotent through `processed_events`, monotonic on `aggregateVersion`). `ExtTenantRegistry` serves it as
  the module's `TenantRegistry`, and `GET /v1/tenants/me` reads it for the caller.
- **Provisioning (`tenant.created`).** `TenantEventsListener` reads the platform role template under the platform
  binding (`RoleTemplateService`) and applies it under the new tenant's binding (`TenantProvisioningService`):
  one role per template entry (name, description, MCP persona, ADR-0061 location scope, permission grants,
  `template_key`), the initial administrator named by `initialAdminEmail` on `ADMIN` with a generated, discarded
  password, the first `role_assignments` row, then `tenant.provisioned` through the outbox, which moves the
  tenant to `ACTIVE` in pos-tenant. Idempotent on tenant: existing roles and users are left alone, so a
  redelivery converges. No credential rides on any event: the administrator is created *awaiting activation*
  (`UserService.createUserAwaitingActivation`: `credentials_non_expired = false` and `awaiting_activation = true`
  behind a discarded random password), and a login attempt is the same 401 `INVALID_CREDENTIALS` as any wrong password.
- **First-administrator activation (WS2b-3, decided 2026-09-10: operator-delivered activation token).** A
  platform operator holding `platform:tenant:provision`, bound to the platform tenant, calls
  `POST /v1/platform/tenants/{tenantId}/administrators/{userId}/activation-token` (`PlatformAdministratorController`
  → `AdministratorActivationService.mint`). It returns `{token, expiresAt}` exactly once: the token is 32 random
  bytes, URL-safe base64, valid 72 hours; only its SHA-256 is stored (`user_activation_tokens`, a global table
  carrying `tenant_id` as data), and any earlier open token for the user is closed. A caller bound to another
  tenant is refused with 403 `PLATFORM_TENANT_REQUIRED` whatever it holds; an unknown user in that tenant is 404
  `USER_NOT_FOUND`; a user that is not awaiting activation is 409 `USER_NOT_AWAITING_ACTIVATION`, so a live
  account's password is never overwritten. "Awaiting activation" is the explicit `users.awaiting_activation`
  marker only `createUserAwaitingActivation` sets (credential state alone cannot tell that account from one whose
  credentials an administrator expired before its first login); activation and any ordinary password set
  (`PUT /v1/users/{id}`) clear it, and activation locks the user row and re-checks the marker before consuming
  the token. Mints for one user serialize on
  a pessimistic lock of the user row; the audit event is emitted after commit. The operator hands the token over out of band; the administrator exchanges it, unauthenticated,
  at `POST /v1/auth/activate` `{token, newPassword}` (on `pos.tenancy.unenforced-paths`), which finds the row by
  hash, binds the row's tenant, sets the password, clears the credential expiry and consumes the token in one
  transaction. Unknown, expired and used tokens are one answer, 401 `ACTIVATION_TOKEN_INVALID`. The same token
  shape later drives e-mail reset. Operator steps: `docs/OPERATIONS_RUNBOOK.md` → "Tenant provisioning".
- **Platform support access (WS2b-4, decided 2026-09-10: impersonation token, never a cross-tenant role).** A
  platform operator holding `platform:tenant:impersonate`, bound to the platform tenant, calls
  `POST /v1/platform/tenants/{tenantId}/impersonation-token` (`PlatformImpersonationController` →
  `PlatformImpersonationService.issue`). It returns `{token, expiresAt, tenantId, tenantSlug}` exactly once. The
  token is an ordinary signed access token to the gateway and every module, minted by
  `JwtServiceImpl.generateImpersonationToken` under the *target* tenant's binding, with: `sub` / `username` =
  `support:<operator>@<tenantSlug>` (a synthetic principal that matches no user; bounded to `jwt_token.subject`'s
  255 characters — a longer operator name is cut and given a stable 8-hex SHA-256 fingerprint, `…~1a2b3c4d@slug`), `uid` = the operator's user
  id (so `X-User-Id` audit lineage names the human), `tid` = the target tenant, `roles` = `["ROLE_SUPPORT"]`,
  `perm_bits` = the target tenant's own `SUPPORT` role's grants (resolved under that binding, so a tenant that
  narrowed its `SUPPORT` role narrowed support) **intersected with the read-only ceiling**
  (`SupportReadOnlyCeiling`: a template role's grants stay editable through the role-permission API, so a
  tenant administrator who grants `security:user:delete` to `SUPPORT` widens the role but never the token —
  the mint keeps only `*:*:view` / `*:*:read` and `location:read`, minus the explicit exclusions, and the
  dropped codes are logged at WARN and audited as `droppedGrants`), empty location-scope bitsets and no `loc_scope`,
  `act` = `{"sub": <operator user id>, "username": <operator>}`, `token_use` = `"impersonation"`, and
  `exp` = `iat` + 15 minutes. **No refresh token**: the `jwt_token` row has a null refresh half (the two
  columns are nullable for exactly this row), `POST /v1/auth/refresh` answers 401 `INVALID_REFRESH_TOKEN` to a
  token carrying `token_use=impersonation` before any lookup — live or already expired (the claims of an
  expired, correctly signed token are still read for `token_use`) — and a longer session is a new mint. The gateway
  needs no change — it reads `tid` and `perm_bits` and ignores `act` / `token_use` — and downstream modules see
  `X-Tenant-Id` = the target tenant with the `SUPPORT` authorities, so `GET /v1/tenants/me` and every
  tenant-scoped read answer inside that tenant. Refusals: 403 `PLATFORM_TENANT_REQUIRED` under any other
  binding whatever the caller holds; 404 `TENANT_NOT_FOUND` when `ext_tenant` does not know the tenant; 409
  `TENANT_NOT_IMPERSONABLE` when it is not `ACTIVE` (the status is in the message), has no `SUPPORT` role yet
  (provisioned before WS2b-4 and not yet reconciled by WS8), or is the platform tenant itself; 404
  `USER_NOT_FOUND` when the operator has no user row in the platform tenant. Audit: one
  `PlatformImpersonationTokenIssued` event in the target tenant (the tenant's own log shows who read its data
  and until when) and one in the platform tenant (the operator-side ledger), each with the operator, subject,
  `jti`, expiry and the request's `X-Correlation-Id`, plus an INFO log line; the token itself is never logged.
  The token holder cannot call this module's bearer-authenticated `/v1/auth/**` utilities (`revoke`, `roles`,
  `subject`, `user-id`): `JwtAuthenticationFilter` resolves the subject to a user, and the synthetic principal
  is none. **Revocation.** The row is stored in the target tenant under the synthetic subject, so neither the
  binding nor the subject `JwtService.revokeAllTokensForUser` queries on matches it; `jwt_token` therefore
  carries `impersonated_by_user_id` (the operator, a partial index), and
  `ImpersonationTokenRevocationService.revokeForOperator` sweeps every tenant `ext_tenant` knows — plus the
  platform tenant — revoking the operator's rows in each (`JwtService.revokeImpersonationTokensMintedBy`, a
  `REQUIRES_NEW` transaction inside the rebind, because the session fixes its tenant when it opens; one
  unreachable tenant is logged and the sweep continues). It runs alongside the username-keyed revocation in
  `AdminAccountStateServiceImpl.disable` / `expireAccount` / `expireCredentials` and in
  `RoleAssignmentTokenRevocationListener`, so disabling or expiring the operator, or revoking the role carrying
  `platform:tenant:impersonate`, ends every support token they had minted instead of leaving it live for up to
  15 minutes. Operator steps: `docs/OPERATIONS_RUNBOOK.md` → "Impersonating a tenant for support".
- **`SUPPORT` role.** The fixed, read-only role an impersonation token carries. Seeded as an alpha floor role
  (`R__seed_reference_security.sql`, `mcp_persona_eligible = false`, location scope `ALL` / `OTHER`), granted in
  `R__seed_role_permissions.sql`, listed in the alpha bulk-load baseline (`roles.csv`, `role-permissions.csv`)
  and marked `template_key` so `R__seed_tenant_template.sql` copies it into the platform role template and
  provisioning gives it to every new tenant (existing tenants get it through the WS8 template reconcile). **No
  user is ever assigned it, and no user can be**: `ReservedRoles` names it unassignable and every grant path
  refuses it with 409 `ROLE_NOT_USER_ASSIGNABLE` — `UserRoleGrantServiceImpl.grant` / `reconcile` (which covers
  `assignUserRole`, `PUT /v1/users/{username}/roles`, `updateUser`, `createUser`, the user bulk-ingest, the
  alpha CSV loader and self-registration) and `RoleManagementServiceImpl.createRoleAssignment`, which writes its
  own dated row. A `reconcile` validates the whole desired set before it revokes anything, so a refused request
  writes nothing. The role itself stays present and editable — an impersonation token has to have something to
  resolve. Its grants are the `*:*:view` / `*:*:read` permissions of the six floor roles plus
  `location:read` — nothing else: no write, no `platform:*`, not the assistant baseline (`mcp:chat:*`,
  `nlti:request:*`), not the MCP administration surface, not `nlti:audit:read`, not `people:employee_pii:view`
  and not `people:self:view`. `RolePermissionBaselineTest.supportIsReadOnly` pins the seeded list to exactly the
  `SupportReadOnlyCeiling` rule the mint enforces, so the seed and the runtime ceiling cannot drift apart, and a
  new read permission granted to a floor role must be added here too, deliberately. The rule reads a permission
  code the way the catalog spells one, mixed case included (`SupportReadOnlyCeiling.SEGMENT` mirrors
  `PermissionRegistryServiceImpl.PERMISSION_PATTERN`), so camelCase reads such as `people:timeAdjustment:view`
  and `people-contact:userLink:view` are admitted like any other. The grants
  (138):

  | Domain | Permissions |
  | --- | --- |
  | `accounting` | `accounting:analytics:view`, `accounting:ap:view`, `accounting:coa:view`, `accounting:credit-memo:read`, `accounting:customer-credit:view`, `accounting:default-mapping:view`, `accounting:events:view`, `accounting:export:view`, `accounting:je:view`, `accounting:mapping-key:view`, `accounting:period:view`, `accounting:posting-category:view`, `accounting:posting_rules:view`, `accounting:reconciliation:view` |
  | `bulkImport` | `bulkImport:status:read` |
  | `catalog` | `catalog:catalog_grouping:view`, `catalog:item_cost:read`, `catalog:labor_standard:view`, `catalog:location_price_override:read`, `catalog:msrp:read`, `catalog:non_inventory:view`, `catalog:price_book:read`, `catalog:product:view`, `catalog:product_uom:view`, `catalog:service_package:view`, `catalog:service_type:view`, `catalog:substitution_group:view`, `catalog:supplier_cost:read`, `catalog:tread_design:view`, `catalog:uom_conversion:view` |
  | `crm` | `crm:consent:view`, `crm:contact:view`, `crm:contact_preference:view`, `crm:followup:view`, `crm:inquiry:view`, `crm:interaction:view`, `crm:party:view`, `crm:person:read`, `crm:processing_log:view`, `crm:promotion_redemption:view`, `crm:relationship:read`, `crm:segment:view`, `crm:suppression:view`, `crm:suspense:view`, `crm:tag:view`, `crm:vehicle:view` |
  | `inventory` | `inventory:adjustment:view`, `inventory:asn:view`, `inventory:availability:read`, `inventory:cycle_count:view`, `inventory:goods_receipt:view`, `inventory:ledger:view`, `inventory:location:view`, `inventory:on_hand:view`, `inventory:pick_list:view`, `inventory:putaway:view`, `inventory:putaway_rule:view`, `inventory:receiving:view`, `inventory:return:view`, `inventory:scrap:view`, `inventory:shortage:view`, `inventory:supplier_stock_hint:view`, `inventory:transfer:view`, `inventory:valuation:view` |
  | `invoice` | `invoice:analytics:view`, `invoice:invoice:view` |
  | `location` | `location:bay:read`, `location:mobile-unit:read`, `location:read`, `location:service-area:read`, `location:travel-buffer-policy:read` |
  | `marketing` | `marketing:campaign:view`, `marketing:stats:view`, `marketing:template:view` |
  | `order` | `order:order:view`, `order:price_override:view`, `order:purchase_order:view`, `order:return:view`, `order:session:view` |
  | `people` | `people:availability:view`, `people:compliance:view`, `people:employee:view`, `people:skill:view`, `people:timeAdjustment:view`, `people:timeEntry:view`, `people:timeException:view`, `people:timekeeping:view` |
  | `people-contact` | `people-contact:organization:view`, `people-contact:person:view`, `people-contact:role:view`, `people-contact:userLink:view` |
  | `pricing` | `pricing:labor_rate:view`, `pricing:normalization:view`, `pricing:promotion:view`, `pricing:restrictions:view`, `pricing:rule:view` |
  | `security` | `security:audit:view`, `security:permission:view`, `security:role:view`, `security:user:view`, `security:user_account_state:view` |
  | `shop` | `shop:dashboard:view`, `shop:schedule:view`, `shop:technician:view` |
  | `supplier` | `supplier:audit:read`, `supplier:pricecatalog:read`, `supplier:profile:read`, `supplier:stockavailability:read`, `supplier:stocksnapshot:read`, `supplier:transmission:read` |
  | `tax` | `tax:exemption:view`, `tax:mode:view`, `tax:rates:view` |
  | `vehicle-fitment` | `vehicle-fitment:catalog:view`, `vehicle-fitment:hint:view` |
  | `vehicle-inventory` | `vehicle-inventory:registry:view`, `vehicle-inventory:search:view` |
  | `warranty` | `warranty:claim:view`, `warranty:part-return:view`, `warranty:policy:view`, `warranty:provider:view`, `warranty:registration:view`, `warranty:reimbursement:view` |
  | `workorder` | `workorder:analytics:view`, `workorder:approval_config:view`, `workorder:change_request:view`, `workorder:dashboard:view`, `workorder:estimate:view`, `workorder:estimate_item:view`, `workorder:estimate_snapshot:view`, `workorder:financials:view`, `workorder:invoice:view`, `workorder:labor:view`, `workorder:labor_intelligence:view`, `workorder:note:view`, `workorder:parts:view`, `workorder:wip:view`, `workorder:workorder:view` |
- **Open-in-view is off** (`spring.jpa.open-in-view: false`): the Hibernate session fixes its `@TenantId` when it
  opens, so a request-scoped session would pin every query to the tenant bound when the request arrived. Login,
  refresh, activation and the platform administrator and support endpoints all rebind mid-request
  (`TenantContext.callAs` / `runAs`) around a `@Transactional` bean, and each transaction opens its own session
  under the binding in force.
- **Role template and platform tenant** (`R__seed_tenant_template.sql`, tier 1). The six Flyway floor roles
  (`ADMIN`, `SYSTEM_ADMINISTRATOR`, `DISPATCHER`, `SHOP_MANAGER`, `SELF_SERVICE_CUSTOMER`, `CONTROLLER`) and
  `SUPPORT` carry `template_key` in alpha and are copied, grants and scope included, into the platform tenant as
  the template.
  Roles the alpha bulk loader adds later (`roles.csv`) are not in the template yet (WS8 runs the loader against
  the platform tenant). A template role rejects delete for the life of its tenant (409 `ROLE_TEMPLATE_IMMUTABLE`);
  its grants may change and custom roles (`template_key` null) are unrestricted.
- **`PLATFORM_ADMIN` / `admin.platform`** exist in the platform tenant only and hold the `platform:tenant:*`
  (including `platform:tenant:provision` and `platform:tenant:impersonate`) and `platform:account:*` families;
  alpha's `ADMIN` no longer does. `generate-permissions.sh --sync` refuses to grant
  a `platform:*` permission through the alpha sources: add the tuple to the platform seed by hand.

`ext_people_staffing_assignment` is a read model of pos-people's `employee_location_assignment` (ADR-0061 §1): one row per assignment keyed by `assignment_id`, storing the assigned location node *verbatim* (shop or District/Region/HQ — never expanded), `is_primary`, `status` (`ACTIVE`/`ENDED`, ended rows are kept), and effective dates. Written only by `PeopleEventsListener`; read through `StaffingAssignmentProjectionService` ("nodes effective on date D", "earliest `effective_to`").

## Development

```bash
./mvnw -pl pos-security-service -am spring-boot:run
```
