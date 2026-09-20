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
  a reason (`../durion/docs/architecture/deployment/TENANCY_SCHEMA.md`); `INSERT ... ON CONFLICT (cols)` on a scoped table names `(tenant_id, cols)`;
  nothing reads a tenant from a request body, query parameter, or client header. Two exceptions are approved.
  `pos-event-receiver`'s summary endpoints (plan WS6) take an optional `tenantId` query parameter as a *scope
  selector* for a caller already bound to the platform tenant, never as the caller's identity, and refuse it from
  any other binding with 403. `pos-bulk-loader`'s `POST /v1/bulk-jobs` (plan WS8) carries a `tenantId` body field
  naming the tenant a bulk load writes into: a load is an operator action against a tenant, not a request inside
  one, and the target has to be said out loud because the job's rows, its audit trail and every sibling call it
  makes are bound to it. It is a target selector, never an authorization input — `BulkLoadTenantBinding` refuses
  any tenant the caller is not already bound to, so a bound caller may name only its own tenant, the platform
  operator included, and an unnamed target falls back to the transitional default tenant and nothing else.
  No new `organizationId` fields.
  **In a module that depends on `pos-tenancy-common`** (`pos-location`, `pos-tenant`, `pos-security-service`, `pos-inventory`, `pos-accounting`, `pos-workorder`, `pos-catalog`, `pos-shop-manager`, `pos-order`, `pos-customer`, `pos-supplier`, `pos-warranty`, `pos-people`, `pos-invoice`, `pos-marketing`, `pos-vehicle-inventory`, `pos-price`, `pos-vehicle-fitment`, `pos-people-contact`, `pos-tax`, `pos-image`, `pos-vehicle-reference-nhtsa`, `pos-vehicle-reference-carapi`, `pos-mcp-server`, `pos-event-receiver` and `pos-bulk-loader` so far; each WS3 wave adds
  its module to `TenancyArchitectureTest.ADOPTED_MODULES`): a new entity extends `TenantScopedEntity` or carries `@TenantGlobal`;
  a new `@Scheduled` job is wrapped in `TenantIterator.forEachActiveTenant` or annotated `@PlatformScoped`; native
  SQL and `JdbcTemplate` on scoped data carry `@TenantAudited`; a Kafka producer stamps the record with
  `TenantKafkaHeaders.record(...)`; application code reads `TenantContext` and never binds it (the one exception is
  `pos-tenant`, whose rows are platform data: its `tenant.provisioned` handler re-binds to `PlatformTenant.ID`, and
  `TenantRegistrySecretFilter` binds it at the request edge for the internal registry endpoint).
  **The rule constrains what a new table carries, not which file holds it (#1996).** The default home for a new
  table is still `V1__baseline_<module>.sql`, hand-edited — but only on the reset path: a deploy against a
  database that already ran the old `V1` fails Flyway validation (`checksum mismatch for migration version 1`)
  unless it is reset (`build-push-ecr.yml` dispatch with `deploy_alpha=true` **and** `reset_alpha_databases=true`;
  `docs/OPERATIONS_RUNBOOK.md` → "Schema reset") or repaired. A table introduced in a post-baseline migration
  instead is equally compliant as long as it carries the whole tenancy schema above — `tenant_id` first with the
  `app_current_tenant()` default, RLS enabled and forced, the `tenant_isolation` policy, a `(tenant_id, id)` unique
  constraint and tenant-leading unique constraints and foreign keys. The precedent is `pos-workorder`'s own
  `V3__service_position_and_single_technician.sql`; `pos-security-service`'s `V3__ext_tenant.sql` is a different
  case — a *global* table (no `tenant_id`, no RLS, listed in `tenancy-global-tables.txt`), not a scoped-table
  example. Such a table is not relocated into `V1` after the fact: moving DDL out of a migration other databases
  have already run needs the same reset or repair, and the next flattening folds it into the new baseline for
  free. Data reconciliation and any index over pre-existing rows always stay in a post-baseline migration.
- Permission registries: assert **enforcement → catalog**, never a bidirectional match. A module's
  `{Module}PermissionRegistry` constants and its `src/main/resources/permissions.yaml` catalog are not
  the same set and are not meant to be — `pos-inventory` currently has 57 catalog entries against 54
  constants, and that is legitimate (a catalog may declare an authority no local controller enforces).
  A test asserting set equality fails by design. The assertion that catches a real defect is
  `everyEnforcedAuthorityIsRegistered`: scan `src/main/java` for the authorities controllers actually
  enforce and require each to appear in the catalog, because a `@PreAuthorize` naming an unregistered
  authority is an endpoint **no role can ever be granted** — it fails closed at runtime with no build
  error. Pair it with `everyConstantIsRegistered` to cover constant-reference (non-literal)
  `@PreAuthorize` expressions. Exemplar:
  `pos-inventory/src/test/java/com/positivity/inventory/internal/security/InventoryPermissionRegistryTest.java`.

  Two static-analysis traps when writing that scan:
  1. **Parse balanced parentheses, not a fixed window.** Reading N characters forward from
     `hasAuthority(` sweeps up whatever annotation argument follows — an
     `@EmitEvent(id = "VEHICLE_SEARCH", …)` on the next method
     (`pos-vehicle-inventory/.../VehicleSearchController.java:78`) scores as an enforced authority
     and the test then demands a catalog entry for an event id.
  2. **Strip comments before scanning.** A `hasAuthority('…')` inside javadoc that documents an
     endpoint is not enforcement, but reads identically to a regex.
- Keep ArchUnit rules green.

## Where to Look

- Shared workspace guidance: `../durion/AGENTS.md`
- Knowledge catalog: `../durion/knowledge-catalog/backend/`
- Local domain docs: `../durion/domains/`
- Module-specific docs: each `pos-*` directory has a local `README.md` and `index.md`

## Related References

- `../durion/docs/architecture/BACKEND_ARCHITECTURE_GUIDE.md`
- `docs/DEVELOPMENT_GUIDE.md`
- `docs/OPERATIONS_RUNBOOK.md`
- `../durion/knowledge-catalog/`
