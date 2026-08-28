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
