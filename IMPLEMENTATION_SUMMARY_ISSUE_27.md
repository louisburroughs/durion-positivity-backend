# Implementation Summary: Cycle Count with Recount Capability

## Issue Reference
- **Origin Issue**: #27 - [BACKEND] [STORY] Counts: Execute Cycle Count and Record Variances
- **Clarification Issue**: Based on clarification answers provided by @louisburroughs

## Implementation Overview

This implementation delivers a complete cycle count system with recount capability, based on the clarification provided for issue #27. The solution ensures audit integrity, enforces business rules for recounts, and provides a clear investigation workflow for variance management.

## Key Design Decisions (Based on Clarification)

### 1. Recount Data Model
- **Immutable CountEntry records**: Each count or recount creates a NEW CountEntry (never updates existing ones)
- **Audit chain**: Recounts reference prior entries via `recountOfCountEntryId`
- **Sequence tracking**: `recountSequenceNumber` (0=original, 1=first recount, 2=second recount)
- **Full audit trail**: Preserves all counts for investigation and analysis

### 2. Permission Model
- **TRIGGER_RECOUNT_SELF**: Auditor can trigger ONE immediate recount (before final submission)
- **TRIGGER_RECOUNT_ANY**: Inventory Manager can trigger additional recounts
- **Validation**: System enforces that auditor can only recount their own tasks once

### 3. Recount Limits
- **Hard cap**: Maximum 3 total counts (original + 2 recounts)
- **Automatic escalation**: Exceeding limit marks task as `REQUIRES_INVESTIGATION`
- **Investigation workflow**: Requires manager sign-off and root-cause documentation

## Components Implemented

### Entities
1. **CycleCountTask** (`pos-inventory/src/main/java/com/positivity/inventory/entity/CycleCountTask.java`)
   - Tracks cycle count tasks assigned to auditors
   - Stores expected quantity (hidden during blind count)
   - References latest count entry
   - Maintains count entries counter for limit enforcement
   - Status: ASSIGNED → COUNTED_PENDING_REVIEW → REQUIRES_INVESTIGATION/APPROVED/REJECTED

2. **CountEntry** (`pos-inventory/src/main/java/com/positivity/inventory/entity/CountEntry.java`)
   - Immutable record of each count performed
   - Stores actual quantity, expected quantity, and variance
   - Links to prior count via `recountOfCountEntryId`
   - Tracks sequence number for ordering

3. **TaskStatus** (`pos-inventory/src/main/java/com/positivity/inventory/entity/TaskStatus.java`)
   - Enum defining workflow states
   - ASSIGNED, COUNTED_PENDING_REVIEW, REQUIRES_INVESTIGATION, APPROVED, REJECTED

### Repositories
1. **CycleCountTaskRepository** - CRUD operations for tasks
2. **CountEntryRepository** - CRUD operations for count entries, with queries for history

### Service Layer
1. **CycleCountService** (interface) - Defines cycle count operations
2. **CycleCountServiceImpl** (implementation)
   - `submitCount()`: Records initial count, calculates variance, updates task status
   - `submitRecount()`: Validates permissions, enforces limits, creates linked count entry
   - `getTask()`: Retrieves task details
   - `getCountHistory()`: Returns all counts for a task
   - `getTasksByAuditor()`: Lists tasks for an auditor

### REST Controller
**CycleCountController** (`pos-inventory/src/main/java/com/positivity/inventory/controller/CycleCountController.java`)
- Endpoints:
  - `POST /api/inventory/cycle-count/submit` - Submit initial count
  - `POST /api/inventory/cycle-count/recount` - Submit recount with permission validation
  - `GET /api/inventory/cycle-count/task/{taskId}` - Get task details
  - `GET /api/inventory/cycle-count/task/{taskId}/history` - Get count history
  - `GET /api/inventory/cycle-count/auditor/{auditorId}/tasks` - Get auditor's tasks
- Exception handlers for business rule violations

### DTOs
1. **SubmitCountRequest** - Request to submit initial count
2. **SubmitRecountRequest** - Request to submit recount (includes permission)
3. **CountResponse** - Response with variance and status information

