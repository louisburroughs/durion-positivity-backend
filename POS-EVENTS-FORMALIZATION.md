# pos-events Library Formalization Research

**Date**: January 30, 2026  
**Scope**: Analysis of pos-events shared library structure, consumer modules, and formalization requirements

---

## 1. pos-events Library Overview

### Purpose
The `pos-events` library is a **shared Spring Boot library** that provides annotation-driven event emission and observability infrastructure for microservices within the Durion POS backend. It enables decoupled, event-driven communication between modules via Spring's `ApplicationEventPublisher`.

### Key Design Principles
- **Annotation-driven**: Methods annotated with `@EmitEvent` automatically emit domain events
- **Transparent**: AOP-based interception (no explicit event publishing code in business logic)
- **Observability**: Logs event lifecycle (start, end, errors) with timestamps
- **Asynchronous**: Published events are consumed by other modules via `@EventListener`

---

## 2. Public API Surface

### Public API Classes (Intended for consumers)

#### 1. **`@EmitEvent` Annotation** (PUBLIC API)
```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface EmitEvent {
    String id();  // Unique event identifier
}
```
- **Purpose**: Marker annotation for methods that should emit domain events
- **Consumer Usage**: Applied to business logic methods in service classes
- **Contract**: Guarantees an `EventEmitted` domain event is published after method completes
- **Scope**: Compile-time (used as metadata by `EmitEventAspect`)

#### 2. **`EventEmitted` Record** (PUBLIC API)
```java
public record EventEmitted(
    String eventId,
    long timestamp,
    Instant publishedAt
)
```
- **Purpose**: Domain event payload published after `@EmitEvent` method execution
- **Consumer Usage**: Listened to via `@EventListener` or `@TransactionalEventListener` in other modules
- **Contract**: Contains event ID, execution timestamp, and publication time
- **Factory Method**: `EventEmitted.from(String eventId, long timestamp)` for creation

#### 3. **`EmitEventProxyFactory`** (PUBLIC API - optional)
```java
@Component
public class EmitEventProxyFactory {
    public <T> T createProxy(T target, Class<T> interfaceType)
}
```
- **Purpose**: Spring-managed factory for creating event-emitting JDK dynamic proxies
- **Consumer Usage**: When AOP is unavailable or proxy-based interception is preferred
- **Contract**: Returns a proxy that intercepts methods and publishes `EventEmitted` events
- **Note**: This is a complementary approach to `EmitEventAspect` for edge cases

### Internal Implementation Classes (NOT FOR EXTERNAL USE)

#### 1. **`EmitEventAspect`**
- **Purpose**: Spring AOP aspect that intercepts `@EmitEvent` methods
- **Access**: Spring component (instantiated by Spring container)
- **Status**: Implementation detail; should not be directly imported or instantiated by consumers
- **Responsibility**: Method interception, timing measurement, event publication, error handling

#### 2. **`EmitEventProxy`** (Utility class)
- **Purpose**: Creates JDK dynamic proxies for event interception
- **Access**: Static factory method; used internally by `EmitEventProxyFactory`
- **Status**: Implementation detail; low-level utility
- **Note**: Private no-arg constructor; meant for internal factory use only

#### 3. **`PosEventsApplication`**
- **Purpose**: Spring Boot application class for the module
- **Current Role**: Enables Spring Boot scanning and auto-configuration
- **Status**: **PROBLEMATIC** - A library-only JAR should not need `@SpringBootApplication`

---

## 3. Consuming Modules

### Current Consumers of pos-events

| Module | Dependency Scope | Usage Pattern |
|--------|------------------|---------------|
| **pos-accounting** | compile | Planned for `@EmitEvent` annotations on service methods |
| **pos-workorder** | compile | Planned for `@EmitEvent` annotations on service methods |
| **pos-catalog** | compile | Planned for `@EmitEvent` annotations on service methods |
| **pos-vehicle-fitment** | compile | Planned for `@EmitEvent` annotations (audit logging) |
| **pos-event-receiver** | compile | Consumes `EventEmitted` events via `@EventListener` in `EmitEventService` |
| **pos-archunit** | test | Architecture testing of module boundaries |
| **pos-dependencies** | (managed) | BOM definition for version management across all modules |

