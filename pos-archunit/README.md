# pos-archunit

Architecture testing aggregator that enforces internal package encapsulation and layering constraints across all `pos-*` domain modules using ArchUnit and JUnit 5. This module has no production code; it exists solely to run cross-module architectural validation during the Maven test phase.

## Responsibilities

- Assert that `internal.*` packages in each domain module are not accessed by other modules
- Verify that controllers do not call repositories directly (must go through the service layer)
- Confirm that only `service.*` packages are treated as public APIs
- Validate entity naming conventions and JPA annotation standards across all domains

## Key Classes

- `ArchitectureTests` — primary cross-module ArchUnit test suite; validates inter-module boundaries
- `EntityStandardsArchitectureTest` — validates JPA entity conventions (naming, annotations, ID strategy)
- `PositivityArchunitApplication` — placeholder Spring Boot application required for classpath scanning

## Dependencies (test scope)

`pos-accounting`, `pos-invoice`, `pos-catalog`, `pos-inventory`, `pos-location`, `pos-people`, `pos-workorder`, `pos-shop-manager`, `pos-customer`, `pos-vehicle-inventory`, `pos-vehicle-fitment`

## Development

This is a test-only aggregator. It does not expose a runnable service.

```bash
# Run all architecture tests
./mvnw -pl pos-archunit -am test
```

Architecture violations fail the build. Add new domain modules to the `pom.xml` dependency list and to the corresponding `ArchitectureTests` rule when they are created.