### Exceptions
1. **RecountLimitExceededException** - Thrown when recount limit exceeded
2. **InsufficientPermissionException** - Thrown when user lacks required permission
3. **InvalidCountQuantityException** - Thrown for negative or invalid quantities
4. **TaskNotFoundException** - Thrown when task not found

### Configuration
- **application.yml**: H2 in-memory database, JPA configuration, Eureka client

## Business Rules Implemented

### Variance Calculation
- Formula: `variance = actual_quantity - expected_quantity`
- Positive variance = surplus (more stock than expected)
- Negative variance = shortage (less stock than expected)
- Zero variance = perfect count

### Recount Validation
1. **Quantity validation**: Must be non-negative integer
2. **Status validation**: Task must be in appropriate status
3. **Permission validation**:
   - TRIGGER_RECOUNT_SELF: Only for first recount, only by original auditor
   - TRIGGER_RECOUNT_ANY: Manager can trigger any recount
4. **Limit enforcement**: Maximum 3 total counts, automatic investigation trigger

### Investigation Workflow
- When limit exceeded:
  - Task status → REQUIRES_INVESTIGATION
  - RecountLimitExceededException thrown with details
  - Requires manual intervention (manager approval, root-cause analysis)

## Acceptance Criteria Coverage

### AC1: Count records a surplus
✅ Implemented - System calculates positive variance when actual > expected

### AC2: Count records a shortage
✅ Implemented - System calculates negative variance when actual < expected

### AC3: Perfect count is recorded
✅ Implemented - System calculates zero variance when actual = expected

### AC4: Invalid input is rejected
✅ Implemented - Validation exception thrown for negative quantities

### AC5: Recount creates a new entry
✅ Implemented - New CountEntry with sequence number and reference to prior entry

## Audit & Observability

### Audit Trail
- Every CountEntry is immutable and timestamped
- Full chain of recounts via `recountOfCountEntryId`
- Auditor ID recorded for each count
- Task status changes tracked

### Logging
- SLF4J logging at INFO and DEBUG levels
- Key operations logged: count submission, recount validation, limit exceeded
- Exception details logged for troubleshooting

### Future Work
- Event publishing for `InventoryVarianceDetected` (mentioned in original story)
- Integration with accounting system for financial reconciliation
- Metrics and monitoring dashboards

## API Usage Examples

### Submit Initial Count
```bash
POST /api/inventory/cycle-count/submit
{
  "taskId": "uuid-of-task",
  "auditorId": "auditor123",
  "actualQuantity": 102
}

Response:
{
  "countEntryId": "uuid-of-entry",
  "taskId": "uuid-of-task",
  "actualQuantity": 102,
  "expectedQuantity": 100,
  "variance": 2,
  "recountSequenceNumber": 0,
  "taskStatus": "COUNTED_PENDING_REVIEW",
  "countedAt": "2026-01-12T23:45:00",
  "limitExceeded": false,
  "message": "Count submitted successfully"
}
```

### Submit Recount (Auditor - First Recount)
```bash
POST /api/inventory/cycle-count/recount
{
  "taskId": "uuid-of-task",
  "auditorId": "auditor123",
  "actualQuantity": 101,
  "permission": "TRIGGER_RECOUNT_SELF"
}

Response:
{
  "countEntryId": "uuid-of-new-entry",
  "taskId": "uuid-of-task",
  "actualQuantity": 101,
  "expectedQuantity": 100,
  "variance": 1,
  "recountSequenceNumber": 1,
  "taskStatus": "COUNTED_PENDING_REVIEW",
  "countedAt": "2026-01-12T23:50:00",
  "limitExceeded": false,
  "message": "Recount submitted successfully"
}
```

### Submit Recount (Manager - Additional Recount)
```bash
POST /api/inventory/cycle-count/recount
{
  "taskId": "uuid-of-task",
  "auditorId": "manager456",
  "actualQuantity": 100,
  "permission": "TRIGGER_RECOUNT_ANY"
}

Response:
{
  "countEntryId": "uuid-of-final-entry",
  "taskId": "uuid-of-task",
  "actualQuantity": 100,
  "expectedQuantity": 100,
  "variance": 0,
  "recountSequenceNumber": 2,
  "taskStatus": "COUNTED_PENDING_REVIEW",
  "countedAt": "2026-01-12T23:55:00",
  "limitExceeded": true,
  "message": "Recount submitted. Maximum recount limit reached."
}
```