### Dependency Scope Analysis
- **All consumers use `compile` scope** (default) except `pos-archunit` which uses `test` scope
- **Version Management**: All consumers specify `version>0.0.1-SNAPSHOT</version>` or `${project.version}`
- **Centralized Version**: `pos-dependencies/pom.xml` declares `pos-events` in `<dependencyManagement>` for version alignment

---

## 4. Current Library Structure Assessment

### pom.xml Configuration

```xml
<artifactId>pos-events</artifactId>
<packaging>jar</packaging>
<dependencies>
    <!-- Lombok for annotation processing -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <scope>provided</scope>
    </dependency>

    <!-- SLF4J API for logging -->
    <dependency>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-api</artifactId>
    </dependency>

    <!-- Spring Boot Starter AOP for AspectJ support -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-aop</artifactId>
    </dependency>

    <!-- Spring Boot Actuator -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>

    <!-- Spring Boot Logging (includes SLF4J + Logback) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-logging</artifactId>
    </dependency>

    <!-- ArchUnit for testing -->
    <dependency>
        <groupId>com.tngtech.archunit</groupId>
        <artifactId>archunit-junit5</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### Analysis

#### Current State
- **Packaging**: `jar` ✅ (correct for a library)
- **Build Section**: None configured ✅ (inherits from parent Spring Boot parent)
- **Spring Boot Dependencies**: Includes `spring-boot-starter-*` artifacts
  - `spring-boot-starter-aop` - **REQUIRED** for `@Aspect` annotation processing
  - `spring-boot-starter-actuator` - **QUESTIONABLE** for a library (actuator is for apps)
  - `spring-boot-starter-logging` - **QUESTIONABLE** (consumers can provide logging)
- **Application Class**: `PosEventsApplication` with `@SpringBootApplication` - **PROBLEMATIC**

#### Issues with Current Structure
1. **`@SpringBootApplication` on library**: 
   - Only Spring Boot applications should have `@SpringBootApplication`
   - Libraries should use auto-configuration or configuration classes
   - The annotation triggers unnecessary beans that aren't needed in consuming services

2. **Unnecessary Spring Boot Starters**:
   - `spring-boot-starter-actuator`: Not needed for a library; consumers don't need exposing actuator just because they use events
   - `spring-boot-starter-logging`: Libraries should declare logging API (`slf4j-api`), not implementation

3. **Dependency Leakage**:
   - Consumers transitively inherit all Spring Boot starters
   - This inflates JAR sizes and startup times

---

## 5. Formalization Recommendations

### Phase 1: Remove Application Bootstrap (REQUIRED)

**Action**: Delete `PosEventsApplication.java`

**Reason**: Libraries don't need a `@SpringBootApplication` class. Spring's auto-configuration will handle component discovery.

```bash
rm /home/louisb/Projects/durion-positivity-backend/pos-events/src/main/java/com/positivity/events/PosEventsApplication.java
```

### Phase 2: Enable Auto-Configuration (REQUIRED)

**Action**: Create `spring-boot-starter.properties` or use `@Configuration` class

**Reason**: Components like `EmitEventAspect` and `EmitEventProxyFactory` need to be available to consumers without explicit imports.

**Option A (Recommended): Create `resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`**

```properties
com.positivity.events.EmitEventAspectAutoConfiguration
```

**Option B: Create a `@Configuration` class**

```java
package com.positivity.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PosEventsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EmitEventAspect emitEventAspect(ApplicationEventPublisher publisher) {
        return new EmitEventAspect(publisher);
    }

    @Bean
    @ConditionalOnMissingBean
    public EmitEventProxyFactory emitEventProxyFactory(ApplicationEventPublisher publisher) {
        return new EmitEventProxyFactory(publisher);
    }
}
```

### Phase 3: Trim Dependencies (RECOMMENDED)

**Action**: Remove unnecessary Spring Boot starters

**Current**:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-logging</artifactId>
</dependency>
```

