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
| `fieldErrors` | `array\|null`    | ❌ Conditional  | Present (non-null) when the response contains field-level validation details; typically accompanies validation-related codes such as `VALIDATION_ERROR` or `VALIDATION_FAILED`. Each entry names the offending field and why it failed. Omitted entirely for all other error types. |
| `referenceId` | `string\|null`   | ❌ Conditional  | Reference to a workflow case, review request, or external audit record. Present for guided error flows such as self-registration review. |
| `nextAction`  | `string\|null`   | ❌ Conditional  | Recommended next step for the caller to resolve the error (e.g. "Sign in with the existing account"). May appear with or without `referenceId` — guided flows such as self-registration review pair it with a `referenceId`, while authorization refusals such as `USER_HAS_NO_ROLES` and `MANAGER_APPROVAL_REQUIRED` carry it alone. |
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
Any service may therefore return these in addition to its module codes below.

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
| `RETURN_LINE_NOT_RETURNABLE` | 422 | Requested return line is not returnable per policy (issue #1694; split out of the former blanket `RETURN_INVALID_ARGUMENT` 422 catch-all) |

### pos-invoice
| Code | Status | Description |
|------|--------|-------------|
| `NOT_FOUND` | 404 | Invoice or receipt not found |
| `INVALID_STATE` | 409 | Invoice state transition is not allowed |
| `CONFLICT` | 409 | General state conflict (e.g. already finalized) |
| `PAYMENT_DECLINED` | 422 | Payment gateway declined the transaction |
| `PAYMENT_WINDOW_EXPIRED` | 422 | Refund window for the payment has closed |
| `INSUFFICIENT_REFUNDABLE_AMOUNT` | 422 | Refund amount exceeds what was originally paid |
| `MANAGER_APPROVAL_REQUIRED` | 403 | Finalizing this invoice exceeds the amount cap and no manager-approval elevation token was supplied — a step-up credential the caller lacks (ADR-0017 §2 question 1, #1725; introduced by #1694 as a 422). `nextAction` points at `elevateManagerApproval` |
| `MANAGER_APPROVAL_INVALID` | 403 | Supplied manager-approval elevation token does not verify (wrong scope, tampered, or expired) — a step-up credential the server considers insufficient (ADR-0017 §2 question 1, #1725; introduced by #1694 as a 422). `nextAction` points at `elevateManagerApproval` |
| `EXCESSIVE_ADJUSTMENT` | 422 | Adjustment would drive the invoice total negative; a credit memo is required instead (issue #1694; split out of the former blanket `IllegalArgumentException` 400 catch-all) |

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
| `USER_NOT_FOUND` | 404 | A referenced user does not resolve — on every entry point that references one by id or username (user management and token issuance alike; ADR-0017 §2 "one condition, one status", #1802). The token-issuance endpoints answer it with a generic message that never names the subject (#1715). A refresh token whose user no longer exists is `401 INVALID_REFRESH_TOKEN` instead, because there the missing user is a credential failure |
| `INVALID_TOKEN` | 401 | `GET /v1/auth/roles`, `/subject`, `/user-id`: the `token` query parameter failed validation (expired, revoked, unknown to the token store, malformed); enveloped since #1808 — previously a bare 401 |
| `TOKEN_USER_ID_MISSING` | 422 | `GET /v1/auth/user-id`: the token passed full validation but carries neither a `uid` nor a legacy `userId` claim (#1803). Not 401 — the token is genuine — and not 400 — it parsed; ADR-0017 §2 question 3 |
| `DUPLICATE_ROLE_NAME` | 409 | Role name is already taken |
| `ACCOUNT_LOCKED` | 401 | Account is locked due to repeated failures |
| `ACCOUNT_DISABLED` | 401 | Account has been disabled by an administrator |
| `BAD_CREDENTIALS` | 401 | Username or password is incorrect |
| `FORBIDDEN` | 403 | Caller lacks required permissions |
| `USER_HAS_NO_ROLES` | 403 | Credentials or refresh token are valid, but the account currently has no roles assigned; answered the same on login and refresh (ADR-0017 §2 question 1, #1725). `nextAction` tells the caller to have an administrator assign a role |
| `VALIDATION_ERROR` | 400 | This module's own field/reference validation failure (`SecurityValidationException`): a blank required field, a malformed permission key or bitset, an unsupported `perm_ver`. A role or user reference that does not resolve is `ROLE_NOT_FOUND` / `USER_NOT_FOUND` (404) since #1802. Aligned onto the fleet-wide spelling in #1730; it answered `INVALID_REQUEST` between #1694 and #1730 |
| `INVALID_REQUEST` | 400 | Request-binding failure raised by the framework before the controller runs — an unreadable body, a missing query parameter, a bean-validation rejection. A pre-existing code with consumers, so #1730 deliberately did **not** rename it. Clients that switch on validation codes should handle both this and `VALIDATION_ERROR` |

### pos-accounting
| Code | Status | Description |
|------|--------|-------------|
| `DUPLICATE_EVENT` | 409 | Event with this ID has already been processed |
| `UNBALANCED_ENTRY` | 422 | Journal entry debits and credits do not balance (or has no lines) |
| `GL_POSTING_FAILED` | 409 | General ledger posting failed |
| `DUPLICATE_ACCOUNT_CODE` | 409 | Chart of accounts code already exists |
| `GL_ACCOUNT_NOT_FOUND` | 404 | Referenced GL account does not exist |
| `GL_ACCOUNT_NOT_ACTIVE` | 422 | GL account is not active on the transaction date, or was never activated |
| `GL_MAPPING_NOT_CONFIGURED` | 422 | No GL mapping (posting category/key/effective date) is configured for the request |
| `ACCOUNT_NOT_ZERO_BALANCE` | 409 | GL account cannot be deactivated because its posted balance is not zero |
| `ACCOUNT_NOT_INACTIVE` | 409 | GL account cannot be archived because it is not currently INACTIVE |
| `NO_MATCHING_VENDOR_BILL` | 400 | An inbound vendor invoice matched no pending receipt/bill for the vendor (a failed match, not a missing addressed resource) |
| `JOURNAL_ENTRY_NOT_FOUND` | 404 | Referenced journal entry does not exist |
| `DEFAULT_GL_MAPPING_NOT_FOUND` | 404 | Referenced default GL mapping does not exist |
| `POSTING_RULE_SET_NOT_FOUND` | 404 | Referenced posting rule set does not exist |

### pos-catalog
| Code | Status | Description |
|------|--------|-------------|
| `NOT_FOUND` | 404 | Catalog item not found |
| `FORBIDDEN` | 403 | Operation not permitted on this catalog entry |
| `VALIDATION_ERROR` | 400 | Catalog data validation failed |
| `BUSINESS_RULE_VIOLATION` | 409 | Catalog business rule was violated |
| `CONFLICT` | 409 | Concurrent update detected; retry required |

### pos-workorder
| Code | Status | Description |
|------|--------|-------------|
| `NOT_FOUND` | 404 | Workorder does not exist (also used generically by a few older endpoints) |
| `ESTIMATE_NOT_FOUND` | 404 | Estimate does not exist |
| `ESTIMATE_ITEM_NOT_FOUND` | 404 | Line item does not exist on the named estimate |
| `CHANGE_REQUEST_NOT_FOUND` | 404 | Change request does not exist |
| `SERVICE_LINE_NOT_FOUND` | 404 | Workorder service line does not exist, or does not belong to the named workorder |
| `PART_NOT_FOUND` | 404 | Workorder part line does not exist, or does not belong to the named workorder |
| `APPROVAL_CONFIGURATION_NOT_FOUND` | 404 | Approval configuration does not exist |
| `WORK_SESSION_NOT_FOUND` | 404 | Work session does not exist |
| `BREAK_SEGMENT_NOT_FOUND` | 404 | Break segment does not exist |
| `TRAVEL_SEGMENT_NOT_FOUND` | 404 | Travel segment does not exist |
| `SUBSTITUTE_LINK_NOT_FOUND` | 404 | Part-substitution link does not exist |
| `INVALID_ARGUMENT` | 400 | Field-level or request-shape validation failure (`WorkorderRequestValidationException`) |
| `VALIDATION_FAILED` | 400 | Bean-validation failure, with `fieldErrors` |
| `CONFLICT` | 409 | Generic stateful collision: invalid lifecycle transition, or a caller-supplied id that does not match the resource it targets, or an operation that would exceed a quantity the resource's current state actually has available (`WorkorderResourceConflictException`, `IllegalStateException`) |
| `TRAVEL_SEGMENT_CONFLICT` | 409 | An active travel segment already exists for the assignment |
| `DUPLICATE_SUBSTITUTE_LINK` | 409 | A substitute-part link already exists for this pair |
| `STALE_SUBSTITUTE_LINK_VERSION` | 409 | Optimistic-lock version mismatch on a substitute link |
| `CUSTOMER_APPROVAL_INVALID` | 409 | Workorder claims an approval its own state does not back |
| `INSUFFICIENT_PART_AVAILABILITY` | 409 | Requested part quantity exceeds current owned stock (guided, with `nextAction`) |
| `PURCHASE_ORDER_REQUIRED` | 422 | Commercial customer's billing rules require a purchase order that was not supplied |
| `ESTIMATE_INCOMPLETE` | 422 | A DRAFT estimate was submitted for approval with no customer, no vehicle, no line items, or uncalculated totals (`EstimateIncompleteException`) |
| `FRACTIONAL_QUANTITY_NOT_ALLOWED` | 422 | Quantity is not a whole number for a product the catalog declares indivisible |
| `UOM_CONVERSION_UNDEFINED` | 422 | `uomCode` names no conversion row for the referenced product |
| `PROMOTION_IDEMPOTENCY_INCONSISTENT` | 500 | A recorded promotion idempotency key resolves to no workorder (server defect, correlated) |
| Dynamic `PromotionErrorCode` values | 404/409 | Estimate-to-workorder promotion precondition failures; see `PromotionValidationException` |
| Dynamic `CustomerRequirementsNotMetException` codes | 409/503 | Customer-requirements verdict blocked workorder creation; see that exception |
| `FORBIDDEN` | 403 | Caller lacks required workorder permissions |

### pos-vehicle-inventory
| Code | Status | Description |
|------|--------|-------------|
| `VALIDATION_FAILED` | 400 | Bean-validation failure (with `fieldErrors`), or Jakarta constraint violation on a path/query parameter |
| `VALIDATION_ERROR` | 400 | Field-level or request-shape validation failure (`VehicleValidationException`) (issue #1694; split out of the former blanket `IllegalArgumentException` 400 catch-all, code unchanged) |
| `RESOURCE_NOT_FOUND` | 404 | Vehicle or care-preference document not found |
| `VEHICLE_VIN_CONFLICT` | 409 | An active vehicle already holds the requested VIN — a stateful collision (issue #1694; split out of the former blanket `IllegalArgumentException` 400 catch-all, which had reported this same case as 400) |

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
    // pos-security-service raises INVALID_REQUEST for framework-level request-binding
    // failures; it carries no fieldErrors, so displayFieldErrors falls back to an empty list.
    case 'INVALID_REQUEST':
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
