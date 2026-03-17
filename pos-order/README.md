# POS Order Service - Price Override Feature

## Overview

The POS Order Service provides order management functionality with a focus on price override capabilities. This implementation allows Service Advisors to apply price discounts with appropriate approval workflows and comprehensive audit trails.

## Features

### Price Override Management

- **Apply Price Overrides**: Service Advisors can request price overrides on order line items
- **Automatic Approval**: Small discounts (≤10% or ≤$100) are automatically approved
- **Manager Approval**: Large discounts require manager approval before application
- **Reason Codes**: Structured reason codes for compliance and reporting
- **Audit Trail**: Complete tracking of who, what, when, and why for all overrides

### Security & Permissions

The service implements role-based access control with the following permissions:

- `PRICE_OVERRIDE_APPLY`: Apply/request price overrides (Service Advisors)
- `PRICE_OVERRIDE_APPROVE`: Approve pending overrides (Managers)
- `PRICE_OVERRIDE_REJECT`: Reject pending overrides (Managers)
- `PRICE_OVERRIDE_VIEW`: View override history and reports

### Business Rules

**Automatic Approval Thresholds:**
- Discount percentage ≤ 10%
- Discount amount ≤ $50

**Validation Rules:**
- Override price cannot exceed original price
- Override price must be non-negative
- Reason code is required
- Justification text is optional but recommended

## API Endpoints

### Apply Price Override

```http
POST /api/v1/orders/price-overrides
Authorization: Bearer {token}
Content-Type: application/json

{
  "orderId": "ORD-001",
  "orderLineId": "LINE-001",
  "productId": "PROD-001",
  "originalPrice": 100.00,
  "overridePrice": 85.00,
  "idempotencyKey": "override-req-001",
  "reasonCode": "CUSTOMER_LOYALTY",
  "justification": "Long-term customer retention"
}
```

**Idempotency behavior:**
- Reusing the same `idempotencyKey` with the same payload returns the original result.
- Reusing the same `idempotencyKey` with a different payload returns HTTP `409 Conflict` with code `ORDER_PRICE_OVERRIDE_IDEMPOTENCY_CONFLICT`.

**Response (Auto-approved):**
```json
{
  "overrideId": 1,
  "orderId": "ORD-001",
  "orderLineId": "LINE-001",
  "productId": "PROD-001",
  "originalPrice": 100.00,
  "overridePrice": 85.00,
  "discountAmount": 15.00,
  "discountPercentage": 15.0,
  "reasonCode": "CUSTOMER_LOYALTY",
  "justification": "Long-term customer retention",
  "status": "APPROVED",
  "requiresApproval": false,
  "requestedByUserId": "user123",
  "createdAt": "2026-01-13T01:00:00Z",
  "message": "Price override approved and ready to apply"
}
```

### Approve Price Override

```http
POST /api/v1/orders/price-overrides/{overrideId}/approve
Authorization: Bearer {token}
Content-Type: application/json

{
  "comments": "Approved for customer retention"
}
```

### Reject Price Override

```http
POST /api/v1/orders/price-overrides/{overrideId}/reject
Authorization: Bearer {token}
Content-Type: application/json

{
  "reason": "Discount too high for this scenario",
  "comments": "Please revise and resubmit"
}
```

### Get Pending Approvals

```http
GET /api/v1/orders/price-overrides/pending
Authorization: Bearer {token}
```

### Query Overrides

```http
GET /api/v1/orders/price-overrides?orderId={orderId}
GET /api/v1/orders/price-overrides?status={status}
GET /api/v1/orders/price-overrides?startDate={iso8601}&endDate={iso8601}
Authorization: Bearer {token}
```

## Reason Codes

- `CUSTOMER_LOYALTY`: Customer loyalty discount or retention offer
- `PRICE_MATCH`: Price matching with competitor
- `PROMOTIONAL_PRICING`: Promotional pricing not in system
- `PRICING_ERROR_CORRECTION`: Correction of pricing error
- `VOLUME_DISCOUNT`: Volume discount for bulk purchase
- `GOODWILL_ADJUSTMENT`: Goodwill adjustment for service recovery
- `MANAGER_DISCRETION`: Manager discretion override
- `OTHER`: Other reason (requires detailed justification)

