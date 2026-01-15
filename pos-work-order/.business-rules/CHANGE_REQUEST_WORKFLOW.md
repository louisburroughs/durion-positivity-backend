# Change Request Workflow Implementation

This document describes the implementation of the additional work request and approval workflow for managing out-of-scope work identified during work order execution.

## Overview

The change request workflow enables Technicians to request additional work beyond the originally authorized scope, with proper approval gates and traceability. This ensures customer approval is captured, billing is controlled, and an auditable trace of scope changes exists.

## Key Features

### 1. Change Request Lifecycle Management

**States:**
- `AWAITING_ADVISOR_REVIEW`: Initial state for new change requests
- `APPROVED`: Service Advisor has approved the additional work
- `DECLINED`: Service Advisor has declined the additional work
- `CANCELLED`: Request was cancelled before decision

**State Transitions:**
- AwaitingAdvisorReview → Approved (via `approveChangeRequest()`)
- AwaitingAdvisorReview → Declined (via `declineChangeRequest()`)
- AwaitingAdvisorReview → Cancelled (via system action)

### 2. Work Order Item Status Management

Work order items (services and parts) associated with change requests have statuses:
- `PENDING_APPROVAL`: Items awaiting advisor decision (blocked from execution)
- `READY_TO_EXECUTE`: Items approved and ready for work
- `OPEN`: Items that can be worked on
- `IN_PROGRESS`: Items currently being worked on
- `COMPLETED`: Items that are complete
- `CANCELLED`: Items that were declined (not billable)

### 3. Emergency/Safety Exception Handling

Special handling for items flagged as emergency/safety (vehicle unsafe to start/operate):
- Requires photo evidence + notes OR explicit "photo not possible" + notes
- Any Technician may flag emergency/safety items
- If declined, requires customer denial acknowledgment before work order closure
- System blocks work order closure until all declined emergency items are acknowledged

### 4. Approval Artifact

Service Advisor notes serve as the approval artifact for all decisions:
- Required for both approval and decline actions
- Provides auditable record of decision and reasoning
- Stored directly on the ChangeRequest entity

### 5. Blocking and Execution Control

Items marked `PENDING_APPROVAL` are blocked from:
- Execution by Technicians
- Inventory consumption
- Billing

Exception: Emergency/Safety items may proceed with documented customer acknowledgment of denial

## Data Model

### ChangeRequest Entity

```java
@Entity
public class ChangeRequest {
    private Long id;
    private Long workOrderId;
    private Long requestedByUserId;
    private LocalDateTime requestedAt;
    private ChangeRequestStatus status;
    private String description; // Required
    private Boolean isEmergencyException;
    private String exceptionReason;
    private String approvalNote; // Approval artifact
    private Long supplementalEstimatePdfId;
    private LocalDateTime approvedAt;
    private Long approvedBy;
    private LocalDateTime declinedAt;
}
```

### WorkOrder Updates

```java
@Entity
public class WorkOrder {
    private WorkOrderStatus status; // Added: IN_PROGRESS, etc.
    // ... existing fields
}
```

### WorkOrderService & WorkOrderPart Updates

```java
@Entity
public class WorkOrderService {
    private WorkOrderItemStatus status; // Added
    private Long changeRequestId; // Reference to ChangeRequest
    
    // Emergency/Safety fields
    private Boolean isEmergencySafety;
    private String photoEvidenceUrl;
    private String emergencyNotes;
    private Boolean photoNotPossible;
    private Boolean customerDenialAcknowledged;
    
    // ... existing fields
}

@Entity
public class WorkOrderPart {
    private WorkOrderItemStatus status; // Added
    private Long changeRequestId; // Reference to ChangeRequest
    
    // Emergency/Safety fields (same as WorkOrderService)
    // ... existing fields
}
```

## REST API Endpoints

### Change Request Management

- `POST /api/work-orders/{workOrderId}/change-requests` - Create new change request
- `POST /api/change-requests/{id}/approve` - Approve change request
- `POST /api/change-requests/{id}/decline` - Decline change request
- `POST /api/change-requests/{id}/acknowledge-denial` - Record customer denial acknowledgment for emergency items
- `GET /api/change-requests/{id}` - Get change request by ID
- `GET /api/work-orders/{workOrderId}/change-requests` - List all change requests for a work order
- `GET /api/work-orders/{workOrderId}/can-close` - Check if work order can be closed

## Business Logic

### Create Change Request

```java
POST /api/work-orders/{workOrderId}/change-requests
{
    "requestedByUserId": 123,
    "description": "Customer reports grinding noise from brakes",
    "isEmergencyException": true,
    "exceptionReason": "Brake pads worn to metal, vehicle unsafe to drive",
    "services": [
        {
            "serviceEntityId": 456,
            "isEmergencySafety": true,
            "photoEvidenceUrl": "https://example.com/photo.jpg",
            "emergencyNotes": "Metal-on-metal contact visible, immediate safety concern"
        }
    ],
    "parts": [
        {
            "productEntityId": 789,
            "quantity": 1,
            "isEmergencySafety": true,
            "photoEvidenceUrl": "https://example.com/photo.jpg"
        }
    ]
}
```

