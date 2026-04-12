# Documentation

This directory contains project-wide documentation for the durion-positivity-backend microservices platform.

## Core Documents

| Document | Description |
| ---------- | ------------- |
| [ARCHITECTURE_GUIDE.md](ARCHITECTURE_GUIDE.md) | Docker, ports, service communication, observability, PostgreSQL setup |
| [DEVELOPMENT_GUIDE.md](DEVELOPMENT_GUIDE.md) | OpenAPI, POM consolidation, version management, pos-events library |
| [OPERATIONS_RUNBOOK.md](OPERATIONS_RUNBOOK.md) | Operations, monitoring, RBAC framework, permission registration |
| [profile-reconciliation-plan.md](profile-reconciliation-plan.md) | Canonical backend profile reconciliation plan (`openapi`, `dev`, `alpha`, `prod`) |

## Module-Specific Documentation

Each module may have its own `docs/` directory with module-specific documentation:

- `pos-inventory/docs/` - Inventory module documentation

## Architecture Decision Records

ADRs are centralized in the main Durion docs repository:

- See `durion/docs/adr/` for ADRs and how to create them

## Contributing to Documentation

When adding new documentation:

1. For architecture/infrastructure: Update [ARCHITECTURE_GUIDE.md](ARCHITECTURE_GUIDE.md)
2. For development workflows: Update [DEVELOPMENT_GUIDE.md](DEVELOPMENT_GUIDE.md)
3. For operations/security: Update [OPERATIONS_RUNBOOK.md](OPERATIONS_RUNBOOK.md)
4. For module-specific docs: Place in `<module>/docs/`
5. For architecture decisions: Add to `durion/docs/adr/`

Use descriptive filenames (kebab-case) and include front matter where appropriate.
