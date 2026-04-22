# pos-dependencies

Maven Bill of Materials (BOM) for all internal Durion POS library artifacts and shared third-party dependency versions. Consuming modules import this BOM to get consistent, compatible versions without specifying individual version numbers.

## Responsibilities

- Declare managed versions for all internal `pos-*` library artifacts (`pos-security-common`, `pos-events`, `pos-shared-dtos`, `pos-tax-common`, `pos-document-helper`, `pos-bulk-ingest-lib`, `pos-archunit`)
- Pin third-party shared dependencies (`uuid-creator`, Spotless plugin)
- Serve as the single version authority for the backend reactor

## Usage

Import in the root `pom.xml` or in any module that needs to control internal artifact versions:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.positivity</groupId>
      <artifactId>pos-dependencies</artifactId>
      <version>${project.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

## Notes

This is a `pom`-packaged BOM with no source code. It is not a deployable service and has no runnable main class.
