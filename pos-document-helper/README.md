# pos-document-helper

Shared library for integrating with the `pos-documents` service for centralized document creation and template management.

## Purpose

This module provides reusable components that simplify document creation integration across all Positivity microservices, following the patterns established in [ADR-0020: Centralized Document Creation](../../durion/docs/adr/0020-documents-centralized-creation.adr.md).

## Components

### 1. Template Registration

- `TemplateRegistration` - DTO for registering HTML templates with pos-documents
- `DocumentTemplateInitializerSupport` - Helper for automatic template registration at startup

### 2. Document Rendering

- `RenderRequest` - Request DTO for document rendering
- `RenderResponse` - Response DTO containing rendered document
- `DocumentFormat` - Enum of supported formats (PDF, HTML, TEXT, CSV)

### 3. Client & Error Handling

- `DocumentServiceClient` - High-level RestClient-based client with retry logic
- `DocumentRenderException` - Exception for rendering failures
- `TemplateRegistrationException` - Exception for registration failures

## Usage

### 1. Add Dependency

```xml
<dependency>
    <groupId>com.positivity</groupId>
    <artifactId>pos-document-helper</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 2. Define Templates

Create a template registry in your module:

```java
package com.positivity.order.internal.config;

import com.positivity.documents.TemplateRegistration;
import java.util.List;

public final class OrderDocumentTemplates {
    private OrderDocumentTemplates() {}

    public static List<TemplateRegistration> all() {
        return List.of(
            TemplateRegistration.htmlTemplate(
                "ORDER_INVOICE",
                "Customer order invoice",
                loadTemplate("/templates/invoice.html")
            ),
            TemplateRegistration.htmlTemplate(
                "ORDER_RECEIPT",
                "Order receipt",
                loadTemplate("/templates/receipt.html")
            )
        );
    }

    private static String loadTemplate(String path) {
        // Load from classpath resources
        try (InputStream is = OrderDocumentTemplates.class.getResourceAsStream(path)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load template: " + path, e);
        }
    }
}
```

### 3. Register Templates at Startup

```java
package com.positivity.order.internal.config;

import com.positivity.documents.DocumentTemplateInitializerSupport;
import com.positivity.documents.TemplateRegistration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OrderDocumentTemplateInitializer implements ApplicationRunner {

    private final RestClient restClient;
    private final DocumentTemplateInitializerSupport initializerSupport;

    public OrderDocumentTemplateInitializer(
            RestClient.Builder restClientBuilder,
            @Value("${pos.documents.base-url:http://pos-documents:8080}") String documentsBaseUrl) {
        this.restClient = restClientBuilder
                .baseUrl(documentsBaseUrl)
                .build();
        this.initializerSupport = new DocumentTemplateInitializerSupport("pos-order");
    }

    @Override
    public void run(ApplicationArguments args) {
        initializerSupport.registerTemplates(
            OrderDocumentTemplates.all(),
            this::registerTemplate
        );
    }

    private void registerTemplate(TemplateRegistration registration) {
        restClient.put()
                .uri("/api/documents/templates/{templateId}", registration.getTemplateId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(registration)
                .retrieve()
                .toBodilessEntity();
    }
}
```

### 4. Render Documents

#### Option A: Using DocumentServiceClient (Recommended)

```java
@Service
public class InvoiceService {

    private final DocumentServiceClient documentClient;

    public InvoiceService(
            @Value("${pos.documents.base-url:http://pos-documents:8080}") String documentsBaseUrl) {
        this.documentClient = DocumentServiceClient.builder()
                .baseUrl(documentsBaseUrl)
                .maxRetries(3)
                .build();
    }

    public byte[] generateInvoicePdf(Order order) {
        Map<String, Object> data = Map.of(
            "orderNumber", order.getOrderNumber(),
            "customerName", order.getCustomerName(),
            "items", order.getItems(),
            "total", order.getTotal()
        );

        RenderRequest request = RenderRequest.pdf("ORDER_INVOICE", data)
                .filename("invoice-" + order.getOrderNumber() + ".pdf")
                .build();

        RenderResponse response = documentClient.renderDocument(request);
        return response.getContent();
    }
}
```

#### Option B: Using RestClient Directly

```java
@Service
public class InvoiceService {

    private final RestClient restClient;

    public InvoiceService(
            RestClient.Builder restClientBuilder,
            @Value("${pos.documents.base-url:http://pos-documents:8080}") String documentsBaseUrl) {
        this.restClient = restClientBuilder.baseUrl(documentsBaseUrl).build();
    }

    public byte[] generateInvoicePdf(Order order) {
        RenderRequest request = RenderRequest.pdf("ORDER_INVOICE", invoiceData).build();

        return restClient.post()
                .uri("/api/documents/render")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(byte[].class);
    }
}
```

## Configuration

Add to your `application.yml`:

```yaml
pos:
  documents:
    base-url: http://pos-documents:8080  # or use Eureka service discovery
    max-retries: 3
    retry-wait-duration: 500ms
```

## Error Handling

The module provides two custom exceptions:

- `TemplateRegistrationException` - Thrown when template registration fails
- `DocumentRenderException` - Thrown when document rendering fails

Both exceptions include context (template ID, format) and support cause chaining for debugging.

```java
try {
    RenderResponse response = documentClient.renderDocument(request);
} catch (DocumentRenderException e) {
    log.error("Failed to render template {}: {}",
        e.getTemplateId(), e.getMessage(), e);
    // Handle error (fallback, retry, alert, etc.)
}
```

## Testing

Use Testcontainers or mock the RestClient for integration tests:

```java
@Test
void testDocumentRendering() {
    RestClient mockClient = mock(RestClient.class);
    // Configure mock responses...

    DocumentServiceClient client = new DocumentServiceClient(
        mockClient,
        Retry.ofDefaults("test")
    );

    RenderResponse response = client.renderDocument(testRequest);
    assertNotNull(response.getContent());
}
```

## Related Documentation

- [ADR-0020: Centralized Document Creation](../../durion/docs/adr/0020-documents-centralized-creation.adr.md)
- [pos-documents Service](../pos-documents/README.md)
- [pos-events Pattern](../pos-events/README.md) - Similar registration pattern

## Architecture Notes

- This module is a **shared library** (JAR packaging), not a service
- It mirrors the `pos-events` pattern for startup registration
- Templates are re-registered on every service restart to ensure freshness
- Uses RestClient (Spring Framework 6.x+) for HTTP communication
- Includes Resilience4j retry logic for resilience against transient failures
- All exceptions are runtime exceptions for cleaner service layer code
