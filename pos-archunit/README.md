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
- `DomainWallsTest` — source-based ADR-0044 check that `internal.client` code only targets utility modules
- `ClasspathVisibilityGuardTest` — fails the build when module classes are invisible to ArchUnit (see below)
- `PositivityArchunitApplication` — placeholder Spring Boot application required for classpath scanning

## Dependencies (test scope)

`pos-accounting`, `pos-invoice`, `pos-catalog`, `pos-inventory`, `pos-location`, `pos-people`, `pos-workorder`, `pos-shop-manager`, `pos-customer`, `pos-vehicle-inventory`, `pos-vehicle-fitment`

## Development

This is a test-only aggregator. It does not expose a runnable service.

```bash
# Run all architecture tests — the -am (also-make) flag is REQUIRED, see below
./mvnw -pl pos-archunit -am test
```

Architecture violations fail the build. Add new domain modules to the `pom.xml` dependency list, to the
corresponding `ArchitectureTests` rule, and to `ClasspathVisibilityGuardTest.MODULE_ROOT_PACKAGES` when
they are created.

### Why `-am` is required (issue #909)

The class-graph rules import `com.positivity` from the test classpath. In a reactor build (`-am`), sibling
modules resolve to their `target/classes` directories, which ArchUnit can read. In a solo build
(`mvn -pl pos-archunit test`), they resolve to the installed Spring Boot repackaged fat jars, whose
application classes live under `BOOT-INF/classes/` where ArchUnit cannot see them — every rule would match
zero classes and pass vacuously. `ClasspathVisibilityGuardTest` turns that silent pass into a loud failure
by asserting each module dependency contributes at least one imported class (and each enforced entity
package contains at least one `@Entity`). The CI job therefore runs this module with
`-am -Dtest='com/positivity/archunit/*' -Dsurefire.failIfNoSpecifiedTests=false` so upstream modules are
compiled but only this module's tests execute.
