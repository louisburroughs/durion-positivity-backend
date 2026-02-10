# POS Accounting Module

## Overview

The POS Accounting module implements:

- An audit trail system for tracking financial exceptions (price overrides, refunds, cancellations)
- Invoice payment status tracking with idempotent processing (payment-applied events → denormalized invoice status view)

This supports financial controls, traceability, and downstream accounting posting workflows.

## Features

### 1. Price Override Tracking
- **Authorization Validation**: Role-based thresholds for price discounts
- **Forbidden Category Detection**: Blocks overrides below cost, stacking violations, etc.
- **Immutable Audit Entries**: Records original/adjusted prices, actor, authorization level, policy version
- **Event Publication**: Publishes `OverridePriceCreated` (Spring application event) with accounting intent/status

### 2. Refund Tracking
- **Separate Authorization**: Independent from original sale authorization
- **Automatic Method Selection**: 
  - Unsettled payments → VOID/CHARGEBACK (based on refund type)
  - Settled payments → CREDIT_MEMO/CASH_REFUND (based on refund type)
- **Full Traceability**: Links to invoice, payment, settlement status
- **Event Publication**: Publishes `RefundCreated` (Spring application event) with refund type, method, and accounting intent/status

### 3. Cancellation Tracking
- **Before/After Snapshots**: Captures pre and post-cancellation state
- **Partial Payment Handling**: Records payments without netting
- **GL Reversal Status**: Tracks pending reversals (Accounting owns posting)
- **Event Publication**: Publishes `CancellationCreated` (Spring application event) with snapshots and accounting intent/status

### 4. Invoice Payment Status Tracking
- **Idempotent Processing**: Uses an idempotency key to prevent duplicate application
- **Retry for Transient Failures**: Retries payment application up to 3 times
- **Denormalized Status View**: Stores current invoice payment status for fast reads
- **Event Logging**: The `payment.applied` endpoint is annotated with `@EmitEvent(id = "payment.applied")` (logs event start/end/error)

## Architecture

### Core Components

#### Entities
- **AuditTrailEntry** (`audit_trail_entry`): Main immutable audit log entry
- **OverridePolicyThreshold** (`override_policy_threshold`): Role-based authorization limits
- **RefundPolicyConfig** (`refund_policy_config`): Refund handling configuration
- **PaymentAppliedEvent** (`payment_applied_events`): Payment application events per invoice
- **InvoiceStatusView** (`invoice_status_views`): Denormalized view of current invoice payment status
- **IdempotencyKey** (`idempotency_keys`): Prevents duplicate processing of payment-applied requests

#### Services
- **AuditTrailService**: Records price overrides, refunds, and cancellations
- **AuditTrailQueryService**: Query and export audit entries
- **PriceOverrideAuthorizationService**: Validates override authorization
- **RefundAuthorizationService**: Validates refund authorization
- **DataInitializationService**: Seeds default policies on startup
- **InvoicePaymentStatusService**: Applies payments and maintains invoice status view
- **IdempotencyService**: Stores and checks idempotency keys (24h TTL)

#### Controllers
- **AuditTrailController**: REST API for recording and querying audit entries
- **InvoicePaymentController**: REST API for payment-applied events and invoice status

### API Endpoints

#### Record Price Override
```http
POST /api/audit/price-override
Content-Type: application/json

{
  "orderId": "uuid",
  "lineItemId": "uuid",
  "originalPrice": 100.00,
  "adjustedPrice": 90.00,
  "actorId": "uuid",
  "actorRole": "MANAGER",
  "reason": "Customer loyalty discount"
}
```

#### Record Refund
```http
POST /api/audit/refund
Content-Type: application/json

{
  "invoiceId": "uuid",
  "paymentId": "uuid",
  "refundType": "CREDIT_MEMO",
  "refundAmount": 50.00,
  "originalPaymentStatus": "SETTLED",
  "actorId": "uuid",
  "actorRole": "MANAGER",
  "reason": "Product return"
}
```

