# Customer Approval Workflow Implementation

This document describes the implementation of the customer approval workflow based on the clarifications provided in issue #206.

## Overview

The implementation provides a complete backend system for managing estimate approvals with configurable approval methods and state management.

## Key Features

### 1. Estimate Lifecycle Management

**States:**
- `DRAFT`: Initial state for new estimates
- `APPROVED`: Customer has approved the estimate (can become a work order)
- `DECLINED`: Customer has declined the estimate
- `EXPIRED`: Declined estimate past the reopen window

**State Transitions:**
- Draft → Approved (via `approveEstimate()`)
- Draft → Declined (via `declineEstimate()`)
- Approved → Declined (via `declineEstimate()`)
- Declined → Draft (via `reopenEstimate()` within expiry period)

### 2. Configurable Approval Methods

Approval methods can be configured per location and/or customer:
- `CLICK_CONFIRM` (default): Service advisor clicks to confirm
- `SIGNATURE`: Customer signature on tablet
- `ELECTRONIC_SIGNATURE`: Electronic signature via email/SMS
- `VERBAL_CONFIRMATION`: Verbal confirmation recorded

### 3. Line Item Decline Support

- Individual services and parts can be marked as declined
- Declined items are excluded from the work order
- Editor can mark items before final approval

### 4. Configuration Priority

The system selects the most specific configuration:
1. Customer-specific configuration (highest priority)
2. Location-specific configuration
3. Default configuration (lowest priority)

### 5. Decline Expiry

- Declined estimates can be reopened within a configurable period (default: 30 days)
- Expiry period is set per configuration
- After expiry, estimates cannot be reopened

## Data Model

### Estimate Entity

```java
@Entity
public class Estimate {
    private Long id;
    private Long shopId;
    private Long vehicleId;
    private Long customerId;
    private EstimateStatus status; // DRAFT, APPROVED, DECLINED, EXPIRED
    private LocalDateTime createdAt;
    private LocalDateTime approvedAt;
    private LocalDateTime declinedAt;
    private LocalDateTime expiresAt;
    private Long approvalConfigurationId;
    private String declineReason;
    private Long approvedBy;
}
```

### ApprovalConfiguration Entity

```java
@Entity
public class ApprovalConfiguration {
    private Long id;
    private Long locationId; // null = applies to all locations
    private Long customerId; // null = applies to all customers
    private ApprovalMethod approvalMethod;
    private Integer declineExpiryDays; // default: 30
    private Boolean requireSignature;
    private Integer priority; // calculated from locationId/customerId
}
```

### WorkOrder Updates

```java
@Entity
public class WorkOrder {
    private Long estimateId; // Reference to approved estimate
    // ... existing fields
}
```

### WorkOrderService & WorkOrderPart Updates

```java
@Entity
public class WorkOrderService {
    private Boolean declined; // Flag for declined line items
    // ... existing fields
}

@Entity
public class WorkOrderPart {
    private Boolean declined; // Flag for declined parts
    // ... existing fields
}
```

## REST API Endpoints

### Estimate Management

- `GET /api/estimates` - List all estimates
- `GET /api/estimates/{id}` - Get estimate by ID
- `GET /api/estimates/customer/{customerId}` - List estimates by customer
- `GET /api/estimates/shop/{shopId}` - List estimates by shop
- `POST /api/estimates` - Create new estimate
- `POST /api/estimates/{id}/approve?approvedBy={userId}` - Approve estimate
- `POST /api/estimates/{id}/decline?reason={text}` - Decline estimate
- `POST /api/estimates/{id}/reopen` - Reopen declined estimate
- `DELETE /api/estimates/{id}` - Delete estimate

### Approval Configuration Management

- `GET /api/approval-configurations` - List all configurations
- `GET /api/approval-configurations/{id}` - Get configuration by ID
- `GET /api/approval-configurations/applicable?locationId={id}&customerId={id}` - Get applicable configuration
- `POST /api/approval-configurations` - Create configuration
- `PUT /api/approval-configurations/{id}` - Update configuration
- `DELETE /api/approval-configurations/{id}` - Delete configuration

## Business Logic

### Estimate Approval

```java
public Estimate approveEstimate(Long estimateId, Long approvedByUserId) {
    // 1. Load estimate
    // 2. Validate state (must be DRAFT)
    // 3. Set status to APPROVED
    // 4. Set approvedAt timestamp
    // 5. Set approvedBy user ID
    // 6. Save and return
}
```

### Estimate Decline

