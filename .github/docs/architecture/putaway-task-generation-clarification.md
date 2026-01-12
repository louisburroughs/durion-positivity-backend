# Putaway Task Generation - Clarification Response

**Issue:** #32 - [BACKEND] [STORY] Putaway: Generate Put-away Tasks from Staging  
**Clarification Issue:** #230  
**Clarification Date:** 2026-01-12  
**Domain:** inventory  
**Type:** domain clarification

## Executive Summary

This document captures the clarification decisions for the Putaway Task Generation feature. These answers resolve ambiguities in the original story (#32) and establish the technical and business requirements for implementation. The decisions ensure **predictable putaway behavior**, **strong auditability**, and **efficient floor operations** without unnecessary complexity.

---

## Clarification Questions and Answers

### 1. Rule Precedence

**Question:** If multiple put-away rules could apply to a single product (e.g., a product-specific rule and a category-level rule), what is the definitive order of precedence?

**Answer:** Use a strict, most-specific-wins hierarchy.

**Decision:**

The system shall enforce the following precedence order (highest → lowest):

1. **Product-specific rule**
   - Explicit bin/zone assignment
   - Hazardous handling requirements
   - Temperature control specifications
   - Product-specific constraints

2. **Category-level rule**
   - Product category (e.g., Tires, Fluids, Batteries)
   - Category-specific storage requirements

3. **Supplier / Receipt-type rule**
   - Cross-dock vs stock
   - Vendor-managed inventory
   - Supplier-specific handling requirements

4. **Location default rule**
   - General storage policy for the warehouse
   - Default zone assignment

5. **System fallback**
   - "Any valid location" (last resort only)
   - Used when no other rules apply

**Enforcement Rules:**
- Higher-precedence rules **override** lower ones
- Conflicts at the same level (e.g., two category rules) are **configuration errors** and must be rejected at setup time
- The system must validate rule configurations at creation to prevent conflicts

**Implementation Notes:**
```java
public enum PutawayRulePrecedence {
    PRODUCT_SPECIFIC(1),
    CATEGORY_LEVEL(2),
    SUPPLIER_RECEIPT_TYPE(3),
    LOCATION_DEFAULT(4),
    SYSTEM_FALLBACK(5);
    
    private final int priority;
    
    PutawayRulePrecedence(int priority) {
        this.priority = priority;
    }
    
    public int getPriority() {
        return priority;
    }
}

@Entity
public class PutawayRule {
    @Id
    private Long id;
    
    @Enumerated(EnumType.STRING)
    private PutawayRulePrecedence precedence;
    
    private String productId; // null for non-product-specific rules
    private String categoryId; // null for non-category rules
    private String supplierId; // null for non-supplier rules
    private String locationId; // null for non-location-default rules
    
    private String suggestedDestinationLocationId;
    
    // Rule application logic
    public boolean appliesTo(ReceiptLineItem lineItem) {
        return switch (precedence) {
            case PRODUCT_SPECIFIC -> productId != null && productId.equals(lineItem.getProductId());
            case CATEGORY_LEVEL -> categoryId != null && categoryId.equals(lineItem.getProduct().getCategoryId());
            case SUPPLIER_RECEIPT_TYPE -> supplierId != null && supplierId.equals(lineItem.getReceipt().getSupplierId());
            case LOCATION_DEFAULT -> locationId != null && locationId.equals(lineItem.getReceipt().getWarehouseId());
            case SYSTEM_FALLBACK -> true;
        };
    }
}
```

---

### 2. Task Granularity

**Question:** Should a single receipt line item always result in a single put-away task? Or should the system group multiple line items heading to the same destination into one consolidated task?

**Answer:** **One receipt line item → one putaway task** by default.

**Decision:**

The system shall create one putaway task per receipt line item by default to preserve traceability.

**Rationale:**
- Preserves traceability between receipt, SKU, and movement
- Simplifies audit, reconciliation, and exception handling
- Clear one-to-one mapping for inventory tracking

**Optional Optimization (Controlled Consolidation):**

The system MAY allow **task consolidation** ONLY when **ALL** of the following conditions are true:

1. Same `productId`
2. Same `suggestedDestinationLocationId`
3. Same receipt/session
4. Same handling constraints (lot, expiry, serial rules)

**Consolidation Requirements:**
- Even when consolidated, maintain internal line references to original receipt lines
- Track each line item's contribution to the consolidated quantity
- Support partial completion of consolidated tasks

**Explicit Non-Behavior:**
- Do NOT merge different SKUs
- Do NOT merge items with different lot/expiry constraints
- Do NOT merge items with different handling requirements

**Implementation Notes:**
```java
@Entity
public class PutawayTask {
    @Id
    private Long taskId;
    
    @Enumerated(EnumType.STRING)
    private TaskStatus status; // UNASSIGNED, ASSIGNED, IN_PROGRESS, COMPLETED, CANCELLED
    
    private String productId;
    private String fromLocationId; // staging location
    private String suggestedDestinationLocationId;
    
    private Integer quantity;
    
    @OneToMany(mappedBy = "putawayTask", cascade = CascadeType.ALL)
    private List<PutawayTaskLineReference> lineReferences; // original receipt line items
    
    private LocalDateTime createdAt;
    private String createdBy;
    
    // Assignment fields
    private String assignedToUserId;
    private LocalDateTime assignedAt;
    
    // Completion tracking
    private LocalDateTime completedAt;
    private String actualDestinationLocationId;
}

@Entity
public class PutawayTaskLineReference {
    @Id
    private Long id;
    
    @ManyToOne
    private PutawayTask putawayTask;
    
    private Long receiptLineItemId;
    private Integer quantityFromThisLine;
}
```

---

### 3. Assignment Mechanism

**Question:** The original story states tasks are "assignable." What is the expected mechanism? Does a manager manually assign tasks, or can any authorized Stock Clerk claim an unassigned task from a shared pool?

**Answer:** **Shared pool with self-claim**, plus optional manager assignment.

**Decision:**

The system shall support a flexible assignment model:

**Default Behavior (Shared Pool):**
- Putaway tasks are created as **UNASSIGNED**
- Any user with permission `CLAIM_PUTAWAY_TASK` may:
  - Claim an unassigned task
  - Execute the task
  - Complete the task

**Manager Capabilities (Optional Assignment):**
- Users with permission `ASSIGN_PUTAWAY_TASK` may:
  - Pre-assign tasks to specific users
  - Reassign tasks (even if already claimed)
  - Override claims
  - Unassign tasks back to the pool

**Rationale:**
- Matches warehouse reality (work-stealing model)
- Avoids bottlenecks from mandatory manual assignment
- Still allows supervisory control when needed
- Maximizes floor efficiency

**State Transitions:**
```
UNASSIGNED → ASSIGNED (via manager assignment or self-claim)
ASSIGNED → IN_PROGRESS (when worker begins execution)
IN_PROGRESS → COMPLETED (when move is completed)
ASSIGNED → UNASSIGNED (when unassigned by manager)
Any State → CANCELLED (administrative action)
```

**Implementation Notes:**
```java
public enum TaskStatus {
    UNASSIGNED,      // Available for claim
    ASSIGNED,        // Claimed or pre-assigned
    IN_PROGRESS,     // Worker has begun execution
    COMPLETED,       // Successfully completed
    CANCELLED        // Administratively cancelled
}

// Permissions required
public interface PutawayTaskPermissions {
    String CLAIM_PUTAWAY_TASK = "CLAIM_PUTAWAY_TASK";
    String ASSIGN_PUTAWAY_TASK = "ASSIGN_PUTAWAY_TASK";
    String SELECT_PUTAWAY_LOCATION = "SELECT_PUTAWAY_LOCATION";
    String EXECUTE_PUTAWAY_TASK = "EXECUTE_PUTAWAY_TASK";
}

@Service
public class PutawayTaskAssignmentService {
    
    // Self-claim by stock clerk
    @PreAuthorize("hasAuthority('CLAIM_PUTAWAY_TASK')")
    public PutawayTask claimTask(Long taskId, String userId) {
        PutawayTask task = taskRepository.findById(taskId)
            .orElseThrow(() -> new TaskNotFoundException(taskId));
        
        if (task.getStatus() != TaskStatus.UNASSIGNED) {
            throw new TaskNotAvailableException("Task is not available for claim");
        }
        
        task.setStatus(TaskStatus.ASSIGNED);
        task.setAssignedToUserId(userId);
        task.setAssignedAt(LocalDateTime.now());
        
        return taskRepository.save(task);
    }
    
    // Manager assignment
    @PreAuthorize("hasAuthority('ASSIGN_PUTAWAY_TASK')")
    public PutawayTask assignTask(Long taskId, String userId) {
        PutawayTask task = taskRepository.findById(taskId)
            .orElseThrow(() -> new TaskNotFoundException(taskId));
        
        // Managers can assign regardless of current state (except COMPLETED)
        if (task.getStatus() == TaskStatus.COMPLETED) {
            throw new TaskAlreadyCompletedException("Cannot reassign completed task");
        }
        
        task.setStatus(TaskStatus.ASSIGNED);
        task.setAssignedToUserId(userId);
        task.setAssignedAt(LocalDateTime.now());
        
        return taskRepository.save(task);
    }
}
```

---

### 4. Exception Handling - Destination Unavailable

**Question:** What is the required system behavior if a rule's suggested destination is full or otherwise unavailable at the time of task generation? Should it look for the next-best location, or flag the task for manual intervention?

**Answer:** Use **automatic fallback at generation time**, with manual intervention only if no valid fallback exists.

**Decision:**

The system shall implement intelligent automatic fallback with explicit tracking:

**Required Behavior at Task Generation:**

If the rule-suggested destination is:
- **Full** (at or exceeding capacity), or
- **Temporarily unavailable** (locked, under maintenance), or
- **Fails validation** (incompatible with product constraints)

Then the system MUST:

1. **Attempt the next-best location** using:
   - Same rule precedence hierarchy
   - Same compatibility constraints (product type, handling requirements, etc.)
   - Same layout ranking logic (proximity, accessibility)

2. **Record fallback decision:**
   - `originalSuggestedLocationId` - the location suggested by the rule
   - `finalSuggestedLocationId` - the actual location assigned
   - `fallbackReason` - enum indicating why fallback was necessary

**Manual Intervention (Last Resort):**

The system shall flag for manual intervention ONLY if:
- **No valid location exists** that satisfies rules and constraints

In that case:
- Create task with status `REQUIRES_LOCATION_SELECTION`
- Require a user with `SELECT_PUTAWAY_LOCATION` permission to resolve
- Present list of potential locations with compatibility scores
- Allow manual override with justification

**Explicitly Disallowed:**
- Generating a task pointing to a known-invalid or full location
- Silent failure or null destinations
- Creating tasks without a valid destination

**Implementation Notes:**
```java
public enum FallbackReason {
    DESTINATION_FULL,
    DESTINATION_UNAVAILABLE,
    VALIDATION_FAILED,
    NO_CAPACITY,
    INCOMPATIBLE_CONSTRAINTS
}

@Entity
public class PutawayTask {
    // ... existing fields ...
    
    private String originalSuggestedLocationId;
    private String finalSuggestedLocationId;
    
    @Enumerated(EnumType.STRING)
    private FallbackReason fallbackReason; // null if no fallback needed
    
    // For tasks requiring manual intervention
    @Enumerated(EnumType.STRING)
    private TaskStatus status; // includes REQUIRES_LOCATION_SELECTION
}

@Service
public class PutawayTaskGenerationService {
    
    public PutawayTask generatePutawayTask(ReceiptLineItem lineItem) {
        // 1. Determine suggested location using rule precedence
        PutawayRule applicableRule = findApplicableRule(lineItem);
        String suggestedLocation = applicableRule.getSuggestedDestinationLocationId();
        
        // 2. Validate location
        LocationValidationResult validation = validateLocation(suggestedLocation, lineItem);
        
        PutawayTask task = new PutawayTask();
        task.setOriginalSuggestedLocationId(suggestedLocation);
        
        if (validation.isValid()) {
            // Location is valid, use it
            task.setFinalSuggestedLocationId(suggestedLocation);
            task.setStatus(TaskStatus.UNASSIGNED);
        } else {
            // Location invalid, attempt fallback
            LocationFallbackResult fallback = findNextBestLocation(lineItem, applicableRule);
            
            if (fallback.hasValidLocation()) {
                task.setFinalSuggestedLocationId(fallback.getLocationId());
                task.setFallbackReason(fallback.getReason());
                task.setStatus(TaskStatus.UNASSIGNED);
            } else {
                // No valid location found, require manual intervention
                task.setFinalSuggestedLocationId(null);
                task.setFallbackReason(FallbackReason.NO_CAPACITY);
                task.setStatus(TaskStatus.REQUIRES_LOCATION_SELECTION);
            }
        }
        
        return taskRepository.save(task);
    }
    
    private LocationFallbackResult findNextBestLocation(
            ReceiptLineItem lineItem, 
            PutawayRule originalRule) {
        
        // Apply same precedence and constraints
        List<Location> candidateLocations = locationRepository
            .findCompatibleLocations(
                lineItem.getProductId(),
                originalRule.getPrecedence(),
                true // available only
            );
        
        // Rank by proximity and accessibility
        return candidateLocations.stream()
            .map(loc -> scoreLocation(loc, lineItem))
            .max(Comparator.comparing(LocationScore::getScore))
            .map(score -> LocationFallbackResult.success(
                score.getLocation().getId(),
                determineFallbackReason(originalRule)
            ))
            .orElse(LocationFallbackResult.noLocationAvailable());
    }
}
```

---

## Summary of Decisions

### Rule Precedence
**Product → Category → Supplier/Receipt → Location default → System fallback**

### Task Granularity
**One receipt line = one task** (controlled consolidation allowed for identical products/destinations)

### Assignment Model
**Shared pool with self-claim**; managers may assign/reassign

### Exception Handling
**Auto-fallback to next-best location**; manual intervention only if no valid option exists

---

## Permission Model

The following permissions are required for the putaway task system:

| Permission | Description | Typical Roles |
|------------|-------------|---------------|
| `CLAIM_PUTAWAY_TASK` | Claim an unassigned task from the shared pool | Stock Clerk, Warehouse Associate |
| `ASSIGN_PUTAWAY_TASK` | Pre-assign or reassign tasks to specific users | Warehouse Manager, Supervisor |
| `SELECT_PUTAWAY_LOCATION` | Manually select location for tasks requiring intervention | Warehouse Manager, Inventory Controller |
| `EXECUTE_PUTAWAY_TASK` | Execute putaway task (move inventory) | Stock Clerk, Warehouse Associate |
| `CANCEL_PUTAWAY_TASK` | Cancel putaway tasks | Warehouse Manager, Supervisor |

---

## Data Model Summary

### Core Entities

1. **PutawayTask**
   - `taskId`: Primary key
   - `productId`: Product being moved
   - `fromLocationId`: Staging location
   - `originalSuggestedLocationId`: Initial suggestion from rule
   - `finalSuggestedLocationId`: Actual assigned destination
   - `fallbackReason`: Why fallback occurred (if applicable)
   - `status`: Task status (UNASSIGNED, ASSIGNED, IN_PROGRESS, COMPLETED, CANCELLED, REQUIRES_LOCATION_SELECTION)
   - `assignedToUserId`: User assigned to task
   - `quantity`: Quantity to move
   - `createdAt`, `assignedAt`, `completedAt`: Timestamps

2. **PutawayTaskLineReference**
   - `id`: Primary key
   - `putawayTaskId`: Foreign key to PutawayTask
   - `receiptLineItemId`: Foreign key to ReceiptLineItem
   - `quantityFromThisLine`: Quantity contributed by this line item

3. **PutawayRule**
   - `id`: Primary key
   - `precedence`: Rule precedence level (PRODUCT_SPECIFIC, CATEGORY_LEVEL, etc.)
   - `productId`, `categoryId`, `supplierId`, `locationId`: Rule scope
   - `suggestedDestinationLocationId`: Target location for this rule

### Enums

- `TaskStatus`: UNASSIGNED, ASSIGNED, IN_PROGRESS, COMPLETED, CANCELLED, REQUIRES_LOCATION_SELECTION
- `PutawayRulePrecedence`: PRODUCT_SPECIFIC, CATEGORY_LEVEL, SUPPLIER_RECEIPT_TYPE, LOCATION_DEFAULT, SYSTEM_FALLBACK
- `FallbackReason`: DESTINATION_FULL, DESTINATION_UNAVAILABLE, VALIDATION_FAILED, NO_CAPACITY, INCOMPATIBLE_CONSTRAINTS

---

## Testing Considerations

### Unit Tests
- Rule precedence evaluation with multiple applicable rules
- Task consolidation logic with various scenarios
- Fallback location selection algorithm
- Permission enforcement for claim/assign operations

### Integration Tests
- End-to-end task generation from receipt line items
- Task assignment and claim workflows
- Location validation and fallback scenarios
- Multi-user concurrent task claiming

### Property-Based Tests
- Rule precedence consistency across all product combinations
- Task granularity maintains inventory traceability
- Fallback logic never produces invalid destinations

---

## Implementation Priority

1. **Phase 1: Core Task Generation**
   - Rule precedence evaluation
   - Basic task creation (one line = one task)
   - Simple assignment model (shared pool)

2. **Phase 2: Intelligent Fallback**
   - Location validation
   - Automatic fallback logic
   - Manual intervention workflow

3. **Phase 3: Optimization**
   - Task consolidation (optional)
   - Manager assignment capabilities
   - Advanced location scoring

---

## References

- **Origin Story:** [Issue #32 - Putaway: Generate Put-away Tasks from Staging](https://github.com/louisburroughs/durion-positivity-backend/issues/32)
- **Clarification Issue:** [Issue #230 - Clarification Origin #32](https://github.com/louisburroughs/durion-positivity-backend/issues/230)
- **Domain Agent:** `.github/agents/domains/inventory-domain-agent.md`
- **Related Story:** [Issue #31 - Putaway: Execute Put-away Move](https://github.com/louisburroughs/durion-positivity-backend/issues/31)

---

## Change Log

| Date | Author | Change |
|------|--------|--------|
| 2026-01-12 | @louisburroughs | Initial clarification decisions documented |
