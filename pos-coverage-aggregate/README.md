# pos-coverage-aggregate

Aggregate JaCoCo report module for full-repository SonarCloud analysis. This module contains no production code — it exists only to combine JaCoCo XML coverage data from all `pos-*` modules into a single report consumed by SonarCloud.

## Responsibilities

- Aggregate JaCoCo coverage from all backend reactor modules into one XML report
- Provide a stable SonarCloud input at `target/site/jacoco-aggregate/jacoco.xml`
- Support authoritative full-repository coverage analysis on main and nightly CI runs

## Dependencies

- `pos-shared-dtos`, `pos-security-common` — included to ensure coverage from cross-cutting libraries is captured

## Development

This is a `pom`-packaged aggregator with no runnable service. It is not intended to be started.

```bash
# Generate the aggregate report
./mvnw -pl pos-coverage-aggregate -am -DskipITs verify

# Run with SonarCloud analysis
./mvnw -pl pos-coverage-aggregate -am -DskipITs verify \
  org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
  -Dsonar.coverage.jacoco.xmlReportPaths=pos-coverage-aggregate/target/site/jacoco-aggregate/jacoco.xml
```

PR workflows use lighter per-module coverage; this module is for the full-repository coverage lane.