**Validation:**
1. Work order must exist and be in `IN_PROGRESS` status
2. Description is required
3. At least one service or part item is required
4. Emergency items require photo evidence OR "photo not possible" + notes
5. If emergency exception flagged, at least one item must be marked emergency/safety

**Process:**
1. Create ChangeRequest with status `AWAITING_ADVISOR_REVIEW`
2. Create associated WorkOrderService and WorkOrderPart entries
3. Set all item statuses to `PENDING_APPROVAL`
4. Link items to ChangeRequest via `changeRequestId`
5. Generate supplemental PDF estimate (TODO)
6. Return created ChangeRequest

### Approve Change Request

```java
POST /api/change-requests/{id}/approve
{
    "approvedBy": 789,
    "approvalNote": "Spoke with customer John Smith, approved brake service. Customer authorized via phone on 2026-01-09."
}
```

**Validation:**
1. ChangeRequest must be in `AWAITING_ADVISOR_REVIEW` status
2. Approval note is required (serves as approval artifact)

**Process:**
1. Set ChangeRequest status to `APPROVED`
2. Record `approvedAt` timestamp and `approvedBy` user
3. Store `approvalNote` as approval artifact
4. Move all associated items from `PENDING_APPROVAL` to `READY_TO_EXECUTE`
5. Items can now be executed and consume inventory

### Decline Change Request

```java
POST /api/change-requests/{id}/decline
{
    "approvalNote": "Customer declined additional brake work. Advised of safety concerns, customer chose to proceed without repair. Documented 2026-01-09."
}
```

**Validation:**
1. ChangeRequest must be in `AWAITING_ADVISOR_REVIEW` status
2. Approval note is required (serves as decline artifact)

**Process:**
1. Set ChangeRequest status to `DECLINED`
2. Record `declinedAt` timestamp
3. Store `approvalNote` as decline artifact
4. Move all associated items from `PENDING_APPROVAL` to `CANCELLED`
5. Items are not billable and cannot be executed

### Record Customer Denial Acknowledgment

```java
POST /api/change-requests/{id}/acknowledge-denial
```

**Validation:**
1. ChangeRequest must be flagged as emergency exception
2. ChangeRequest must be in `DECLINED` status

**Process:**
1. Find all emergency/safety items linked to this ChangeRequest
2. Mark each with `customerDenialAcknowledged = true`
3. Work order can now be closed (if all other requirements met)

### Check if Work Order Can Close

```java
GET /api/work-orders/{workOrderId}/can-close
```

**Returns:** `true` if work order can be closed, `false` otherwise

**Logic:**
1. Find all declined emergency ChangeRequests for the work order
2. For each, check if all emergency/safety items are acknowledged
3. If any emergency items lack acknowledgment, return `false`
4. Otherwise, return `true`

## Workflow Examples

### Example 1: Normal Additional Work Flow

1. **Technician identifies additional work needed:**
   - Work Order 123 is IN_PROGRESS
   - Technician notices worn brake pads during oil change

2. **Technician creates change request:**
   ```
   POST /api/work-orders/123/change-requests
   {
       "requestedByUserId": 456,
       "description": "Brake pads worn, need replacement",
       "services": [{"serviceEntityId": 789}],
       "parts": [{"productEntityId": 101, "quantity": 1}]
   }
   ```
   - System creates ChangeRequest with status AWAITING_ADVISOR_REVIEW
   - Items marked PENDING_APPROVAL (blocked from execution)

3. **Service Advisor reviews and contacts customer:**
   - Customer approves the work

4. **Service Advisor approves:**
   ```
   POST /api/change-requests/456/approve
   {
       "approvedBy": 789,
       "approvalNote": "Customer John Smith approved via phone. Cost $250."
   }
   ```
   - System moves items to READY_TO_EXECUTE
   - Technician can now perform the work

5. **Technician completes work:**
   - Items are executed and parts consumed
   - Work appears on final invoice

### Example 2: Emergency/Safety Work Declined

1. **Technician identifies emergency issue:**
   - During inspection, notices brake pads worn to metal

2. **Technician creates emergency change request:**
   ```
   POST /api/work-orders/123/change-requests
   {
       "requestedByUserId": 456,
       "description": "SAFETY: Brake pads worn to metal",
       "isEmergencyException": true,
       "exceptionReason": "Vehicle unsafe to drive",
       "services": [{
           "serviceEntityId": 789,
           "isEmergencySafety": true,
           "photoEvidenceUrl": "https://example.com/brake-photo.jpg",
           "emergencyNotes": "Metal-on-metal contact, grinding noise"
       }]
   }
   ```