## Override Status Lifecycle

1. **PENDING_APPROVAL**: Override requested, awaiting manager approval
2. **APPROVED**: Override approved (auto or manual)
3. **REJECTED**: Override rejected by manager
4. **APPLIED**: Override applied to order (future state)
5. **CANCELLED**: Override cancelled or reverted (future state)

## Database Schema

### price_override Table

| Column | Type | Description |
|--------|------|-------------|
| override_id | BIGINT | Primary key |
| order_id | VARCHAR | Order identifier |
| order_line_id | VARCHAR | Order line identifier |
| product_id | VARCHAR | Product identifier |
| original_price | DECIMAL(19,4) | Baseline price |
| override_price | DECIMAL(19,4) | Override price |
| reason_code | VARCHAR | Reason code |
| justification | VARCHAR(2000) | Optional justification |
| status | VARCHAR | Current status |
| requires_approval | BOOLEAN | Approval required flag |
| requested_by_user_id | VARCHAR | Requestor user ID |
| approved_by_user_id | VARCHAR | Approver user ID |
| rejected_by_user_id | VARCHAR | Rejecter user ID |
| rejection_reason | VARCHAR(1000) | Rejection reason |
| created_at | TIMESTAMP | Creation timestamp |
| updated_at | TIMESTAMP | Last update timestamp |
| approved_at | TIMESTAMP | Approval timestamp |
| rejected_at | TIMESTAMP | Rejection timestamp |
| applied_at | TIMESTAMP | Application timestamp |

### approval_record Table

| Column | Type | Description |
|--------|------|-------------|
| record_id | BIGINT | Primary key |
| price_override_id | BIGINT | Override reference |
| reviewer_user_id | VARCHAR | Reviewer user ID |
| reviewer_role | VARCHAR | Reviewer role |
| action | VARCHAR | APPROVED or REJECTED |
| comments | VARCHAR(2000) | Optional comments |
| action_timestamp | TIMESTAMP | Action timestamp |
| reviewer_ip_address | VARCHAR | IP address (audit) |

## Configuration

Application properties for development (H2 in-memory database):

```properties
spring.application.name=pos-order
server.port=8084

# Database Configuration (H2)
spring.datasource.url=jdbc:h2:mem:posorder
spring.jpa.hibernate.ddl-auto=create-drop

# Security
spring.security.user.name=admin
spring.security.user.password=admin
```

## Testing

The service includes comprehensive integration tests covering:

- Small discount auto-approval
- Large discount manual approval flow
- Approval/rejection workflows
- Business rule validation
- Query operations
- Audit trail creation

Run tests with:
```bash
mvn test -pl pos-order
```

## Deployment

Build the service:
```bash
mvn clean package -pl pos-order -am
```

Run standalone:
```bash
java -jar pos-order/target/pos-order-0.0.1-SNAPSHOT.jar
```

## Future Enhancements

1. **Integration with Pricing Service**: Real-time baseline price retrieval
2. **Order Management Integration**: Automatic application of approved overrides
3. **Reporting Dashboard**: Override usage analytics and compliance reports
4. **Threshold Configuration**: Dynamic approval thresholds per role/region
5. **Multi-tier Approval**: Director approval for very large discounts
6. **Notification System**: Alert managers of pending approvals
7. **Bulk Operations**: Apply overrides to multiple line items
8. **Price Override Templates**: Pre-defined override scenarios

## Compliance & Audit

All price override operations are fully audited with:

- User identification (who)
- Timestamp tracking (when)
- Reason codes and justification (why)
- Original and override prices (what)
- Approval chain (by whom)
- IP address tracking for security audit

This audit trail supports:
- SOX compliance
- Internal control requirements
- Fraud detection
- Performance analysis
- Training and quality improvement

## Support

For questions or issues, contact the POS development team.
