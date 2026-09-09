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
- Tenancy (ADR-0062). **Schema, actionable now:** a new table goes into the module's `V1__baseline_<module>.sql`
  with `tenant_id` first, RLS enabled and forced, the `tenant_isolation` policy, unique constraints and
  scoped-to-scoped foreign keys leading with `tenant_id`, unless it is listed in `db/tenancy-global-tables.txt` with
  a reason (`docs/TENANCY_SCHEMA.md`); `INSERT ... ON CONFLICT (cols)` on a scoped table names `(tenant_id, cols)`;
  nothing reads a tenant from a request body, query parameter, or client header; no new `organizationId` fields.
  **Once `pos-tenancy-common` lands (plan WS1; none of these types exist yet):** a new entity extends
  `TenantScopedEntity` or carries `@TenantGlobal`; a new `@Scheduled` job is per-tenant or `@PlatformScoped`; native
  SQL and `JdbcTemplate` on scoped data carry `@TenantAudited`.
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