#### Record Cancellation
```http
POST /api/audit/cancellation
Content-Type: application/json

 
  "cancellationType": "ORDER_CANCELLED",
  "beforeSnapshot": "{\"total\": 100.00, \"status\": \"CONFIRMED\"}",
  "afterSnapshot": "{\"total\": 0.00, \"status\": \"CANCELLED\"}",
  "actorId": "uuid",
  "actorRole": "SERVICE_WRITER",
  "reason": "Customer request"
}
```

  "invoiceId": "uuid",
#### Query by Order
```http
GET /api/audit/order/{orderId}
  "partialPaymentInfo": "{\"payments\": []}",
```

#### Query by Invoice
```http
GET /api/audit/invoice/{invoiceId}
```

#### Query by Exception Type and Date Range
```http
GET /api/audit/type/{type}?startDate=2024-01-01T00:00:00Z&endDate=2024-12-31T23:59:59Z
```

#### Query by Actor and Date Range
```http
GET /api/audit/actor/{actorId}?startDate=2024-01-01T00:00:00Z&endDate=2024-12-31T23:59:59Z
```

#### Query by Date Range
```http
GET /api/audit/range?startDate=2024-01-01T00:00:00Z&endDate=2024-12-31T23:59:59Z
```

#### Apply Payment (Idempotent)
```http
POST /api/accounting/payment-applied
Content-Type: application/json

{
  "invoiceId": "INV-123",
  "transactionReference": "TXN-456",
  "paymentAmount": 50.00,
  "invoiceTotal": 100.00,
  "idempotencyKey": "INV-123:TXN-456",
  "paymentFailed": false
}
```

#### Get Invoice Payment Status
```http
GET /api/accounting/invoice/{invoiceId}/status
```

## Configuration

### Default Override Policies

On startup, the system initializes three default override policies:

- **SERVICE_WRITER**: Max $50 or 10% discount
- **MANAGER**: Max $500 or 25% discount
- **GLOBAL_ADMIN**: Max $10,000 or 100% discount

### Refund Policy

Default refund policy:
- Requires separate authorization: **true**
- Settled payment handling: **CREDIT_MEMO**
- Unsettled payment handling: **REVERSAL**

## Business Rules

1. **Immutability**: Audit entries cannot be deleted or modified
2. **Authorization Enforcement**: All exceptions validated before recording
3. **Completeness**: Every exception includes actor, timestamp, reason, authorization level
4. **Traceability**: All entries link back to originating order/invoice/payment
5. **Accounting Intent**: All events include accounting intent and status
6. **GL Posting Out of Scope**: Recording only; GL posting is Accounting's responsibility
7. **Idempotency for Payments**: Duplicate `payment-applied` requests with the same idempotency key return the existing invoice status

## Database Schema

### audit_trail_entry
- audit_id (UUID, PK)
- exception_type (ENUM)
- actor_id, actor_role, timestamp, reason
- authorization_level, policy_version
- Price override fields: order_id, line_item_id, original_price, adjusted_price
- Refund fields: invoice_id, payment_id, refund_type, refund_amount, refund_method
- Cancellation fields: cancellation_type, before_snapshot, after_snapshot
- Accounting fields: accounting_intent, accounting_status, expected_accounting_outcome

### override_policy_threshold
- policy_id (UUID, PK)
- role, max_absolute_amount, max_percent_off
- effective_date, expiration_date, version, active

### refund_policy_config
- config_id (UUID, PK)
- requires_separate_authorization, settled_payment_handling, unsettled_payment_handling
- version, active

### payment_applied_events
- id (BIGINT, PK)
- invoice_id, transaction_reference
- payment_amount, invoice_total
- status (PAID/PARTIALLY_PAID/UNPAID/FAILED)
- timestamp
- idempotency_key

### invoice_status_views
- id (BIGINT, PK)
- invoice_id (unique)
- current_status
- total_paid, invoice_total
- latest_transaction_reference
- last_updated

### idempotency_keys
- id (BIGINT, PK)
- key_value (unique, indexed)
- invoice_id
- created_at, expires_at

