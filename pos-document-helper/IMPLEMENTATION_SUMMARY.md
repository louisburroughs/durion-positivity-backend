# pos-document-helper - Implementation Summary

## Overview

The `pos-document-helper` module provides a **simplified, reusable integration layer** for the centralized document creation pattern established in ADR-0020. It mirrors the successful `pos-events` registration pattern.

## What Was Added (New Simplified API)

### Package: `com.positivity.documents` (New - Simplified API recommended for new code)

This package provides a **clean, minimal API** for document integration:

#### Core DTOs
- **`TemplateRegistration`** - Register HTML templates with pos-documents
  - Builder pattern with sensible defaults
  - Helper method: `TemplateRegistration.htmlTemplate(id, description, content)`

- **`RenderRequest`** - Request document rendering
  - Helper methods: `RenderRequest.pdf(templateId, data)`, `RenderRequest.html(templateId, data)`
  - Supports PDF, HTML, TEXT, CSV formats (enum: `DocumentFormat`)

- **`RenderResponse`** - Rendered document with metadata
  - Contains byte[] content, content type, size, filename, etc.

#### Helper Classes
- **`DocumentTemplateInitializerSupport`** - Startup registration helper
  - Registers templates at service startup (ApplicationRunner pattern)
  - Logs success/failure counts
  - Mirrors `EventTypeInitializerSupport` from pos-events

- **`DocumentServiceClient`** - High-level RestClient-based client
  - Built-in Resilience4j retry logic (configurable max retries + wait duration)
  - Methods: `registerTemplate(registration)`, `renderDocument(request)`
  - Fluent builder: `DocumentServiceClient.builder().baseUrl(...).maxRetries(3).build()`

#### Error Handling
- **`DocumentRenderException`** - Rendering failures (includes templateId + format context)
- **`TemplateRegistrationException`** - Registration failures (includes templateId context)

Both are **runtime exceptions** for cleaner service layer code.

## What Already Existed

### Package: `com.positivity.documents.helper` (Existing - Legacy API)

This package contains existing infrastructure that predated ADR-0020:

- `DocumentClient` - Lower-level document client
- `DocumentTemplateInitializer` - Abstract base class for template initialization
- `TemplateUtils` - Template validation and loading utilities
- Various other DTOs and exceptions in the `helper` subpackage

**Recommendation:** New code should prefer the simplified `com.positivity.documents` API. The `helper` package remains for backward compatibility.

## Usage Pattern

### 1. Add Dependency

```xml
<dependency>
    <groupId>com.positivity</groupId>
    <artifactId>pos-document-helper</artifactId>
</dependency>
```

### 2. Define Templates

```java
public final class OrderDocumentTemplates {
    public static List<TemplateRegistration> all() {
        return List.of(
            TemplateRegistration.htmlTemplate(
                "ORDER_INVOICE",
                "Customer order invoice",
                loadTemplateFromClasspath("/templates/invoice.html")
            )
        );
    }
}
```

### 3. Register at Startup

```java
@Component
public class OrderDocumentTemplateInitializer implements ApplicationRunner {
    
    private final DocumentServiceClient client;
    private final DocumentTemplateInitializerSupport support;

    public OrderDocumentTemplateInitializer(
            @Value("${pos.documents.base-url:http://pos-documents:8080}") String baseUrl) {
        this.client = DocumentServiceClient.builder().baseUrl(baseUrl).build();
        this.support = new DocumentTemplateInitializerSupport("pos-order");
    }

    @Override
    public void run(ApplicationArguments args) {
        support.registerTemplates(OrderDocumentTemplates.all(), client::registerTemplate);
    }
}
```

### 4. Render Documents

```java
@Service
public class InvoiceService {
    private final DocumentServiceClient client;

    public byte[] generateInvoicePdf(Order order) {
        RenderRequest request = RenderRequest.pdf("ORDER_INVOICE", orderData)
                .filename("invoice.pdf")
                .build();

        try {
            RenderResponse response = client.renderDocument(request);
            return response.getContent();
        } catch (DocumentRenderException e) {
            log.error("Failed to render invoice: {}", e.getMessage(), e);
            throw e;
        }
    }
}
```

## Error Handling

Both custom exceptions include rich context:

```java
try {
    client.renderDocument(request);
} catch (DocumentRenderException e) {
    log.error("Template: {}, Format: {}, Error: {}", 
        e.getTemplateId(),  // "ORDER_INVOICE"
        e.getFormat(),       // "PDF"
        e.getMessage(),
        e
    );
}
```

## Configuration

Add to `application.yml`:

```yaml
pos:
  documents:
    base-url: http://pos-documents:8080  # or from Eureka
```

## Architecture Alignment

- ✅ Mirrors `pos-events` startup registration pattern
- ✅ Follows ADR-0020 centralized document creation
- ✅ Uses RestClient (Spring Framework 6.x+)
- ✅ Includes Resilience4j for retry logic
- ✅ Runtime exceptions for cleaner service code
- ✅ Template re-registration on every startup ensures freshness

## Testing

Use Testcontainers or mock the `DocumentServiceClient` for integration tests. The module includes comprehensive unit tests demonstrating usage patterns.

## Related Documentation

- [ADR-0020: Centralized Document Creation](../../../durion/docs/adr/0020-documents-centralized-creation.adr.md)
- [pos-documents Service](../pos-documents/README.md)
- [pos-events Pattern](../pos-events/README.md)

## Issue Reference

- **Issue:** ADR-0020
