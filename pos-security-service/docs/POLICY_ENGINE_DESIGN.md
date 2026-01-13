# Policy Engine Design

> **Purpose:** Enforce business rules and thresholds without exploding the permission space  
> **Status:** Design Document  
> **Version:** 1.0  
> **Date:** 2026-01-13

## Problem Statement

Many business operations have conditional authorization requirements:

- Refunds **under $100** can be approved by Managers
- Refunds **over $100** require District Manager approval
- Inventory adjustments **over 50 units** require Controller approval
- Price overrides **over 20%** require Regional Manager approval

**Anti-Pattern:** Create separate permissions for each threshold combination
- ❌ `financial:refund:approve_under_100`
- ❌ `financial:refund:approve_100_to_500`
- ❌ `financial:refund:approve_over_500`

**Problem:** This leads to **permission explosion** and makes policy changes require code/schema updates.

## Solution: Policy Engine

Separate **capability** (permission) from **constraints** (policy rules).

### Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Authorization Request                 │
│  (user, operation, context)                             │
└─────────────────────┬───────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────┐
│              Permission Check (RBAC)                    │
│  Does user have permission for operation?               │
│  • financial:refund:approve                             │
└─────────────────────┬───────────────────────────────────┘
                      │
                      ├─ NO ──► DENY
                      │
                      ▼ YES
┌─────────────────────────────────────────────────────────┐
│              Policy Engine Evaluation                   │
│  Do constraints allow this operation?                   │
│  • Check thresholds                                     │
│  • Check context (location, time, etc.)                 │
│  • Apply business rules                                 │
└─────────────────────┬───────────────────────────────────┘
                      │
                      ├─ PASSES ──► ALLOW
                      │
                      └─ FAILS ───► REQUIRE_APPROVAL or DENY
```

## Policy Rule Structure

Policies are **data**, not code. They are versioned and auditable.

### Policy Schema

```yaml
policyId: "REFUND_APPROVAL_001"
version: "1.0"
effectiveDate: "2026-01-01"
domain: "financial"
resource: "refund"
action: "approve"

rules:
  - name: "Small Refund - Manager Approval"
    condition:
      amount: { lessThan: 100.00 }
    requiredRole: "Manager"
    
  - name: "Medium Refund - District Manager Approval"
    condition:
      amount: { greaterThanOrEqual: 100.00, lessThan: 500.00 }
    requiredRole: "DistrictManager"
    
  - name: "Large Refund - Regional Manager Approval"
    condition:
      amount: { greaterThanOrEqual: 500.00 }
    requiredRole: "RegionalManager"

audit:
  logLevel: "HIGH"
  retentionYears: 7
```

### Example: Inventory Adjustment Threshold

```yaml
policyId: "INVENTORY_ADJUSTMENT_001"
version: "1.0"
domain: "inventory"
resource: "adjustment"
action: "approve"

rules:
  - name: "Small Adjustment"
    condition:
      quantity: { lessThanOrEqual: 50 }
      OR:
        value: { lessThan: 500.00 }
    requiredPermission: "inventory:adjustment:approve"
    requiredScope: "LOCATION"  # Must be scoped to location
    
  - name: "Large Adjustment"
    condition:
      quantity: { greaterThan: 50 }
      OR:
        value: { greaterThanOrEqual: 500.00 }
    requiredRole: "InventoryController"
    requiredScope: "GLOBAL"  # Requires global authority
```

## Policy Evaluation Flow

### Step 1: Permission Check

```java
// Check if user has base permission
boolean hasPermission = roleManagementService.checkPermission(
    userId, 
    "financial:refund:approve", 
    locationId
);

if (!hasPermission) {
    return AuthorizationResult.denied("Missing required permission");
}
```

### Step 2: Load Applicable Policies

```java
// Load policy for this operation
Policy policy = policyEngine.getPolicy(
    domain: "financial",
    resource: "refund",
    action: "approve",
    effectiveDate: LocalDate.now()
);
```

### Step 3: Evaluate Policy Rules

```java
// Build context with operation details
PolicyContext context = PolicyContext.builder()
    .userId(userId)
    .locationId(locationId)
    .attribute("amount", refundAmount)
    .attribute("category", refundCategory)
    .attribute("customer", customerId)
    .build();