## Events

### OverridePriceCreated
- Published (Spring application event) when price override recorded
- Includes accounting intent: REVENUE_ADJUSTMENT
- Status: PENDING_POSTING

### RefundCreated
- Published (Spring application event) when refund recorded
- Includes refund type and accounting intent
- Status: PENDING_POSTING

### CancellationCreated
- Published (Spring application event) when cancellation recorded
- Includes before/after snapshots
- Status: PENDING_POSTING

### AuthorizationDenied
- Published (Spring application event) when authorization fails
- Includes denial reason and policy version

### payment.applied
- Logged via `@EmitEvent(id = "payment.applied")` on the `POST /api/accounting/payment-applied` endpoint

## Running the Application

### Prerequisites
- Java 21
- Maven 3.9+ (or use the included Maven wrapper)
- H2 (embedded) or PostgreSQL

### Build
```bash
./mvnw clean compile -pl pos-accounting -am
```

### Run
```bash
./mvnw spring-boot:run -pl pos-accounting
```

### Access
- Application: http://localhost:8084
- H2 Console: http://localhost:8084/h2-console
- Health Check: http://localhost:8084/actuator/health

## Developer Guidance

- **STRONG REFERENCE — Use `exemplars.md`:** The file `pos-accounting/exemplars.md` contains authoritative, curated code exemplars for controllers, services, repositories, entities, and tests. Follow the patterns and snippets there when adding or modifying code in this module. Treat `exemplars.md` as the canonical style and architectural guide for `pos-accounting`.
- Tests and examples in this README follow the patterns documented in `exemplars.md`.

## Testing Notes

- Unit tests can be run locally without the full integration stack:

```bash
./mvnw -pl pos-accounting test -DskipITs
```

- Integration/contract tests use Spring context and may require dependent services or specific test profiles. If a Spring context fails to load, run the individual test with more logging or run only unit tests as shown above.

## Testing

### Run unit tests

```bash
./mvnw test -pl pos-accounting
```

### Manual Testing with curl

#### Price Override (Approved)
```bash
curl -X POST http://localhost:8084/api/audit/price-override \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "550e8400-e29b-41d4-a716-446655440000",
    "lineItemId": "550e8400-e29b-41d4-a716-446655440001",
    "originalPrice": 100.00,
    "adjustedPrice": 95.00,
    "actorId": "550e8400-e29b-41d4-a716-446655440002",
    "actorRole": "SERVICE_WRITER",
    "reason": "Customer loyalty discount"
  }'
```

#### Price Override (Denied - Exceeds Threshold)
```bash
curl -X POST http://localhost:8084/api/audit/price-override \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "550e8400-e29b-41d4-a716-446655440000",
    "lineItemId": "550e8400-e29b-41d4-a716-446655440001",
    "originalPrice": 100.00,
    "adjustedPrice": 40.00,
    "actorId": "550e8400-e29b-41d4-a716-446655440002",
    "actorRole": "SERVICE_WRITER",
    "reason": "Large discount"
  }'
```

#### Query Audit Trail
```bash
curl -X GET http://localhost:8084/api/audit/order/550e8400-e29b-41d4-a716-446655440000
```

#### Apply Payment
```bash
curl -X POST http://localhost:8084/api/accounting/payment-applied \
  -H "Content-Type: application/json" \
  -d '{
    "invoiceId": "INV-123",
    "transactionReference": "TXN-456",
    "paymentAmount": 50.00,
    "invoiceTotal": 100.00,
    "idempotencyKey": "INV-123:TXN-456",
    "paymentFailed": false
  }'
```

## Security Considerations

- Authentication/authorization should be implemented at API Gateway level
- Audit trail entries are immutable - no DELETE or PUT operations allowed
- Actor IDs should be validated against identity management system
- All timestamps are server-generated to prevent tampering

## Future Enhancements

- Export to CSV/JSON for compliance reporting
- Advanced filtering and search capabilities
- Real-time dashboards for exception monitoring
- Integration with GL posting service
- Role-based access control for audit trail queries
