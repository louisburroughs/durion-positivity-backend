# POS Accounting Module

## Overview

The POS Accounting module implements a comprehensive audit trail system for tracking financial exceptions including price overrides, refunds, and cancellations. This ensures full compliance with financial controls and provides evidence for exception reviews.

## Features

### 1. Price Override Tracking
- **Authorization Validation**: Role-based thresholds for price discounts
- **Forbidden Category Detection**: Blocks overrides below cost, stacking violations, etc.
- **Immutable Audit Entries**: Records original/adjusted prices, actor, authorization level, policy version
- **Event Emission**: `OverridePriceCreated` with accounting intent

### 2. Refund Tracking
- **Separate Authorization**: Independent from original sale authorization
- **Automatic Method Selection**: 
  - Unsettled payments → REVERSAL (void/chargeback)
  - Settled payments → CREDIT_MEMO or REFUND_PAYMENT
- **Full Traceability**: Links to invoice, payment, settlement status
- **Event Emission**: `RefundCreated` with refund type and accounting intent

### 3. Cancellation Tracking
- **Before/After Snapshots**: Captures pre and post-cancellation state
- **Partial Payment Handling**: Records payments without netting
- **GL Reversal Status**: Tracks pending reversals (Accounting owns posting)
- **Event Emission**: `CancellationCreated` with snapshots

## Architecture

### Core Components

#### Entities
- **AuditTrailEntry**: Main immutable audit log entry
- **OverridePolicyThreshold**: Role-based authorization limits
- **RefundPolicyConfig**: Refund handling configuration

#### Services
- **AuditTrailService**: Records price overrides, refunds, and cancellations
- **AuditTrailQueryService**: Query and export audit entries
- **PriceOverrideAuthorizationService**: Validates override authorization
- **RefundAuthorizationService**: Validates refund authorization
- **DataInitializationService**: Seeds default policies on startup

#### Controllers
- **AuditTrailController**: REST API for recording and querying audit entries

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

{
  "orderId": "uuid",
  "cancellationType": "ORDER_CANCELLED",
  "beforeSnapshot": "{\"total\": 100.00, \"status\": \"CONFIRMED\"}",
  "afterSnapshot": "{\"total\": 0.00, \"status\": \"CANCELLED\"}",
  "actorId": "uuid",
  "actorRole": "SERVICE_WRITER",
  "reason": "Customer request"
}
```

#### Query by Order
```http
GET /api/audit/order/{orderId}
```

#### Query by Date Range
```http
GET /api/audit/range?startDate=2024-01-01T00:00:00Z&endDate=2024-12-31T23:59:59Z
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

## Database Schema

### audit_trail_entry
- audit_id (UUID, PK)
- exception_type (ENUM)
- actor_id, actor_role, timestamp, reason
- authorization_level, policy_version
- Price override fields: order_id, line_item_id, original_price, adjusted_price
- Refund fields: invoice_id, payment_id, refund_type, refund_amount, refund_method
- Cancellation fields: cancellation_type, before_snapshot, after_snapshot
- Accounting fields: accounting_intent, accounting_status, expected_outcome

### override_policy_threshold
- policy_id (UUID, PK)
- role, max_absolute_amount, max_percent_off
- effective_date, expiration_date, version, active

### refund_policy_config
- config_id (UUID, PK)
- requires_separate_authorization, settled_payment_handling, unsettled_payment_handling
- version, active

## Events

### OverridePriceCreated
- Emitted when price override recorded
- Includes accounting intent: REVENUE_ADJUSTMENT
- Status: PENDING_POSTING

### RefundCreated
- Emitted when refund recorded
- Includes refund type and accounting intent
- Status: PENDING_POSTING

### CancellationCreated
- Emitted when cancellation recorded
- Includes before/after snapshots
- Status: PENDING_POSTING

### AuthorizationDenied
- Emitted when authorization fails
- Includes denial reason and policy version

## Running the Application

### Prerequisites
- Java 21
- Maven 3.9+
- H2 (embedded) or PostgreSQL

### Build
```bash
mvn clean compile -pl pos-accounting -am
```

### Run
```bash
mvn spring-boot:run -pl pos-accounting
```

### Access
- Application: http://localhost:8084
- H2 Console: http://localhost:8084/h2-console
- Health Check: http://localhost:8084/actuator/health

## Testing

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
