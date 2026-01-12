# Documentation

This directory contains project-wide documentation, including operational guides, architecture decision records, and clarification resolutions.

## Contents

### Architecture Decision Records (ADR)
See [adr/README.md](adr/README.md) for information about ADRs and how to create them.

- [ADR-0001: Inventory Ledger ATP Computation](adr/0001-inventory-ledger-atp-computation.md) - Defines On-Hand and Available-to-Promise calculation rules

### Clarification Resolutions

- [CLARIFICATION-233-RESOLUTION.md](CLARIFICATION-233-RESOLUTION.md) - Resolution of clarification issue #233 for story #36

### Operations

- [OperationsRunbook.md](OperationsRunbook.md) - Operational procedures and runbooks

## Module-Specific Documentation

Each module may have its own `docs/` directory with module-specific documentation:

- `pos-inventory/docs/` - Inventory module documentation including [Inventory Ledger ATP guide](../pos-inventory/docs/inventory-ledger-atp.md)

## Contributing to Documentation

When adding new documentation:

1. Place architecture decisions in `docs/adr/` following the ADR template
2. Place module-specific documentation in `<module>/docs/`
3. Update relevant README files to link to new documentation
4. Use descriptive filenames (kebab-case)
5. Include front matter or metadata where appropriate