// Evaluate policy
PolicyResult result = policyEngine.evaluate(policy, context);

if (result.isAllowed()) {
    return AuthorizationResult.allowed();
} else if (result.requiresApproval()) {
    return AuthorizationResult.requiresApproval(
        approverRole: result.getRequiredRole(),
        reason: result.getReason()
    );
} else {
    return AuthorizationResult.denied(result.getReason());
}
```

## Policy Storage

Policies are stored in the database for runtime evaluation:

```sql
CREATE TABLE policies (
    id BIGINT PRIMARY KEY,
    policy_id VARCHAR(100) UNIQUE NOT NULL,
    version VARCHAR(20) NOT NULL,
    domain VARCHAR(50) NOT NULL,
    resource VARCHAR(100) NOT NULL,
    action VARCHAR(50) NOT NULL,
    effective_date DATE NOT NULL,
    expiration_date DATE,
    rules_json TEXT NOT NULL,  -- JSON serialization of rules
    audit_config_json TEXT,
    created_at TIMESTAMP,
    created_by VARCHAR(255)
);

CREATE INDEX idx_policies_domain_resource_action 
    ON policies(domain, resource, action);
CREATE INDEX idx_policies_effective_date 
    ON policies(effective_date, expiration_date);
```

## Policy Versioning

Policies are **versioned** to enable audit trail and rollback:

```yaml
# Version 1.0 (effective 2026-01-01 to 2026-06-30)
policyId: "REFUND_APPROVAL_001"
version: "1.0"
effectiveDate: "2026-01-01"
expirationDate: "2026-06-30"
rules:
  - name: "Manager approval under $100"
    condition: { amount: { lessThan: 100.00 }}
    requiredRole: "Manager"

# Version 2.0 (effective 2026-07-01)
policyId: "REFUND_APPROVAL_001"
version: "2.0"
effectiveDate: "2026-07-01"
rules:
  - name: "Manager approval under $150"  # Threshold increased
    condition: { amount: { lessThan: 150.00 }}
    requiredRole: "Manager"
```

Historical audits use the **policy version active at the time** of the authorization decision.

## Implementation Plan

### Phase 1: Basic Rule Engine (v1.0)

- ✅ Permission-based authorization (already implemented)
- 🔲 Simple threshold rules (amount, quantity comparisons)
- 🔲 Role-based constraint checks
- 🔲 Policy versioning and storage

### Phase 2: Advanced Rules (v2.0)

- 🔲 Complex boolean logic (AND/OR/NOT)
- 🔲 Time-based constraints (business hours, blackout periods)
- 🔲 Rate limiting (max operations per time window)
- 🔲 Approval workflow integration

### Phase 3: Dynamic Policies (v3.0)

- 🔲 External policy sources (e.g., compliance service)
- 🔲 Machine learning for anomaly detection
- 🔲 Real-time policy updates without deployment

## Example Use Cases

### Use Case 1: Refund Approval

```java
@PostMapping("/refunds/{id}/approve")
public ResponseEntity<?> approveRefund(
    @PathVariable Long id,
    @RequestHeader("X-User-Id") Long userId
) {
    Refund refund = refundService.getById(id);
    
    // Check permission + policy
    AuthorizationResult authz = authorizationService.authorize(
        userId,
        "financial:refund:approve",
        PolicyContext.builder()
            .attribute("amount", refund.getAmount())
            .attribute("category", refund.getCategory())
            .attribute("locationId", refund.getLocationId())
            .build()
    );
    
    if (authz.isAllowed()) {
        refundService.approve(refund, userId);
        return ResponseEntity.ok().build();
    } else if (authz.requiresApproval()) {
        // Escalate to higher authority
        return ResponseEntity.status(403).body(
            Map.of(
                "error", "Additional approval required",
                "requiredRole", authz.getRequiredRole(),
                "reason", authz.getReason()
            )
        );
    } else {
        return ResponseEntity.status(403).body(
            Map.of("error", authz.getReason())
        );
    }
}
```

### Use Case 2: Inventory Adjustment

```java
@PostMapping("/adjustments/{id}/approve")
public ResponseEntity<?> approveAdjustment(
    @PathVariable Long id,
    @RequestHeader("X-User-Id") Long userId
) {
    Adjustment adj = adjustmentService.getById(id);
    
    AuthorizationResult authz = authorizationService.authorize(
        userId,
        "inventory:adjustment:approve",
        PolicyContext.builder()
            .attribute("quantity", adj.getQuantity())
            .attribute("value", adj.getEstimatedValue())
            .attribute("locationId", adj.getLocationId())
            .build()
    );
    
    if (!authz.isAllowed()) {
        throw new ForbiddenException(authz.getReason());
    }
    
    adjustmentService.approve(adj, userId);
    return ResponseEntity.ok().build();
}
```

## Testing Strategy

### Unit Tests

```java
@Test
void shouldAllowManagerForSmallRefund() {
    Policy policy = loadPolicy("REFUND_APPROVAL_001");
    PolicyContext context = PolicyContext.builder()
        .userId(123L)
        .userRoles(Set.of("Manager"))
        .attribute("amount", 50.00)
        .build();
    
    PolicyResult result = policyEngine.evaluate(policy, context);
    
    assertThat(result.isAllowed()).isTrue();
}