```java
public Estimate declineEstimate(Long estimateId, String reason) {
    // 1. Load estimate
    // 2. Validate state (must be DRAFT or APPROVED)
    // 3. Set status to DECLINED
    // 4. Set declinedAt timestamp
    // 5. Set declineReason
    // 6. Calculate and set expiresAt based on configuration
    // 7. Save and return
}
```

### Estimate Reopen

```java
public Estimate reopenEstimate(Long estimateId) {
    // 1. Load estimate
    // 2. Validate state (must be DECLINED)
    // 3. Check if within expiry period
    // 4. Set status to DRAFT
    // 5. Clear declined fields
    // 6. Save and return
}
```

### Work Order Creation

```java
public WorkOrder createWorkOrder(WorkOrder workOrder) {
    // 1. If estimateId provided:
    //    - Load estimate
    //    - Validate status is APPROVED
    //    - Proceed with creation
    // 2. Check customer requirements (legacy)
    // 3. Check approval (legacy)
    // 4. Save work order
}
```

## Configuration Examples

### Default Configuration

```json
{
  "locationId": null,
  "customerId": null,
  "approvalMethod": "CLICK_CONFIRM",
  "declineExpiryDays": 30,
  "requireSignature": false
}
```

### Location-Specific Configuration

```json
{
  "locationId": 1,
  "customerId": null,
  "approvalMethod": "SIGNATURE",
  "declineExpiryDays": 45,
  "requireSignature": true
}
```

### Customer-Specific Configuration

```json
{
  "locationId": 1,
  "customerId": 123,
  "approvalMethod": "ELECTRONIC_SIGNATURE",
  "declineExpiryDays": 60,
  "requireSignature": false
}
```

## Integration Points

### pos-customer
- Validates customer existence via `customerId`
- Uses REST API for customer requirements check (legacy)

### pos-location
- Validates location existence via `shopId`/`locationId`
- Uses location ID for configuration matching

### pos-work-order
- Work orders reference estimates via `estimateId`
- Validates estimate is approved before creating work order
- Inherits declined items from estimate

## Future Enhancements

1. **Signature Capture**: Implement signature capture API for tablet integration
2. **Email/SMS Notifications**: Send approval requests via email/SMS
3. **Audit Trail**: Log all state transitions with user and timestamp
4. **Reporting**: Generate reports on approval rates, decline reasons, etc.
5. **Expired Estimate Cleanup**: Automated job to mark expired declined estimates
6. **Work Order Decline Workflow**: Separate workflow for declining active work orders

## Testing Notes

### Build Requirements
- Java 21 or later
- Maven 3.8+
- Spring Boot 3.2.6

### Test Scenarios

1. **Estimate Approval Flow**
   - Create estimate (status: DRAFT)
   - Approve estimate (status: APPROVED)
   - Create work order from approved estimate

2. **Estimate Decline and Reopen**
   - Create estimate (status: DRAFT)
   - Decline estimate (status: DECLINED, expiresAt set)
   - Reopen within expiry period (status: DRAFT)
   - Attempt reopen after expiry (should fail)

3. **Configuration Priority**
   - Create default configuration
   - Create location-specific configuration
   - Create customer-specific configuration
   - Verify most specific configuration is used

4. **Line Item Decline**
   - Create estimate with multiple services/parts
   - Mark some items as declined
   - Approve estimate
   - Create work order (declined items excluded)

5. **State Validation**
   - Attempt to approve already approved estimate (should fail)
   - Attempt to decline without reason
   - Attempt to reopen non-declined estimate (should fail)

## Architecture Decisions

### State Machine in Entity
The state machine logic is embedded in the Estimate entity methods (`canApprove()`, `canDecline()`, `canReopen()`). This keeps business rules close to the data and makes them easily testable.

### Configuration Priority Calculation
Priority is auto-calculated on save based on presence of `locationId` and `customerId`. This ensures consistent ordering in queries.

### Timestamp Management
All state transitions record timestamps automatically, providing an audit trail of when changes occurred.

### Default Configuration
If no configuration exists, the system uses a default in-memory configuration. This ensures the system always has valid approval settings.

## Related Documentation

- Issue #206: [BACKEND] [STORY] Approval: Capture In-Person Customer Approval
- Issue #206 Clarification: This implementation
- `pos-work-order/README.md`: Overall module documentation
- API Documentation: Available at `/swagger-ui.html` when running

## Contact

For questions or issues related to this implementation, please reference issue #206 or contact the workexec domain team.