3. **Service Advisor contacts customer:**
   - Customer declines the work

4. **Service Advisor declines:**
   ```
   POST /api/change-requests/456/decline
   {
       "approvalNote": "Customer declined brake work. Advised of safety risk. Customer acknowledged vehicle unsafe and chose to have it towed."
   }
   ```
   - Items moved to CANCELLED

5. **Service Advisor records customer acknowledgment:**
   ```
   POST /api/change-requests/456/acknowledge-denial
   ```
   - System marks emergency items as acknowledged

6. **Attempt to close work order:**
   ```
   GET /api/work-orders/123/can-close
   => Returns true
   ```
   - System allows closure because emergency denial is acknowledged

## Validation Rules

### Change Request Creation
- Work order must exist
- Work order must be in `IN_PROGRESS` status
- Description is required (not null, not empty)
- At least one service or part item is required
- If emergency exception flagged:
  - At least one item must be marked as emergency/safety
  - Each emergency item must have photo evidence OR "photo not possible" + notes

### Approval
- Change request must be in `AWAITING_ADVISOR_REVIEW` status
- Approval note is required (serves as approval artifact)
- User ID must be provided

### Decline
- Change request must be in `AWAITING_ADVISOR_REVIEW` status
- Approval note is required (serves as decline artifact)

### Emergency Acknowledgment
- Change request must be flagged as emergency exception
- Change request must be in `DECLINED` status

### Work Order Closure
- All declined emergency change requests must have customer denial acknowledgment
- System blocks closure if any emergency items lack acknowledgment

## Integration Points

### pos-customer
- Validates customer existence via `customerId`
- Customer approval workflow remains separate from change requests

### pos-catalog
- References ServiceEntity and ProductEntity via IDs
- No direct dependency on catalog module

### pos-invoice
- Approved items (READY_TO_EXECUTE, COMPLETED) are billable
- Cancelled items (DECLINED) are not billable
- Change request ID provides traceability on invoices

### pos-inventory
- Items in PENDING_APPROVAL status cannot consume inventory
- Items in READY_TO_EXECUTE or later can consume inventory
- Inventory service should check item status before allocation

## Future Enhancements

1. **Supplemental PDF Estimate Generation**: Implement PDF service to generate estimates for change request items
2. **Event Emission**: Add @EmitEvent annotations for audit trail events
3. **Notification Service**: Notify Service Advisors when change requests are created
4. **Mobile App Integration**: Allow customers to approve/decline via mobile app
5. **Analytics Dashboard**: Track approval rates, average additional work amounts, common items
6. **Customer Portal**: Allow customers to view change request history
7. **Photo Upload Service**: Dedicated service for uploading and storing emergency photos
8. **Workflow Automation**: Auto-approve low-value items, auto-decline expired requests

## Security Considerations

- **Authentication**: All endpoints require authentication
- **Authorization**: 
  - Technicians can create change requests
  - Service Advisors can approve/decline
  - Proper role-based access control (RBAC) required
- **Data Validation**: All inputs validated before processing
- **Audit Trail**: All state changes logged with timestamp and user

## Testing Scenarios

1. **Create Change Request - Happy Path**
   - Work order in IN_PROGRESS
   - Valid description and items provided
   - Items marked PENDING_APPROVAL

2. **Create Change Request - Validation Failures**
   - Missing description
   - No items provided
   - Work order not in IN_PROGRESS
   - Emergency flagged but no emergency items

3. **Approve Change Request**
   - Items move to READY_TO_EXECUTE
   - Approval note stored

4. **Decline Change Request**
   - Items move to CANCELLED
   - Decline note stored

5. **Emergency Work - Acknowledge Denial**
   - Emergency items marked as acknowledged
   - Work order can be closed

6. **Emergency Work - Missing Acknowledgment**
   - Work order closure blocked
   - Error returned to user

7. **Photo Evidence Validation**
   - With photo URL: accepted
   - Without photo, without "photo not possible": rejected
   - Without photo, with "photo not possible" but no notes: rejected
   - Without photo, with "photo not possible" and notes: accepted

## Metrics and Observability

Track the following metrics:
- Count of change requests created per day/week/month
- Approval vs decline ratio
- Average time from request to decision
- Frequency of emergency/safety flags
- Customer denial acknowledgment compliance rate
- Common emergency items (for training and process improvement)

## Related Documentation

- Issue #156: [BACKEND] [STORY] Execution: Request Additional Work and Flag for Approval
- Issue #322: Clarification issue for story requirements
- `pos-work-order/CUSTOMER_APPROVAL_WORKFLOW.md`: Estimate approval workflow
- `pos-work-order/README.md`: Overall module documentation
- API Documentation: Available at `/swagger-ui.html` when running

## Contact

For questions or issues related to this implementation, please reference issue #156 or contact the workexec domain team.