@Test
void shouldRequireDistrictManagerForLargeRefund() {
    Policy policy = loadPolicy("REFUND_APPROVAL_001");
    PolicyContext context = PolicyContext.builder()
        .userId(123L)
        .userRoles(Set.of("Manager"))
        .attribute("amount", 250.00)
        .build();
    
    PolicyResult result = policyEngine.evaluate(policy, context);
    
    assertThat(result.requiresApproval()).isTrue();
    assertThat(result.getRequiredRole()).isEqualTo("DistrictManager");
}
```

### Integration Tests

Test policy engine integration with actual authorization service:

```java
@Test
void shouldEvaluatePolicyForRefundApproval() {
    // Given: User with Manager role
    User user = createUser(roles: ["Manager"]);
    Refund refund = createRefund(amount: 75.00);
    
    // When: Authorize approval
    AuthorizationResult result = authorizationService.authorize(
        user.getId(),
        "financial:refund:approve",
        PolicyContext.builder()
            .attribute("amount", refund.getAmount())
            .build()
    );
    
    // Then: Should be allowed
    assertThat(result.isAllowed()).isTrue();
}
```

## Benefits

1. ✅ **No Permission Explosion** - Permissions stay stable as thresholds change
2. ✅ **Business Rule Flexibility** - Update thresholds without code deployment
3. ✅ **Audit Trail** - Policy versions capture "what was allowed when"
4. ✅ **Testability** - Policies can be unit tested independently
5. ✅ **Compliance** - Clear separation of capability and constraint

## Migration Path

Existing permission-only checks remain valid. Policy engine is **additive**, not replacement:

```java
// Phase 1: Permission check only (current state)
if (!hasPermission(user, "financial:refund:approve")) {
    throw new ForbiddenException();
}

// Phase 2: Permission + policy check (enhanced)
AuthorizationResult authz = authorize(user, "financial:refund:approve", context);
if (!authz.isAllowed()) {
    if (authz.requiresApproval()) {
        escalateToApprover(authz.getRequiredRole());
    } else {
        throw new ForbiddenException(authz.getReason());
    }
}
```

## References

- **RBAC Policy:** `docs/RBAC_POLICY.md` - Core access control model
- **Permission Registry:** `docs/PERMISSION_REGISTRY.md` - Permission naming and registration
- **Origin:** Clarification Issue #42, Question 1 - Threshold handling decision
