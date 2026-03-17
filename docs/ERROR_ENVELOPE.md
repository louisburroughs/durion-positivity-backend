# Error Envelope — Durion Backend API

All Durion backend REST APIs return a consistent `ApiError` JSON object for non-2xx responses.

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
| `fieldErrors` | `array\|null`    | ❌ Conditional  | Present only when `code` is `VALIDATION_ERROR` or `VALIDATION_FAILED`. Each entry names the offending field and why it failed. Omitted (not `null`) for all other error types. |
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

## Common Error Codes by Module

### pos-order
| Code | Status | Description |
|------|--------|-------------|
| `ORDER_NOT_FOUND` | 404 | Sales order does not exist |
| `ORDER_INVALID_SKU` | 400 | SKU on the order line is not valid |
| `ORDER_PRICE_OVERRIDE_NOT_FOUND` | 404 | Price override record not found |
| `ORDER_PRICE_OVERRIDE_INVALID` | 422 | Price override failed business validation |
| `ORDER_PRICE_OVERRIDE_IDEMPOTENCY_CONFLICT` | 409 | Duplicate idempotency key for price override |
| `ORDER_CANCELLATION_INVALID` | 409 | Order cannot be cancelled in its current state |
| `ORDER_FORBIDDEN` | 403 | Caller lacks required order permissions |

### pos-invoice
| Code | Status | Description |
|------|--------|-------------|
| `NOT_FOUND` | 404 | Invoice or receipt not found |
| `INVALID_STATE` | 409 | Invoice state transition is not allowed |
| `CONFLICT` | 409 | General state conflict (e.g. already finalized) |
| `PAYMENT_DECLINED` | 422 | Payment gateway declined the transaction |
| `PAYMENT_WINDOW_EXPIRED` | 422 | Refund window for the payment has closed |
| `INSUFFICIENT_REFUNDABLE_AMOUNT` | 422 | Refund amount exceeds what was originally paid |

### pos-inventory
| Code | Status | Description |
|------|--------|-------------|
| `NOT_FOUND` | 404 | Inventory resource not found |
| `VALIDATION_ERROR` | 400 | Request parameter validation failed |
| `INSUFFICIENT_STOCK` | 422 | Not enough on-hand stock to fulfill |
| `INSUFFICIENT_ATP` | 422 | Available-to-promise quantity is insufficient |
| `RETURN_QUANTITY_EXCEEDED` | 422 | Return exceeds original purchase quantity |
| `ADJUSTMENT_LEDGER_POST_FAILED` | 500 | Ledger post for adjustment failed |

### pos-security-service
| Code | Status | Description |
|------|--------|-------------|
| `ROLE_NOT_FOUND` | 404 | Role does not exist |
| `USER_NOT_FOUND` | 404 | User does not exist |
| `DUPLICATE_ROLE_NAME` | 409 | Role name is already taken |
| `ACCOUNT_LOCKED` | 401 | Account is locked due to repeated failures |
| `ACCOUNT_DISABLED` | 401 | Account has been disabled by an administrator |
| `BAD_CREDENTIALS` | 401 | Username or password is incorrect |
| `FORBIDDEN` | 403 | Caller lacks required permissions |

### pos-accounting
| Code | Status | Description |
|------|--------|-------------|
| `DUPLICATE_EVENT` | 409 | Event with this ID has already been processed |
| `UNBALANCED_ENTRY` | 422 | Journal entry debits and credits do not balance |
| `GL_POSTING_FAILED` | 409 | General ledger posting failed |
| `DUPLICATE_ACCOUNT_CODE` | 409 | Chart of accounts code already exists |

### pos-catalog
| Code | Status | Description |
|------|--------|-------------|
| `NOT_FOUND` | 404 | Catalog item not found |
| `FORBIDDEN` | 403 | Operation not permitted on this catalog entry |
| `VALIDATION_ERROR` | 400 | Catalog data validation failed |
| `BUSINESS_RULE_VIOLATION` | 409 | Catalog business rule was violated |
| `CONFLICT` | 409 | Concurrent update detected; retry required |

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
