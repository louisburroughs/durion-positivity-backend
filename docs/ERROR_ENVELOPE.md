# Error Envelope — Durion Backend API

All Durion backend REST APIs return a consistent `ApiError` JSON object for non-2xx responses.

**Scope.** This document describes the envelope *shape* (required by [ADR-0017 §3](https://github.com/louisburroughs/durion/blob/main/docs/adr/0017-api-controller-http-response-codes.adr.md))
and the platform fallback codes emitted by the shared catch-all handler ([ADR-0056](adr-0056-global-exception-handling.md)).
It does not catalogue the error codes of individual modules — see [Module Error Codes](#module-error-codes) for where those live.

## Schema

```json
{
  "code": "string",
  "message": "string",
  "status": 0,
  "timestamp": "string",
  "correlationId": "string",
  "fieldErrors": [
    {
      "field": "string",
      "message": "string"
    }
  ],
  "referenceId": "string",
  "nextAction": "string",
  "supportAction": "string"
}
```

## Field Semantics

| Field         | Type             | Always Present | Description |
|---------------|------------------|----------------|-------------|
| `code`        | `string`         | ✅ Yes          | Machine-readable error code (e.g. `ORDER_NOT_FOUND`). Use this in client code for programmatic error handling. |
| `message`     | `string`         | ✅ Yes          | Human-readable description intended for developers or end-user display. Do not rely on the exact phrasing in client logic. |
| `status`      | `integer`        | ✅ Yes          | HTTP status code mirrored in the body for clients that can't access response headers easily. |
| `timestamp`   | `string`         | ✅ Yes          | ISO 8601 UTC timestamp when the error occurred (e.g. `2026-03-17T14:30:00.123456789Z`). |
| `correlationId` | `string`       | ✅ Yes          | UUID identifying this specific request across all services. Include this in bug reports and support tickets. Also present in the `X-Correlation-Id` response header. |
| `fieldErrors` | `array\|null`    | ❌ Conditional  | Present (non-null) when the response contains field-level validation details; typically accompanies validation-related codes such as `VALIDATION_ERROR` or `VALIDATION_FAILED`. Each entry names the offending field and why it failed. Omitted entirely for all other error types. |
| `referenceId` | `string\|null`   | ❌ Conditional  | Reference to a workflow case, review request, or external audit record. Present for guided error flows such as self-registration review. |
| `nextAction`  | `string\|null`   | ❌ Conditional  | Recommended action for the caller to resolve the error (e.g. "Sign in with the existing account"). Present alongside `referenceId`. |
| `supportAction` | `string\|null` | ❌ Conditional  | Investigation guidance for operations or support staff. Not intended for end-user display. |

> **Note:** Fields that are `null` or absent are omitted from the JSON payload entirely (Jackson `@JsonInclude(NON_NULL)`). Clients should treat a missing field as `null`, not as an error.

---

## Payload Examples

### HTTP 400 — Validation Error

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "status": 400,
  "timestamp": "2026-03-17T14:30:00.123456789Z",
  "correlationId": "019507b4-1f3a-7000-8e04-5c9d3a4f6e12",
  "fieldErrors": [
    {
      "field": "quantity",
      "message": "must be greater than 0"
    },
    {
      "field": "customerId",
      "message": "must not be null"
    }
  ]
}
```

### HTTP 404 — Not Found

```json
{
  "code": "ORDER_NOT_FOUND",
  "message": "Sales order '019507b4-1f3a-7000-8e04-5c9d3a4f6e12' was not found",
  "status": 404,
  "timestamp": "2026-03-17T14:30:00.123456789Z",
  "correlationId": "019507b4-1f3a-7000-8e04-5c9d3a4f6e12"
}
```

### HTTP 409 — Conflict

```json
{
  "code": "DUPLICATE_PROMO_CODE",
  "message": "Promotion code 'SUMMER25' already exists",
  "status": 409,
  "timestamp": "2026-03-17T14:30:00.123456789Z",
  "correlationId": "019507b4-1f3a-7001-8e04-5c9d3a4f6e12"
}
```

### HTTP 422 — Business Rule Violation

```json
{
  "code": "RETURN_QUANTITY_EXCEEDED",
  "message": "Return quantity 10 exceeds the original purchase quantity 5",
  "status": 422,
  "timestamp": "2026-03-17T14:30:00.123456789Z",
  "correlationId": "019507b4-1f3a-7002-8e04-5c9d3a4f6e12"
}
```

### HTTP 500 — Internal Server Error

```json
{
  "code": "INTERNAL_ERROR",
  "message": "Unexpected error occurred",
  "status": 500,
  "timestamp": "2026-03-17T14:30:00.123456789Z",
  "correlationId": "019507b4-1f3a-7003-8e04-5c9d3a4f6e12"
}
```

### HTTP 401 — Guided Authentication Error (Security Service)

```json
{
  "code": "ACCOUNT_LOCKED",
  "message": "Account is temporarily locked",
  "status": 401,
  "timestamp": "2026-03-17T14:30:00.123456789Z",
  "correlationId": "019507b4-1f3a-7004-8e04-5c9d3a4f6e12",
  "referenceId": "019507b4-2f3a-8000-9e04-6c9d3a4f7e13",
  "nextAction": "Wait for the lockout period to expire or contact an administrator to unlock your account.",
  "supportAction": "Check the audit log for repeated failed login attempts from this user and determine if this is a brute-force attempt."
}
```

### HTTP 403 — Forbidden

```json
{
  "code": "FORBIDDEN",
  "message": "Access denied",
  "status": 403,
  "timestamp": "2026-03-17T14:30:00.123456789Z",
  "correlationId": "019507b4-1f3a-7005-8e04-5c9d3a4f6e12"
}
```

---

## Platform Fallback Codes (pos-web-common)

Emitted by the shared `GlobalApiExceptionHandler` (auto-configured from `pos-web-common`, see
`docs/adr-0056-global-exception-handling.md`) when no service-specific advice mapped the exception.
Any service may therefore return these in addition to its own module codes.

| Code | Status | Description |
|------|--------|-------------|
| `DUPLICATE_RESOURCE` | 409 | Unique-constraint violation (SQLSTATE 23505); message names the constraint |
| `REFERENCE_CONFLICT` | 409 | Foreign-key constraint violation |
| `DATA_INTEGRITY_VIOLATION` | 409 | Other database integrity violation |
| `MISSING_REQUIRED_VALUE` | 422 | Not-null violation on a client-supplied column; message names the column |
| `CONSTRAINT_VIOLATION` | 422 | Check-constraint violation; message names the constraint |
| `VALIDATION_ERROR` | 400 | Bean-validation failure (with `fieldErrors`) or malformed request |
| `NOT_FOUND` | 404 | No endpoint for the requested path |
| `METHOD_NOT_ALLOWED` | 405 | HTTP method not supported for this path |
| `NOT_ACCEPTABLE` / `PAYLOAD_TOO_LARGE` / `UNSUPPORTED_MEDIA_TYPE` / `REQUEST_REJECTED` | 406/413/415/other 4xx | Framework-rejected request |
| `INTERNAL_ERROR` | 500 | Unhandled exception, or a not-null violation on a server-populated audit column; stack trace logged at ERROR against the `correlationId` |

---

## Module Error Codes

Each module's error codes are defined by its own `@RestControllerAdvice` handlers (typically under
`internal/exception`), and every code is a string literal there. Those handlers, and the module's
generated OpenAPI spec, are the source of truth for what a client can receive. Look there, not here.

This document deliberately does not maintain per-module code tables. An earlier version did, and the
tables were a hand-maintained snapshot that drifted from the code within weeks (see
louisburroughs/durion-positivity-backend#1724). A stale table is worse than none because it reads as
complete.

Conventions that every module code follows regardless of module:

- `code` is a stable, upper-snake-case identifier; clients branch on it, never on `message`.
- The HTTP status follows the ADR-0017 matrix (`404` missing resource, `409` identity/version/lifecycle
  conflict, `422` domain-policy refusal, `400` request-shape or field validation).
- Codes are additive: a module may introduce new codes in a release, and clients should fall through to
  a generic handler for any code they do not recognise (see below).

---

## Client Handling Guidelines

### Recommended Response Handling

```typescript
interface ApiError {
  code: string;
  message: string;
  status: number;
  timestamp: string;
  correlationId: string;
  fieldErrors?: Array<{ field: string; message: string }>;
  referenceId?: string;
  nextAction?: string;
  supportAction?: string;
}

async function handleApiError(response: Response): Promise<never> {
  const error: ApiError = await response.json();
  
  switch (error.code) {
    case 'VALIDATION_ERROR':
    case 'VALIDATION_FAILED':
      // Display field-level errors to the user
      displayFieldErrors(error.fieldErrors ?? []);
      break;
    case 'ORDER_NOT_FOUND':
    case 'NOT_FOUND':
      navigateTo('/404');
      break;
    case 'FORBIDDEN':
      showPermissionDeniedMessage();
      break;
    default:
      showGenericError(error.message, error.correlationId);
  }
  
  throw new Error(`[${error.correlationId}] ${error.code}: ${error.message}`);
}
```

### Correlation ID

Always log the `correlationId` when an error occurs. Include it in support tickets so errors can be traced across all services in the distributed system.

The same ID is also available in the `X-Correlation-Id` response header for access without parsing the body.

---

## Implementation Notes

- The canonical type is `com.positivity.shared.error.ApiError` in the `pos-shared-dtos` module.
- All error handler methods in `@RestControllerAdvice` classes return `ResponseEntity<ApiError>`.
- Factory methods available on the `ApiError` record:
  - `ApiError.of(code, message, status, timestamp, correlationId)` — simple error with no optional fields
  - `ApiError.withFieldErrors(code, message, status, timestamp, correlationId, fieldErrors)` — validation error
  - `ApiError.guided(code, message, status, timestamp, correlationId, referenceId, nextAction, supportAction)` — guided workflow error
