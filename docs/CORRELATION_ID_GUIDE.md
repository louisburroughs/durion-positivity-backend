# Correlation ID Implementation Guide

**Standard:** `X-Correlation-Id` header for distributed tracing and request correlation

**Decision References:**
- DECISION-INVENTORY-012: Correlation ID propagation standard
- DECISION-POSITIVITY-014: W3C Trace Context integration
- DECISION-SHOPMGMT-011: Updated to use X-Correlation-Id

---

## Backend Implementation (Spring Boot)

### Controller Pattern

Add `X-Correlation-Id` header parameter to all REST endpoints:

```java
@PostMapping("/resource")
public ResponseEntity<?> createResource(
    @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
    @RequestBody ResourceRequest request) {
    
    log.info("Create resource requested. correlationId={}, request={}", correlationId, request);
    ResourceResponse response = resourceService.create(request, correlationId);
    return ResponseEntity.ok(response);
}
```

### Service Layer Pattern

Accept and propagate correlation ID through service methods:

```java
public ResourceResponse create(ResourceRequest request, String correlationId) {
    log.info("[ResourceService] create correlationId={}, request={}", correlationId, request);
    
    // Propagate to downstream services
    // Include in error responses
    
    return response;
}
```

### Error Response Pattern

Always include correlation ID in error responses:

```java
public class ErrorResponse {
    private String errorCode;
    private String message;
    private String correlationId;
    private Instant timestamp;
    private Map<String, String> fieldErrors;
    
    public ErrorResponse(String errorCode, String message, String correlationId) {
        this.errorCode = errorCode;
        this.message = message;
        this.correlationId = correlationId;
        this.timestamp = Instant.now();
    }
    // ... getters/setters
}
```

### OpenAPI Documentation

Document the header in Swagger annotations:

```java
@Parameter(description = "Correlation ID for request tracing")
@RequestHeader(value = "X-Correlation-Id", required = false) String correlationId
```

---

## Frontend Implementation (TypeScript/JavaScript)

### Import Utilities

```typescript
import {
  fetchWithCorrelation,
  parseErrorResponse,
  formatErrorWithCorrelation,
  addCorrelationIdHeader
} from '@/utils/correlationId';
```

### Using Fetch Wrapper

```typescript
// Automatic correlation ID injection
const response = await fetchWithCorrelation('/api/v1/appointments', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json'
  },
  body: JSON.stringify(appointmentData)
});

if (!response.ok) {
  const error = await parseErrorResponse(response);
  console.error(formatErrorWithCorrelation(error.message, error.correlationId));
  // Display error with correlation ID to user
}
```

### Manual Header Injection

```typescript
const options = addCorrelationIdHeader({
  method: 'GET',
  headers: {
    'Authorization': 'Bearer token'
  }
});

const response = await fetch('/api/v1/resource', options);
```

### Error Display

Always show correlation ID in error messages for support troubleshooting:

```vue
<template>
  <div v-if="error" class="error-message">
    <p>{{ error.message }}</p>
    <small v-if="error.correlationId">
      Correlation ID: {{ error.correlationId }}
    </small>
  </div>
</template>

<script setup lang="ts">
import { parseErrorResponse } from '@/utils/correlationId';

const error = ref<CorrelatedError | null>(null);

async function handleError(response: Response) {
  error.value = await parseErrorResponse(response);
}
</script>
```

---

## API Gateway Configuration

### Required Capabilities

1. **Generate Correlation ID** if not present on incoming request
2. **Propagate Correlation ID** to all downstream services
3. **Include Correlation ID** in all response headers
4. **Log Correlation ID** for request/response pairs

### Example Configuration (Conceptual)

```yaml
# Spring Cloud Gateway example
spring:
  cloud:
    gateway:
      default-filters:
        - name: CorrelationId
          args:
            headerName: X-Correlation-Id
            generateIfMissing: true
```

---

## Testing

### Backend Unit Tests

```java
@Test
public void testCorrelationIdPropagation() {
    String correlationId = "test-correlation-123";
    
    ResponseEntity<?> response = controller.createResource(
        correlationId,
        new ResourceRequest()
    );
    
    // Verify correlation ID was passed to service
    verify(service).create(any(), eq(correlationId));
}
```

### Frontend Unit Tests

```typescript
import { addCorrelationIdHeader, extractCorrelationId } from '@/utils/correlationId';

describe('Correlation ID Utils', () => {
  test('adds correlation ID header if not present', () => {
    const options = addCorrelationIdHeader({});
    const headers = new Headers(options.headers);
    
    expect(headers.has('X-Correlation-Id')).toBe(true);
  });
  
  test('preserves existing correlation ID', () => {
    const existingId = 'existing-123';
    const options = addCorrelationIdHeader({
      headers: { 'X-Correlation-Id': existingId }
    });
    const headers = new Headers(options.headers);
    
    expect(headers.get('X-Correlation-Id')).toBe(existingId);
  });
});
```

---

## Existing Implementations

### pos-people Module (Reference)

```java
// TimeEntryApprovalController.java
@PostMapping("/timeEntries/{timeEntryId}/approve")
public ResponseEntity<?> approveEntry(
    @PathVariable UUID timeEntryId,
    @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
    // Implementation
}
```

### pos-shop-manager Module (Updated)

```java
// AppointmentsController.java
@PostMapping("/appointments")
public ResponseEntity<Object> createAppointment(
    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
    @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
    @RequestBody AppointmentCreateRequest request) {
    // Implementation
}
```

---

## Best Practices

1. **Always Optional**: Make `X-Correlation-Id` header optional (required = false)
2. **Generate if Missing**: Gateway/service should generate if client doesn't provide
3. **Log Consistently**: Include in all structured logs for request/response pairs
4. **Include in Errors**: Always return correlation ID in error responses
5. **Display to Users**: Show correlation ID in user-facing error messages
6. **No PII**: Never include PII in correlation ID or related logs
7. **W3C Trace Context**: Use `traceparent` header when available as primary trace context

---

## Migration Guide

### Updating Existing Endpoints

1. Add `@RequestHeader(value = "X-Correlation-Id", required = false) String correlationId` parameter
2. Update service method signatures to accept `correlationId`
3. Add correlation ID to log statements
4. Include correlation ID in error responses
5. Update OpenAPI documentation

### Updating Existing Frontend Code

1. Import correlation ID utilities
2. Replace `fetch()` calls with `fetchWithCorrelation()`
3. Update error handlers to extract and display correlation ID
4. Add correlation ID to error reporting/analytics (excluding PII)

---

## Support and Troubleshooting

When investigating issues:

1. **Ask user for Correlation ID** from error message
2. **Search logs** for the correlation ID across all services
3. **Trace request flow** through gateway → services → database
4. **Identify failures** at specific service boundaries

Example log query:
```
correlationId:"abc-123-def" AND timestamp:[2026-01-25T00:00:00 TO 2026-01-25T23:59:59]
```
