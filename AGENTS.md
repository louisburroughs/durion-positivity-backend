# AGENTS.md — durion-positivity-backend

## Quick Start

```bash
# Build a single service plus deps
./mvnw -pl pos-order -am clean package

# Run module tests
./mvnw -pl pos-order -am test

# Run architecture validation
./mvnw -pl pos-archunit -am -Dtest=ArchitectureTests test
```

## Critical Rules

- All code lives under `com.positivity.<domain>.internal`, except grant-surface types: `{domain}.service` /
  `service.model` hold only types named by a cross-module grant recorded in an ADR (ADR-0026 D1–D5; today the
  sole grant is `SupplierStockService`, per ADR-0044). Ungranted service interfaces live in `internal.service`
  beside their implementations.
- Use `@NonNull` on non-null service and DAO parameters/returns.
- Keep controllers thin; business logic belongs in service layer.
- Prefer the API gateway and event-driven boundaries over direct cross-service coupling.
- Use `@EmitEvent` on state-changing endpoints and register event types at startup.
- Every endpoint that takes a caller-supplied `locationId` needs a recorded location-scope decision
  (`gate` | `narrow` | `unscoped`, with a reason) in `<module>/location-scope.yaml` beside its
  `@PreAuthorize` permission (ADR-0061); `scripts/audit-rbac.py --check` fails CI on a missing, stale
  or invalid entry. See `docs/OPERATIONS_RUNBOOK.md` → "Location-scope decisions".
- The permission alternates a `LocationScope` / `LocationScopeService` / `LocationScopeGuard` call
  passes must be ones the endpoint reaching it requires — a scope check against a permission the
  caller need not hold does nothing (#1890, gated as `location_scope_alternates`).
- Tenancy (ADR-0062): a new table is tenant-scoped (`tenant_id`, RLS enabled and forced, `tenant_isolation`
  policy) unless it is listed in the module's `tenancy-global-tables.txt` with a reason; a new entity extends
  `TenantScopedEntity` or carries `@TenantGlobal`; a new `@Scheduled` job is per-tenant or `@PlatformScoped`;
  native SQL and `JdbcTemplate` on scoped data carry `@TenantAudited`; nothing reads a tenant from a request body,
  query parameter, or client header; no new `organizationId` fields. Until `pos-tenancy-common` lands (plan WS1)
  write new schema so the retrofit is mechanical: include `tenant_id UUID NOT NULL` and `(tenant_id, ...)` unique
  constraints from the start.
- Keep ArchUnit rules green.

## Where to Look

- Shared workspace guidance: `../durion/AGENTS.md`
- Knowledge catalog: `../durion/knowledge-catalog/backend/`
- Local domain docs: `../durion/domains/`
- Module-specific docs: each `pos-*` directory has a local `README.md` and `index.md`

## Related References

- `docs/ARCHITECTURE_GUIDE.md`
- `docs/DEVELOPMENT_GUIDE.md`
- `docs/OPERATIONS_RUNBOOK.md`
- `../durion/knowledge-catalog/`