### Recount Limit Exceeded
```bash
POST /api/inventory/cycle-count/recount
{
  "taskId": "uuid-of-task",
  "auditorId": "manager456",
  "actualQuantity": 99,
  "permission": "TRIGGER_RECOUNT_ANY"
}

Response (HTTP 400):
{
  "error": "Recount limit exceeded for task uuid-of-task. Current count: 3, Maximum allowed: 3. Task requires investigation with manager approval.",
  "taskId": "uuid-of-task",
  "currentCount": 3,
  "maxAllowed": 3
}

Task status automatically changed to REQUIRES_INVESTIGATION
```

## Build and Deployment Notes

### Requirements
- Java 21 (as specified in `.sdkmanrc`)
- Spring Boot 3.2.6
- Maven 3.9+

### Build Command
```bash
./mvnw clean install -pl pos-inventory -am
```

### Running the Service
```bash
java -jar pos-inventory/target/pos-inventory-0.0.1-SNAPSHOT.jar
```

The service will start on port 8084 and register with Eureka at http://localhost:8761.

## Database Schema

### cycle_count_task
- task_id (UUID, PK)
- bin_location (VARCHAR)
- item_sku (VARCHAR)
- item_description (VARCHAR)
- expected_quantity (INTEGER)
- auditor_id (VARCHAR)
- status (VARCHAR - ENUM)
- latest_count_entry_id (UUID, FK)
- count_entries_count (INTEGER)
- created_at (TIMESTAMP)
- updated_at (TIMESTAMP)

### count_entry
- count_entry_id (UUID, PK)
- cycle_count_task_id (UUID, FK)
- auditor_id (VARCHAR)
- actual_quantity (INTEGER)
- expected_quantity (INTEGER)
- variance (INTEGER)
- recount_sequence_number (INTEGER)
- recount_of_count_entry_id (UUID, FK nullable)
- counted_at (TIMESTAMP)

## Security Considerations

### Permission-Based Access
- Permission strings: TRIGGER_RECOUNT_SELF, TRIGGER_RECOUNT_ANY
- Future integration with Spring Security for role-based access control
- Auditor can only recount their own tasks (once)

### Data Integrity
- Immutable count entries prevent tampering
- Audit trail preserved for compliance
- Variance calculation prevents manual manipulation

## Testing Strategy

### Unit Tests (Recommended)
- Service layer: Test variance calculation, permission validation, limit enforcement
- Repository layer: Test query methods, cascade operations
- Controller layer: Test request validation, exception handling

### Integration Tests (Recommended)
- End-to-end flow: Create task → Submit count → Submit recount → Verify limit
- Database transactions: Verify atomicity of count submission
- API contract tests: Verify request/response formats

### Manual Testing
- Use H2 console at http://localhost:8084/h2-console
- Test with Swagger UI at http://localhost:8084/swagger-ui.html
- Use Postman/cURL for API testing

## Related Stories and Future Work

### Immediate Follow-ups
1. Manager approval workflow for REQUIRES_INVESTIGATION tasks
2. Root-cause documentation interface
3. Variance threshold configuration

### Future Enhancements
1. Event publishing for inventory variance detected
2. Integration with accounting system
3. Variance reporting and analytics
4. Mobile app for auditors
5. Barcode scanning integration

## Questions for Product Owner

1. What should happen after a task is marked REQUIRES_INVESTIGATION?
2. Should there be variance thresholds that auto-trigger investigation?
3. What reports are needed for cycle count management?
4. Should we support batch count creation?
5. What audit reports are required for compliance?

## Conclusion

This implementation provides a solid foundation for cycle count management with strong audit controls and clear business rule enforcement. The design supports future enhancements while maintaining simplicity and clarity in the current scope.

All clarification questions from issue #27 have been addressed:
- ✅ Recounts create new, separate CountEntry records
- ✅ Permission-based recount triggering implemented
- ✅ Hard cap of 2 recounts (3 total counts) enforced
- ✅ Investigation workflow triggered when limits exceeded

The implementation is ready for testing and integration with the broader inventory management system.
