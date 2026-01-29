# Spring Modulith Configuration Guide

**Status:** CONFIGURED  
**Date:** 2026-01-29  
**Framework Version:** Spring Modulith 1.3.0  
**Compatibility:** Spring Boot 3.4.2, Java 21

---

## Overview

This document describes the Spring Modulith configuration for `durion-positivity-backend`. Spring Modulith enforces module boundaries at compile and runtime, preventing circular dependencies and ensuring alignment with domain-driven design principles defined in [ADR-0009](../docs/adr/0009-backend-domain-responsibilities-guide.adr.md).

---

## Architecture

### Module Structure

All `pos-*` services are now organized as Spring Modulith modules under a central `pos-modulith` aggregator:

```
pos-modulith/                    ← Aggregator (Spring Boot executable)
├── pos-accounting/             ← Financial domain
├── pos-invoice/                ← Invoice management
├── pos-catalog/                ← Product catalog
├── pos-inventory/              ← Inventory management
├── pos-location/               ← Location hierarchy
├── pos-people/                 ← HR domain
├── pos-workorder/              ← Job/task management
├── pos-shop-manager/           ← Shop operations
├── pos-customer/               ← Customer relations
├── pos-vehicle-inventory/      ← Vehicle management
├── pos-vehicle-fitment/        ← Vehicle-to-parts mapping
├── pos-vehicle-reference-*/    ← External vehicle data
├── pos-inquiry/                ← Vendor integration
├── pos-order/                  ← Order management
├── pos-image/                  ← Image storage & serving
├── pos-events/                 ← Event publishing
├── pos-event-receiver/         ← Event consumption
├── pos-security-service/       ← Authentication & authorization
├── pos-mcp-server/             ← AI integration
└── pos-api-gateway/            ← API routing
```

### Module Detection

Modules are detected using **classpath detection** with package naming convention:
- **Module name:** `com.positivity.{domain}`
- **Module API:** Classes in `api` subpackage (exported)
- **Module internals:** All other classes are package-private

Example for accounting module:
```
com.positivity.accounting/
├── api/
│   ├── TransactionService.java        ← PUBLIC (exported)
│   └── JournalEntryEvent.java         ← PUBLIC
├── service/
│   └── TransactionProcessor.java      ← INTERNAL
├── entity/
│   └── GLAccount.java                 ← INTERNAL
└── repository/
    └── TransactionRepository.java     ← INTERNAL
```

---

## Module Dependencies

### Enforcement Rules

**Allowed:**
- Modules may depend on other modules' **public APIs** (from `api` packages)
- Cross-module communication via **events** (primary) or **REST** (secondary)
- Service discovery for runtime routing

**Not Allowed:**
- Direct access to internal classes (non-`api` packages)
- Circular dependencies
- Direct database access across modules
- Synchronous calls during initialization

### Dependency Graph (from ADR-0009)

Key dependencies:
```
pos-order          → pos-inventory, pos-accounting, pos-customer
pos-workorder      → pos-people, pos-inventory, pos-accounting
pos-shop-manager   → pos-workorder, pos-people, pos-location
pos-inventory      → pos-location
pos-accounting     → (foundational, no dependencies)
pos-events         → (utilities, can be used by all)
```

---

## Event-Driven Communication

### Publishing Events

Modules publish domain events using `ApplicationEventPublisher`:

```java
@Service
public class OrderService {
    private final ApplicationEventPublisher publisher;
    
    public void createOrder(OrderRequest request) {
        Order order = Order.create(request);
        orderRepository.save(order);
        
        // Publish domain event
        publisher.publishEvent(new OrderCreatedEvent(order.getId(), order.getCustomerId()));
    }
}
```

### Event Listeners

Other modules listen for events from the `pos-events` module:

```java
@Service
public class InventoryService {
    
    @EventListener
    void onOrderCreated(OrderCreatedEvent event) {
        // Reserve inventory asynchronously
        inventoryReserveService.reserve(event.getOrderId());
    }
}
```

### Event Configuration

In `pos-modulith/src/main/resources/application.yml`:
```yaml
spring.modulith:
  events:
    async-mode: async              # Events are async by default
    chunk-size: 32
    thread-pool-size: 8
```

---

## Setup Instructions

### 1. Build the Project

```bash
cd durion-positivity-backend

# Build all modules including pos-modulith aggregator
./mvnw clean install

# Or build pos-modulith aggregator only (which pulls all dependencies)
./mvnw -pl pos-modulith -am clean package
```

### 2. Run Module Structure Validation

```bash
# Run Spring Modulith tests to verify module boundaries
./mvnw -pl pos-modulith test

# Example output:
# ✓ Accounting module found
# ✓ Inventory module found
# ✓ People module found
# ✓ Workorder module found
```

### 3. Run the Application

```bash
# Run the aggregator (listens on port 8888)
java -jar pos-modulith/target/pos-modulith-0.0.1-SNAPSHOT.jar

# Or via Maven
./mvnw -pl pos-modulith spring-boot:run
```

### 4. Access Modulith Documentation

The application generates module documentation at:
- **HTML Report:** `target/spring-modulith-docs/index.html`
- **PlantUML Diagrams:** `target/spring-modulith-docs/`
- **JSON Model:** `target/spring-modulith-docs/modules.json`

Generate documentation:
```bash
./mvnw -pl pos-modulith spring-modulith:generate
```

---

## Module Configuration Pattern

### Creating a New Module

1. **Create package structure:**
   ```
   src/main/java/com/positivity/{domain}/
   ├── api/
   ├── service/
   ├── entity/
   ├── repository/
   └── config/
   ```

