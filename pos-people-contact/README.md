# pos-people-contact

Identity, contact, and user-link authority service for the Durion Positivity platform
(ADR-0044 §6 Phase 3, issue #874). Split out of pos-people, which retains the HR domain
(employees, timekeeping, availability, staffing).

## Responsibilities

- Own `Person`, `PersonContactPoint`, and `UserPersonLink` records (ADR-0015)
- Person directory search (name / email) and weighted person resolution
- Typed contact-point management (email, phone) — source of truth for contacts
- User ↔ person link management (keyed by username; ADR-0043)
- Person → role assignment proxying to pos-security-service (utility sync call,
  allowed by the ADR-0044 whitelist)

## Eventing (ADR-0044)

- Publishes identity facts to `people-contact.events.v1` via a transactional outbox:
  `people-contact.person.updated`, `people-contact.person.deleted`,
  `people-contact.user-person-link.updated`, `people-contact.user-person-link.removed`
- Publishes reconciliation manifests to `people-contact.manifest.v1`
- Consumes `people-contact.commands.v1` (`people-contact.outbox.replay-requested`
  for replica bootstrap / drift repair)
- Feature flag: `pos.people-contact.kafka.enabled` (`POS_PEOPLE_CONTACT_KAFKA_ENABLED`)

## Permissions

`people-contact:person:{view,create,edit,delete}`, `people-contact:role:{view,assign,revoke}`,
`people-contact:userLink:{view,write}` — registered with pos-security-service at startup.

## Notes

- Gateway route: `/people-contact/**` → `lb://PEOPLE-CONTACT`
- Employment data (employee status, assignments) lives in pos-people; this service's
  directory search has no employment filters.
- Consumers needing person reference data maintain `ext_people_contact_person` replicas
  fed from `people-contact.events.v1` (Phases 3.2–3.4: #875, #876, #877).

## Role assignments (ADR-0061)

`PersonAccessController` proxies `/v1/people/{personUuid}/access/{roles,assignments}` to
pos-security-service through `internal/client/SecurityServiceClient`, resolving the person to a
security user via their active user-person link.

- **An assignment carries no location.** Per ADR-0061 it is an effective-dated user → role link
  and nothing more. A person's location reach is the assigned role's own `location_scope`
  combined with that person's pos-people staffing assignment, resolved at token issuance — it is
  not chosen when the role is granted. `assignRoleToPerson` therefore takes no `locationId`, and
  the assignment returned by `listRoleAssignments`/`assignRoleToPerson` reports none. Issue #1875
  deleted the corresponding `scopeType`/`scopeLocationIds` fields from pos-security-service.
- **A role's name is its code.** pos-security-service has no separate role-code field;
  `GET /v1/roles/by-name/{name}` resolves an assignment by exactly the value
  `listAssignableRoles` returns as both `name` and `code`.
- **The role catalog is unfiltered.** `GET /v1/roles` takes no parameters, so
  `listAssignableRoles` issues one call and lists each role once.
- **Effective dates are date-times.** Both the inbound `startDate`/`endDate` and the downstream
  `effectiveStartDate`/`effectiveEndDate` are `LocalDateTime`; a date-only value is rejected
  downstream rather than coerced to midnight. The window is start-inclusive and end-exclusive.
- **There is nothing here for location scope to enforce (#1885).** `createAssignment` accepts no
  location, so there is no location to check the acting user's reach against; the module keeps its
  `location-scope.yaml` entry as an `unscoped` decision rather than a deferral, and gains no
  location replica. Reach on the *resulting* access is decided by ADR-0061 §2 at token issuance,
  and enforced by whichever service the assignee then calls.

## Multitenancy (ADR-0062, WS3 wave 11)

This module runs on the ADR-0062 runtime: it depends on `pos-tenancy-common`, every scoped entity extends `TenantScopedEntity`, and the two global tables (`event_outbox`, `processed_events`, listed in
`src/main/resources/db/tenancy-global-tables.txt`) carry `@TenantGlobal`. The request tenant is
bound by `TenantContextFilter` from `X-Tenant-Id` (the gateway injects it from the token's `tid`), the Kafka tenant by `TenantRecordInterceptor` from the `tenantId` record header on the
command consumer, and every
connection checkout binds `app.current_tenant` for row-level security. `pos.tenancy.default-tenant-id` still binds
the alpha default tenant on every unbound path.

The application pool connects as the non-owner `pos_app` role (Compose: `SPRING_DATASOURCE_USERNAME`
/ `POS_APP_PASSWORD`); Flyway alone uses the owner credential (`SPRING_FLYWAY_USER` /
`SPRING_FLYWAY_PASSWORD`, `FlywayConfig`).

The outbox row carries the producing tenant as data (`tenant_id`, stamped from the bound tenant by
`OutboxEventWriter`, added by `V2__event_outbox_tenant_id.sql`); `OutboxPublisher.publishPending` and
`ManifestPublisher.publishDueManifest` are platform-scoped (they drain and summarise the global `event_outbox`;
each manifest is a platform-tenant record until per-tenant manifests land in plan WS4-3). There are no native queries. The `pg` test profile is strict (the
transitional `connection-init-sql` binding is gone); `FlywayMigrationIT` keeps its own `@ServiceConnection`
container as the owner.

Proof: `TenantIsolationIT` (tenant A's `person` row is invisible to tenant B and to an unbound
connection, through the repository and through raw SQL) and `TenancySchemaConformanceIT` (every
non-whitelisted table has `tenant_id`, RLS enabled and forced, and the `tenant_isolation` policy; the pool is
`pos_app` with no bypass), both on Testcontainers Postgres (`./mvnw -pl pos-people-contact -am verify`).

