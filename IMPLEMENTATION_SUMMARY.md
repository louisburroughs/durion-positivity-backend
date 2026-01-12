# Implementation Summary: Inventory Adjustment Permissions

## Problem Addressed
Issue #37 required clarification on the permissions model for inventory adjustments. The user provided a decision to use granular, permission-based authorization instead of hard-coded role checks.

## Solution Implemented

### 1. Permission Registration (`InventoryPermissionInitializer.java`)
Created a Spring component that registers two core permissions at application startup:

- **`inventory:adjustment:create`**: Allows creating adjustment requests (draft/pending state)
  - Requires: reasonCode, quantity, notes (optional)
  - Captures audit fields: requestedBy, timestamp

- **`inventory:adjustment:approve`**: Allows approving and posting adjustments to the ledger
  - Creates ADJUSTMENT_IN/OUT events
  - Requires: approvedBy, approvalTimestamp, policyVersion

**Key Features:**
- Automatic registration at startup using `@PostConstruct`
- Follows established naming convention: `domain:resource:action`
- All lowercase, parsed into domain/resource/action components
- Skips already-registered permissions
- Comprehensive logging for audit trail

### 2. Role Definitions (`RoleInitializer.java`)
Updated the role initializer to include three inventory-specific roles:

- **`INVENTORY_LEAD`**: Can create adjustment requests (location-scoped)
- **`INVENTORY_MANAGER`**: Can create and approve adjustments (location-scoped)
- **`INVENTORY_CONTROLLER`**: Can approve adjustments globally or across multiple locations

**Implementation Details:**
- Roles are created at startup if they don't exist
- Each role has a descriptive explanation
- Roles bundle permissions without hard-coding authorization logic
- Scope (GLOBAL/LOCATION) is applied when assigning roles to users

### 3. Comprehensive Documentation (`INVENTORY_PERMISSIONS.md`)
Created detailed documentation covering:

- Permission model and philosophy
- Core permissions and their requirements
- Permission scopes (GLOBAL vs LOCATION)
- Recommended role-to-permission mappings
- Enforcement rules
- Threshold-based approval (future enhancement)
- API usage examples for:
  - Registering new permissions
  - Assigning permissions to roles
  - Assigning roles to users with scope
- Implementation references

## Architecture Alignment

### Security Service Integration
- Uses existing `PermissionRepository` infrastructure
- Follows established patterns from `RoleInitializer`
- Integrates with existing `PermissionRegistryService`
- Leverages Spring Boot's component scanning

### Permission Naming Convention
```
domain:resource:action
inventory:adjustment:create
inventory:adjustment:approve
```

### Scope Support
Permissions themselves are not scoped; scope is applied when assigning roles to users:
```java
RoleAssignment {
  userId: 123,
  roleId: 456,
  scopeType: LOCATION,
  scopeLocationIds: ["LOC-001", "LOC-002"]
}
```

## What's NOT Included (By Design)

The following are explicitly out of scope for this implementation:

1. **Business Logic**: The actual adjustment workflow logic belongs in the inventory service
2. **Threshold Checks**: Logic to enforce approval requirements based on value/quantity thresholds
3. **Authorization Enforcement**: The inventory service will need to check permissions using `RoleManagementService.userHasPermission()`
4. **UI Changes**: Frontend modifications to support the new permission model
5. **API Endpoints**: REST endpoints for creating/approving adjustments

## Usage Example

After this implementation, the inventory service can check permissions like this:

```java
@Service
public class InventoryAdjustmentService {
    private final RoleManagementService roleManagementService;
    
    public void createAdjustment(Long userId, String locationId, AdjustmentRequest request) {
        // Check permission
        boolean hasPermission = roleManagementService.userHasPermission(
            userId,
            "inventory:adjustment:create",
            locationId
        );
        
        if (!hasPermission) {
            throw new AccessDeniedException("User lacks inventory:adjustment:create permission");
        }
        
        // Create adjustment in PENDING state
        // ...
    }
    
    public void approveAdjustment(Long userId, String locationId, String adjustmentId) {
        // Check permission
        boolean hasPermission = roleManagementService.userHasPermission(
            userId,
            "inventory:adjustment:approve",
            locationId
        );
        
        if (!hasPermission) {
            throw new AccessDeniedException("User lacks inventory:adjustment:approve permission");
        }
        
        // Check thresholds, then approve and post to ledger
        // ...
    }
}
```

## Benefits of This Approach

1. **Flexible**: Permissions can be assigned to any role without code changes
2. **Auditable**: All permission registrations and role assignments are logged
3. **Scalable**: New permissions can be added without modifying core security code
4. **Scope-Aware**: Supports both global and location-specific permissions
5. **Separation of Concerns**: Security service handles authorization; inventory service handles business logic
6. **Future-Proof**: Supports threshold-based approval and other enhancements

## Files Changed

1. **New**: `pos-security-service/src/main/java/com/positivity/securityservice/config/InventoryPermissionInitializer.java` (82 lines)
2. **New**: `pos-security-service/INVENTORY_PERMISSIONS.md` (161 lines)
3. **Modified**: `pos-security-service/src/main/java/com/positivity/securityservice/config/RoleInitializer.java` (+27 lines)

**Total**: 270 lines added across 3 files

## Testing Considerations

While we couldn't build/test due to Java version requirements (project needs Java 21, environment has Java 17), the code:

- Follows all existing patterns in the repository
- Uses established Spring Boot annotations and lifecycle hooks
- Imports only existing dependencies
- Is syntactically correct
- Matches the coding style of surrounding files

## Next Steps (Not Part of This PR)

1. Build and test with Java 21
2. Implement adjustment workflow in inventory service
3. Add threshold-based approval logic
4. Create REST endpoints for adjustment operations
5. Update UI to support new permission model
6. Add integration tests for permission checks
7. Update user documentation

## References

- Origin Issue: [#37 - Record Stock Movements in Inventory Ledger](https://github.com/louisburroughs/durion-positivity-backend/issues/37)
- Clarification Decision: Use granular permissions with scope support
- Security Service: `pos-security-service`
- Permission Registry: `PermissionRegistryService`
- Role Management: `RoleManagementService`
