# Inventory Adjustment Permissions

This document describes the permission-based authorization model for inventory adjustments, as defined in the clarification for issue #37.

## Permission Model

Inventory adjustments use **granular permissions** that are bundled into roles. This approach enables:
- Least-privilege assignment
- Separation of duties (create vs approve)
- Flexible role evolution without code changes

## Core Permissions

### `inventory:adjustment:create`
**Description:** Create an adjustment request (draft/pending), capture reason code, quantity, and supporting notes.

**Required Fields:**
- `reasonCode` (enum)
- `quantity` (number)
- `notes` (optional string)
- Audit fields: `requestedBy`, `timestamp`

**Scope:** Location-scoped or global

### `inventory:adjustment:approve`
**Description:** Approve and post an adjustment to the ledger (creates ADJUSTMENT_IN/OUT events).

**Required Fields:**
- All fields from create
- `approvedBy` (user identifier)
- `approvalTimestamp`
- `policyVersion` (for audit)

**Scope:** Location-scoped or global

## Permission Scopes

Permissions support two scope types:

- **GLOBAL:** Permission applies to all resources regardless of location
- **LOCATION:** Permission applies only to resources in specified locations

Scope is defined at the role assignment level, not the permission level.

## Role Mapping

The following roles bundle these permissions. **Note:** This is a recommended starting configuration. Role definitions can be modified through the security service API without code changes.

### Stock Clerk
**Permissions:** None  
**Description:** Cannot create or approve adjustments

### Inventory Lead
**Permissions:** 
- `inventory:adjustment:create` (location-scoped)

**Description:** Can create adjustment requests for their assigned location(s)

### Inventory Manager
**Permissions:**
- `inventory:adjustment:create` (location-scoped)
- `inventory:adjustment:approve` (location-scoped)

**Description:** Can create and approve adjustments for their assigned location(s)

### Inventory Controller / Director
**Permissions:**
- `inventory:adjustment:approve` (global or multi-location)

**Description:** Can approve adjustments across all or multiple locations

## Threshold-Based Approval (Future)

Even with the `inventory:adjustment:approve` permission, posting may require additional approval if the variance exceeds configured thresholds:
- Unit quantity threshold
- Value threshold
- Percentage variance threshold

This business logic will be implemented in the inventory service, not the security service.

## Enforcement Rules

### Always Required
- `reasonCode` (from predefined enum)
- `notes` (optional but recommended)
- Audit fields: `requestedBy`, `approvedBy`, timestamps, `policyVersion`

### Authorization Check Flow
1. Verify user has `inventory:adjustment:create` permission
2. Verify user has access to the target location (if location-scoped)
3. Create adjustment request in PENDING state
4. (Later) Verify user has `inventory:adjustment:approve` permission
5. (Later) Check if threshold requires additional approval
6. Post adjustment to ledger, creating ADJUSTMENT_IN/OUT events

## API Usage

### Registering Additional Permissions
Use the Permission Registry API to register new inventory permissions:

```bash
POST /api/permissions/register
{
  "domain": "inventory",
  "serviceName": "pos-inventory-service",
  "permissions": [
    {
      "name": "inventory:transfer:create",
      "description": "Create inventory transfer requests"
    }
  ]
}
```

### Assigning Permissions to Roles
Update role permissions through the Role Management API:

```bash
PUT /api/roles/{roleId}/permissions
{
  "permissionNames": [
    "inventory:adjustment:create",
    "inventory:adjustment:approve"
  ]
}
```

### Assigning Roles to Users
Create role assignments with scope:

```bash
POST /api/roles/assignments
{
  "userId": 123,
  "roleId": 456,
  "scopeType": "LOCATION",
  "scopeLocationIds": ["LOC-001", "LOC-002"],
  "effectiveStartDate": "2024-01-01"
}
```

## Implementation Details

### Permission Registration
Permissions are registered at application startup by `InventoryPermissionInitializer` in the security service.

### Role Initialization
Default roles are created by `RoleInitializer` in the security service.

### Permission Format
Permissions follow the format: `domain:resource:action`
- All lowercase
- Singular nouns for domain and resource
- Action verbs (create, approve, view, edit, delete)

## References

- Origin Issue: [#37 - Record Stock Movements in Inventory Ledger](https://github.com/louisburroughs/durion-positivity-backend/issues/37)
- Security Service: `pos-security-service`
- Permission Registry: `PermissionRegistryService`
- Role Management: `RoleManagementService`
