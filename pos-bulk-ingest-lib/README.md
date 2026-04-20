# pos-bulk-ingest-lib

Shared API contract library for domain bulk-ingest endpoints.

## Purpose

This library provides a standardized contract for domain services to implement bulk data ingestion endpoints. It enables `pos-bulk-loader` to orchestrate bulk imports across multiple domain services (catalog, inventory, location) without tight coupling.

## Provided Types

- `BulkIngestRequest<T>` — Request payload containing batch of records
- `BulkIngestResult` — Individual record processing result (success/failure)
- `BulkIngestResponse` — Aggregated batch processing response
- `AbstractBulkIngestController<T>` — Base controller with standard endpoint

## Controller Contract

`AbstractBulkIngestController<T>` exposes:

- `POST /bulk-ingest` (`200`)

Subclasses implement `processRecords(BulkIngestRequest<T>)` and provide module-specific routing with `@RequestMapping`.

## Usage

### 1. Add Dependency

In your domain service `pom.xml`:

```xml
<dependency>
    <groupId>com.positivity</groupId>
    <artifactId>pos-bulk-ingest-lib</artifactId>
</dependency>
```

### 2. Implement Controller

```java
package com.positivity.catalog.internal.controller;

import com.positivity.bulkingest.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/catalog")
public class CatalogBulkIngestController extends AbstractBulkIngestController<ProductImportDto> {

    private final ProductService productService;

    public CatalogBulkIngestController(ProductService productService) {
        this.productService = productService;
    }

    @Override
    protected BulkIngestResponse processRecords(BulkIngestRequest<ProductImportDto> request) {
        List<BulkIngestResult> results = new ArrayList<>();

        for (ProductImportDto dto : request.getRecords()) {
            try {
                productService.createProduct(dto);
                results.add(BulkIngestResult.success(dto.getId()));
            } catch (Exception e) {
                results.add(BulkIngestResult.failure(dto.getId(), e.getMessage()));
            }
        }

        return new BulkIngestResponse(results);
    }
}
```

### 3. Define DTO

```java
package com.positivity.catalog.internal.dto;

public class ProductImportDto {
    private String id;           // row identifier
    private String name;
    private String sku;
    private BigDecimal price;

    // getters/setters
}
```

## DTOs

### BulkIngestRequest<T>

```java
public class BulkIngestRequest<T> {
    private List<T> records;      // batch of records to process
    private String batchId;       // optional batch identifier

    // getters/setters
}
```

### BulkIngestResult

```java
public class BulkIngestResult {
    private String recordId;      // identifier from input record
    private boolean success;      // true if processed successfully
    private String errorMessage;  // error detail if success=false

    // factory methods
    public static BulkIngestResult success(String recordId) { ... }
    public static BulkIngestResult failure(String recordId, String error) { ... }
}
```

### BulkIngestResponse

```java
public class BulkIngestResponse {
    private List<BulkIngestResult> results;
    private int totalCount;
    private int successCount;
    private int failureCount;

    public BulkIngestResponse(List<BulkIngestResult> results) {
        // auto-calculates counts
    }
}
```

## Security Contract

**IMPORTANT**: This library does **NOT** include Spring Security dependencies or authentication/authorization logic.

- Domain services are responsible for their own security configuration
- Use `pos-security-common` (`GatewaySecurityConfig`) for gateway header-based authentication
- Apply method-level security annotations (`@PreAuthorize`) as needed
- Ensure bulk-ingest endpoints are protected with appropriate permissions (e.g., `BULK_IMPORT_EXECUTE`)

## Implementation Examples

Current implementations:

- **pos-catalog**: `CatalogBulkIngestController` — Product/Service bulk import
- **pos-inventory**: `InventoryBulkIngestController` — Stock level bulk import
- **pos-location**: `LocationBulkIngestController` — Location/Warehouse bulk import

See module-specific controllers for complete working examples.
