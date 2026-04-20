# Development Guide

This document covers development workflows, build configuration, OpenAPI documentation, version management, and the pos-events shared library for the durion-positivity-backend microservices platform.

## Table of Contents

1. [Java Version Management with SDKMAN!](#java-version-management-with-sdkman)
2. [Build Configuration](#build-configuration)
3. [Runtime Profile Matrix](#runtime-profile-matrix)
4. [OpenAPI Documentation](#openapi-documentation)
5. [Version Management](#version-management)
6. [pos-events Shared Library](#pos-events-shared-library)
7. [Spring Boot 4.0 Migration Status](#spring-boot-40-migration-status)

---

## Java Version Management with SDKMAN

This project uses **SDKMAN!** to manage Java versions consistently across development environments.

### Why SDKMAN!?

- **Automatic version switching**: When you `cd` into the project, the correct Java version is activated automatically
- **Consistent environments**: All developers use the same Java distribution and version
- **Multiple Java versions**: Install and manage multiple Java versions side-by-side without conflicts

### Required Java Version

This project requires **Java 25.0.2-tem** (Eclipse Temurin 25.0.2), as specified in `.sdkmanrc`.

### Installation & Setup

1. **Install SDKMAN!** (one-time setup):

   ```bash
   curl -s "https://get.sdkman.io" | bash
   source "$HOME/.sdkman/bin/sdkman-init.sh"
   ```

2. **Enable automatic environment switching**:
   Edit `~/.sdkman/etc/config` and set:

   ```properties
   sdkman_auto_env=true
   ```

3. **Install the required Java version**:

   ```bash
   cd durion-positivity-backend
   sdk env install
   ```

   This reads `.sdkmanrc` and installs Java 25.0.2-tem if not already present.

4. **Verify the version**:

   ```bash
   java -version
   # Should output: openjdk version "25.0.2"
   ```

### Usage

With `sdkman_auto_env=true`, SDKMAN! automatically switches to the correct Java version when you enter the project directory:

```bash
cd durion-positivity-backend
# SDKMAN! activates Java 25.0.2-tem automatically

java -version
# openjdk version "25.0.2" 2026-10-15
```

### Manual Switching

If automatic switching is disabled, use:

```bash
sdk env
```

### Troubleshooting

**Problem**: "SDK version not available"

```bash
sdk list java           # View available versions
sdk install java 25.0.2-tem
```

**Problem**: Wrong Java version active

```bash
sdk env                 # Force environment switch
sdk current java        # Check active version
```

---

## Build Configuration

### POM Consolidation

All dependency versions are centralized in the root `pom.xml`:

**Version Properties:**

```xml
<properties>
    <java.version>25</java.version>
    <spring-cloud.version>2025.1.1</spring-cloud.version>
    <junit5.version>5.10.1</junit5.version>
    <mockito.version>5.8.0</mockito.version>
    <assertj.version>3.25.1</assertj.version>
    <lombok.version>1.18.32</lombok.version>
    <slf4j.version>2.0.13</slf4j.version>
    <springdoc-openapi.version>2.7.0</springdoc-openapi.version>
    <swagger-annotations.version>2.2.44</swagger-annotations.version>
    <opentelemetry.version>1.40.0</opentelemetry.version>
</properties>
```

### Internal BOM (`pos-dependencies`)

The `pos-dependencies` module manages internal artifact versions:

- pos-events
- pos-archunit
- pos-agent-framework

### Common Commands

```bash
# Full build with tests
./mvnw clean package

# Fast build (skip tests)
./mvnw clean package -DskipTests

# Build single module
./mvnw -pl pos-order -am clean package

# Run tests
./mvnw -DskipTests=false clean test

# Check for dependency updates
./mvnw versions:display-dependency-updates
./mvnw versions:display-plugin-updates
```

---

## Runtime Profile Matrix

The backend uses four canonical environment/build profiles:

| Profile   | Activation                                                | Primary Purpose                                                                                          | Intended Environment  |
| --------- | --------------------------------------------------------- | -------------------------------------------------------------------------------------------------------- | --------------------- |
| `openapi` | Maven profile (`-Popenapi`)                               | Generate module `openapi.yaml` using `spring-boot:start/stop` and `springdoc-openapi:generate`           | Build-time only       |
| `dev`     | Spring runtime profile (`--spring.profiles.active=dev`)   | Local developer runtime with H2 and minimal laptop-friendly defaults                                     | Developer workstation |
| `alpha`   | Spring runtime profile (`--spring.profiles.active=alpha`) | Near-production runtime defaults for EC2 alpha environment                                               | AWS EC2 alpha         |
| `prod`    | Spring runtime profile (`--spring.profiles.active=prod`)  | Production runtime configuration (env-driven; intentionally minimal while production is being finalized) | Production            |

Migration note:

- Legacy profile names are retired: `local` is replaced by `dev`, and `preprod` is replaced by `alpha`.

---

## OpenAPI Documentation

### Overview

All 19 POS modules have complete OpenAPI 3.0.1 documentation integration using SpringDoc OpenAPI 2.7.0.

### Standard Pattern

**pom.xml:**

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>${springdoc-openapi.version}</version>
</dependency>

<profile>
    <id>openapi</id>
    <!-- Contains spring-boot:start/stop and springdoc-openapi:generate goals -->
</profile>
```

**SecurityConfig.java:**

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated()
            )
            .build();
    }
}
```

### Generating OpenAPI Specs

**Method 1: Maven Profile (Recommended)**

```bash
cd pos-inventory
./mvnw -Popenapi clean verify -DskipTests
# Output: openapi.yaml
```

**Method 2: Manual**

```bash
cd pos-inventory
java -jar target/pos-inventory-*.jar --spring.profiles.active=dev --server.port=8093 &
curl http://localhost:8093/v3/api-docs.yaml > openapi.yaml
```

### Access Points (When Running)

| Endpoint     | URL                                             |
| ------------ | ----------------------------------------------- |
| Swagger UI   | `http://localhost:{port}/swagger-ui/index.html` |
| OpenAPI JSON | `http://localhost:{port}/v3/api-docs`           |
| OpenAPI YAML | `http://localhost:{port}/v3/api-docs.yaml`      |
| Health Check | `http://localhost:{port}/actuator/health`       |

### Controller Annotations

```java
@RestController
@RequestMapping("/v1/inventory")
@Tag(name = "Inventory API", description = "Inventory management operations")
public class InventoryController {

    @GetMapping("/items")
    @Operation(summary = "List inventory items", description = "Returns paginated inventory")
    @ApiResponse(responseCode = "200", description = "Success")
    public ResponseEntity<Page<InventoryItem>> listItems(
        @Parameter(description = "Page number") @RequestParam(defaultValue = "0") int page,
        @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {
        // ...
    }
}
```

---

## Version Management

### Using the Update Script

```bash
# Make executable (first time)
chmod +x scripts/update-version.sh

# Preview changes
./scripts/update-version.sh patch

# Bump and commit
./scripts/update-version.sh patch --commit
./scripts/update-version.sh minor --commit
./scripts/update-version.sh major --commit
```

### Manual Version Update

```bash
# View current version
mvn help:evaluate -Dexpression=project.version -q -DforceStdout

# Set version across all modules
mvn versions:set -DnewVersion=0.2.0-SNAPSHOT -DprocessAllModules

# Commit changes
git add pom.xml **/pom.xml
git commit -m "chore: bump version to 0.2.0-SNAPSHOT"
```

### Release Workflow

```bash
# 1. Remove -SNAPSHOT
mvn versions:set -DnewVersion=0.2.0 -DprocessAllModules

# 2. Commit and tag
git add pom.xml **/pom.xml
git commit -m "chore: release version 0.2.0"
git tag v0.2.0

# 3. Bump to next development version
./scripts/update-version.sh patch --commit

# 4. Push
git push origin main --tags
```

---

## pos-events Shared Library

### Overview

The `pos-events` library provides annotation-driven event emission and observability for microservices.

### Public API

**@EmitEvent Annotation:**

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface EmitEvent {
    String id();  // Unique event identifier
    String apiVersion() default "1";
}
```

**EventEmitted Record:**

```java
public record EventEmitted(
    String eventId,
    long timestamp,
    Instant publishedAt
)
```

### Usage

**Add Dependency:**

```xml
<dependency>
    <groupId>com.positivity</groupId>
    <artifactId>pos-events</artifactId>
    <version>${project.version}</version>
</dependency>
```

**Annotate Methods:**

```java
@Service
public class OrderService {

    @EmitEvent(id = "ORDER_CREATE", apiVersion = "1")
    public Order createOrder(OrderRequest request) {
        // Business logic
        return order;
    }
}
```

**Listen to Events:**

```java
@Component
public class OrderEventListener {

    @EventListener
    public void handleOrderEvent(EventEmitted event) {
        log.info("Event received: {} at {}", event.eventId(), event.publishedAt());
    }
}
```

### Event Type Registry Pattern

Each module defines event types with performance thresholds:

```java
public final class OrderEventTypes {
    public static List<EventTypeRegistration> all() {
        return List.of(
            EventTypeRegistration.fastRead("ORDER_LIST", "List orders").build(),
            EventTypeRegistration.write("ORDER_CREATE", "Create order").build(),
            EventTypeRegistration.approval("ORDER_APPROVE", "Approve order").build()
        );
    }
}
```

**Threshold Presets:**

| Preset     | p50   | p95   | p99   | Use Case          |
| ---------- | ----- | ----- | ----- | ----------------- |
| `fastRead` | 50ms  | 200ms | 500ms | Simple GET/list   |
| `search`   | 100ms | 500ms | 1s    | Search/filter     |
| `write`    | 200ms | 1s    | 3s    | POST/PUT/DELETE   |
| `approval` | 500ms | 2s    | 5s    | Workflow approval |

### Auto-Configuration

The library uses Spring Boot auto-configuration — no manual setup required. Components are automatically registered when pos-events is on the classpath.

---

## Spring Boot 4.0 Migration Status

### Current State

**Completed (Phase 1-2):**

- ✅ Spring Boot: 3.4.2 → 4.0.1
- ✅ Spring Cloud: 2024.0.0 → 2025.1.1
- ✅ RestTemplate → RestClient migration (15 files, 8 modules)
- ✅ Spring AOP manual configuration
- ✅ All 27 modules compile successfully
- ✅ Eureka Server runtime validated
- ✅ Test infrastructure updated

**Deferred (Future Work):**

- ⏳ Jackson 3.0 code migration (tools.jackson.\* group ID)
- ⏳ Spring Security 7.0 refactoring

### Critical Version Enforcement

**Required Versions:**

- Spring Boot: 4.0.1
- Spring Cloud: 2025.1.1 (minimum)
- Java: 21 LTS

**DO NOT USE:** Spring Cloud 2025.0.0 (incompatible with Boot 4.0.1)

### RestClient Migration Pattern

**Before (RestTemplate):**

```java
restTemplate.getForEntity(url, Response.class);
```

**After (RestClient):**

```java
restClient.get().uri(url).retrieve().body(Response.class);
```

---

## Quick Reference Commands

```bash
# Build all
./mvnw clean package -DskipTests

# Build single module
./mvnw -pl pos-order -am clean package

# Run single module
cd pos-order && ./mvnw spring-boot:run

# Generate OpenAPI spec
./mvnw -pl pos-order -Popenapi clean verify -DskipTests

# Check dependency updates
./mvnw versions:display-dependency-updates

# Bump version
./scripts/update-version.sh minor --commit

# Run tests
./mvnw -DskipTests=false clean test

# Module-only tests
./mvnw -pl pos-order -am test
```

---

## References

- [SpringDoc OpenAPI](https://springdoc.org/)
- [OpenAPI 3.0 Specification](https://spec.openapis.org/oas/v3.0.3)
- [Maven Versions Plugin](https://www.mojohaus.org/versions/versions-maven-plugin/)
- [Semantic Versioning](https://semver.org/)