**Proposed**:
```xml
<!-- Required for @Aspect and ApplicationEventPublisher -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>

<!-- Logging API only (consumers provide implementation) -->
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
</dependency>

<!-- Spring Framework core (needed for @Component, @EventListener) -->
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-context</artifactId>
</dependency>
```

**Rationale**:
- `spring-boot-starter-actuator`: Remove—not needed in a library
- `spring-boot-starter-logging`: Replace with `slf4j-api` only—library declares API, app provides implementation
- Consumers won't bloat their runtime with unnecessary actuator endpoints

### Phase 4: Package Structure Review (DOCUMENTATION)

**Current Structure** (Correct):
```
com.positivity.events/
├── EmitEvent.java                  ← PUBLIC API (annotation)
├── EventEmitted.java               ← PUBLIC API (event record)
├── EmitEventProxyFactory.java       ← PUBLIC API (optional factory)
├── EmitEventAspect.java            ← INTERNAL (AOP implementation)
├── EmitEventProxy.java             ← INTERNAL (JDK proxy utility)
└── PosEventsApplication.java       ← DELETE (not needed in library)
```

**Recommended Action**: Create a `README.md` documenting public vs. internal APIs

```markdown
# pos-events Library

## Public API
- `@EmitEvent` annotation
- `EventEmitted` record
- `EmitEventProxyFactory` bean

## Internal (Do Not Use Directly)
- `EmitEventAspect`
- `EmitEventProxy`
```

### Phase 5: Version Management (BEST PRACTICE)

**Current State**: All consumers explicitly declare version or use `${project.version}`

**Recommendation**: Keep centralized version in `pos-dependencies/pom.xml` and all consumers should use:
```xml
<dependency>
    <groupId>com.positivity</groupId>
    <artifactId>pos-events</artifactId>
    <!-- Version inherited from pos-dependencies BOM -->
</dependency>
```

---

## 6. Testing & Verification

### After Formalization

1. **Rebuild all modules**:
   ```bash
   cd durion-positivity-backend
   ./mvnw clean package -DskipTests
   ```

2. **Run tests**:
   ```bash
   ./mvnw test
   ```

3. **Verify auto-configuration**:
   - Run a consuming module and confirm `EmitEventAspect` and `EmitEventProxyFactory` beans are initialized
   - Check startup logs for `EmitEventAspectAutoConfiguration` registration

4. **Dependency tree analysis**:
   ```bash
   ./mvnw dependency:tree -pl pos-accounting
   ```
   Confirm `spring-boot-starter-actuator` is no longer transitively included

---

## 7. Summary

### What pos-events Is
A **shared Spring Boot auto-configurable library** providing annotation-driven event emission infrastructure for decoupled, observable microservice communication.

### Public API
1. `@EmitEvent` annotation → marks methods for automatic event emission
2. `EventEmitted` record → event payload consumed by other modules
3. `EmitEventProxyFactory` bean → optional factory for proxy-based interception

### Consuming Modules
- **6 Production Modules**: pos-accounting, pos-workorder, pos-catalog, pos-vehicle-fitment, pos-event-receiver, pos-archunit
- **All use compile scope** (or test for pos-archunit)
- **Centralized version management** via pos-dependencies BOM

### Current Issues
1. ❌ `PosEventsApplication` with `@SpringBootApplication` — wrong for a library
2. ❌ `spring-boot-starter-actuator` — unnecessary dependency leakage
3. ❌ `spring-boot-starter-logging` — should be API-only

### Formalization Steps (Priority Order)
1. **DELETE** `PosEventsApplication.java`
2. **ADD** Spring Boot auto-configuration (via `.imports` file or `@Configuration` class)
3. **REMOVE** actuator and logging starters; keep only AOP and slf4j-api
4. **DOCUMENT** public API vs. internal implementation
5. **VERIFY** all consuming modules still build and auto-configuration works

### Expected Outcome
A properly formalized shared library that:
- ✅ Enables event-driven communication across modules
- ✅ Minimizes transitive dependencies
- ✅ Follows Spring Boot library best practices
- ✅ Maintains backward compatibility with existing consumers
- ✅ Enables clear separation of public API from internal implementation

