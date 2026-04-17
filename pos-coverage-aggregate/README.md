# pos-coverage-aggregate

This module exists only to generate a single aggregate JaCoCo XML report for
full-repository SonarCloud analysis.

## Purpose

- aggregate coverage from the backend reactor into one report
- provide a stable SonarCloud input at
  `target/site/jacoco-aggregate/jacoco.xml`
- support authoritative coverage analysis on `main` and nightly CI runs

## Typical Usage

From the backend repository root:

```bash
./mvnw -pl pos-coverage-aggregate -am -DskipITs verify
```

To run SonarCloud with the aggregate report:

```bash
./mvnw -pl pos-coverage-aggregate -am -DskipITs verify \
  org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
  -Dsonar.coverage.jacoco.xmlReportPaths=pos-coverage-aggregate/target/site/jacoco-aggregate/jacoco.xml
```

## Notes

- The module is intentionally `pom`-packaged and contains no production code.
- PR workflows continue to use lighter per-module coverage import; this module
  is for full-repository coverage lanes.