2. **Define module API** (`api` package):
   ```java
   // com.positivity.accounting.api.TransactionService.java
   public interface TransactionService {
       void recordTransaction(TransactionRequest request);
       List<Transaction> getTransactionHistory(String accountId);
   }
   ```

3. **Implement services** (other packages):
   ```java
   // com.positivity.accounting.service.TransactionProcessor.java (internal)
   @Service
   class TransactionProcessor implements TransactionService {
       // implementation
   }
   ```

4. **Register in `@NamedInterface`** (Spring Modulith):
   ```java
   // com.positivity.accounting.config.AccountingConfiguration.java
   @Configuration
   class AccountingConfiguration {
       @Bean
       NamedInterface accountingApi() {
           return NamedInterface.of("Accounting API", 
               TransactionService.class, 
               JournalEntryService.class);
       }
   }
   ```

---

## Validation & Testing

### Module Structure Tests

The `ModuleStructureTests` class validates:

```java
@Test
void verifyModuleStructure() {
    // Fails if circular dependencies detected
    // Fails if modules access non-public APIs
    // Warns if undocumented dependencies exist
    ApplicationModules.of(PositivityModulithApplication.class).verify();
}
```

Run tests:
```bash
./mvnw -pl pos-modulith test -Dtest=ModuleStructureTests
```

### Integration Tests

Test module contracts:
```java
@SpringModulithTest
class OrderToInventoryIntegrationTests {
    
    @Test
    void orderCreationReservesInventory() {
        // Verify event flow from Order → Inventory
        OrderService orderService = // injected
        InventoryService inventoryService = // injected
        
        orderService.createOrder(...);
        // Event processed asynchronously
        Thread.sleep(100);
        
        assertThat(inventoryService.getReservedQuantity(...)).isGreaterThan(0);
    }
}
```

---

## Monitoring & Observability

### Actuator Endpoints

```bash
# View module structure
curl http://localhost:8888/actuator/modulith

# Health check
curl http://localhost:8888/actuator/health

# Metrics (Micrometer)
curl http://localhost:8888/actuator/metrics
```

### Logging Configuration

In `application.yml`:
```yaml
logging:
  level:
    org.springframework.modulith: DEBUG
    com.positivity: DEBUG
```

Example debug output:
```
[Thread-1] ApplicationModuleDetection: Detecting modules for [com.positivity.modulith.PositivityModulithApplication]
[Thread-1] ApplicationModule: Bootstrapping module 'accounting' (com.positivity.accounting)
[Thread-1] ApplicationModule: Bootstrapping module 'inventory' (com.positivity.inventory)
[Thread-1] DeclaredModule: Verifying boundaries for 'accounting' module
```

---

## Migration Guide: Existing Services

### Phase 1: Restructure Package Layout (No Code Changes)

Rename packages to follow `com.positivity.{domain}.api`:

```bash
# Before:
com.positivity.accounting.controller
com.positivity.accounting.service
com.positivity.accounting.repository

# After:
com.positivity.accounting.api         ← NEW: Public APIs
com.positivity.accounting.service     ← Internal
com.positivity.accounting.repository  ← Internal
```

### Phase 2: Expose Public APIs

Move public service interfaces to `api` package:

```java
// BEFORE: com.positivity.accounting.service.TransactionService
public class TransactionService { ... }

// AFTER: com.positivity.accounting.api.TransactionService
public interface TransactionService { ... }

// AFTER: com.positivity.accounting.service.TransactionServiceImpl
@Service
class TransactionServiceImpl implements TransactionService { ... }
```

### Phase 3: Update Dependencies

In each `pos-*` module's `pom.xml`, add:

```xml
<dependency>
    <groupId>org.springframework.modulith</groupId>
    <artifactId>spring-modulith-core</artifactId>
</dependency>
```

### Phase 4: Validation & Testing

Run module structure tests to identify violations:

```bash
./mvnw -pl pos-modulith test
```

---

## Troubleshooting

### Issue: "Circular dependency detected"

**Cause:** Module A depends on B, and B depends on A (directly or indirectly)

**Solution:** Use event-driven communication to break the cycle:
```
Before (circular):
  OrderService → InventoryService → OrderService (ordering check)

After (event-driven):
  OrderService publishes OrderCreatedEvent
  InventoryService listens and processes independently
```

### Issue: "Access to non-public API detected"

**Cause:** Module accessing internal class from another module

**Solution:** Create public API interface in `api` package:
```java
// DO NOT ACCESS: com.positivity.accounting.service.TransactionProcessor
// DO ACCESS: com.positivity.accounting.api.TransactionService

// Add to accounting module:
@Bean
public TransactionService transactionService() {
    return new TransactionProcessorImpl();  // internal impl
}
```

### Issue: "Module not detected"

**Cause:** Package naming doesn't match `com.positivity.{domain}` pattern

**Solution:** Verify package structure:
```bash
# Should have exactly 2 parts after com.positivity
✓ com.positivity.accounting
✗ com.positivity.service.accounting (too many parts)
✗ com.positivity (too few parts)
```

---

## References

- [Spring Modulith Documentation](https://spring.io/projects/spring-modulith)
- [ADR-0009: Backend Domain Responsibilities](../docs/adr/0009-backend-domain-responsibilities-guide.adr.md)
- [OWASP Module Design Principles](https://owasp.org/www-project-modular-architecture/)

---

## Next Steps

1. **Phase 1 (Q1 2026):** Restructure existing modules to follow `com.positivity.{domain}.api` pattern
2. **Phase 2 (Q2 2026):** Run module structure tests and fix boundary violations
3. **Phase 3 (Q2 2026):** Implement event-driven communication patterns
4. **Phase 4 (Q3 2026):** Enable Spring Modulith enforcement in CI/CD pipeline
5. **Phase 5 (Q4 2026):** Document module contracts and publish API documentation
