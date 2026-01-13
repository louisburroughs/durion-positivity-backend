# RBAC Framework Usage Examples

This document provides practical examples of how to use the POS Security Service RBAC framework implemented for Issue #2.

---

## Table of Contents

1. [Quick Start](#quick-start)
2. [Creating Roles and Permissions](#creating-roles-and-permissions)
3. [Assigning Roles to Users](#assigning-roles-to-users)
4. [Checking Permissions](#checking-permissions)
5. [Location-Scoped Permissions](#location-scoped-permissions)
6. [Time-Bound Role Assignments](#time-bound-role-assignments)
7. [Revoking Access](#revoking-access)
8. [Integrating with Business Services](#integrating-with-business-services)

---

## Quick Start

### Prerequisites
- POS Security Service running on port 8080 (or configured port)
- JWT authentication token with ADMIN role
- User account already created in the system

### Base URL
```
http://localhost:8080/api/roles
```

---

## Creating Roles and Permissions

### 1. Register a Permission

First, permissions must be registered in the system before they can be assigned to roles.

```bash
# Register a refund approval permission
POST /api/permissions/register
Content-Type: application/json
Authorization: Bearer {JWT_TOKEN}

{
  "name": "financial:refund:approve",
  "description": "Approve customer refund requests",
  "registeredByService": "pos-accounting"
}
```

**Response:**
```json
{
  "id": 1,
  "name": "financial:refund:approve",
  "description": "Approve customer refund requests",
  "domain": "financial",
  "resource": "refund",
  "action": "approve",
  "registeredAt": "2026-01-13T10:00:00Z",
  "registeredByService": "pos-accounting",
  "version": "1.0"
}
```

### 2. Create a Role

```bash
POST /api/roles
Content-Type: application/json
Authorization: Bearer {JWT_TOKEN}

{
  "name": "Cashier",
  "description": "Front desk cashier role with basic POS operations"
}
```

**Response:**
```json
{
  "id": 1,
  "name": "Cashier",
  "description": "Front desk cashier role with basic POS operations",
  "permissions": [],
  "createdAt": "2026-01-13T10:05:00Z",
  "createdBy": "admin"
}
```

### 3. Assign Permissions to a Role

```bash
PUT /api/roles/permissions
Content-Type: application/json
Authorization: Bearer {JWT_TOKEN}

{
  "roleId": 1,
  "permissionNames": [
    "pos:order:create",
    "pos:order:view",
    "pos:payment:accept"
  ]
}
```

**Response:**
```json
{
  "id": 1,
  "name": "Cashier",
  "description": "Front desk cashier role with basic POS operations",
  "permissions": [
    {
      "id": 2,
      "name": "pos:order:create",
      "description": "Create new sales orders",
      ...
    },
    {
      "id": 3,
      "name": "pos:order:view",
      "description": "View sales orders",
      ...
    },
    {
      "id": 4,
      "name": "pos:payment:accept",
      "description": "Accept customer payments",
      ...
    }
  ],
  "createdAt": "2026-01-13T10:05:00Z",
  "createdBy": "admin",
  "lastModifiedAt": "2026-01-13T10:10:00Z",
  "lastModifiedBy": "admin"
}
```

---

## Assigning Roles to Users

### Basic Role Assignment (Global Scope)

```bash
POST /api/roles/assignments
Content-Type: application/json
Authorization: Bearer {JWT_TOKEN}

{
  "userId": 123,
  "roleId": 1,
  "scopeType": "GLOBAL",
  "effectiveStartDate": "2026-01-13"
}
```

**Response:**
```json
{
  "id": 1,
  "user": {
    "id": 123,
    "username": "jane.doe"
  },
  "role": {
    "id": 1,
    "name": "Cashier"
  },
  "scopeType": "GLOBAL",
  "scopeLocationIds": [],
  "effectiveStartDate": "2026-01-13",
  "effectiveEndDate": null,
  "createdAt": "2026-01-13T10:15:00Z",
  "createdBy": "admin"
}
```

---

## Checking Permissions

### Check if User Has Permission

```bash
GET /api/roles/check-permission?userId=123&permission=pos:order:create&locationId=STORE-001
Authorization: Bearer {JWT_TOKEN}
```

**Response:**
```json
true
```

### Get All User Permissions

```bash
GET /api/roles/permissions/user/123
Authorization: Bearer {JWT_TOKEN}
```

**Response:**
```json
[
  {
    "id": 2,
    "name": "pos:order:create",
    "description": "Create new sales orders",
    "domain": "pos",
    "resource": "order",
    "action": "create"
  },
  {
    "id": 3,
    "name": "pos:order:view",
    "description": "View sales orders",
    "domain": "pos",
    "resource": "order",
    "action": "view"
  }
]
```

### Get User's Role Assignments

```bash
GET /api/roles/assignments/user/123
Authorization: Bearer {JWT_TOKEN}
```

**Response:**
```json
[
  {
    "id": 1,
    "role": {
      "id": 1,
      "name": "Cashier",
      "permissions": [...]
    },
    "scopeType": "GLOBAL",
    "effectiveStartDate": "2026-01-13",
    "effectiveEndDate": null
  }
]
```

---

## Location-Scoped Permissions

Assign a role that only applies to specific locations (shops).

### Create Location-Scoped Assignment

```bash
POST /api/roles/assignments
Content-Type: application/json
Authorization: Bearer {JWT_TOKEN}

{
  "userId": 124,
  "roleId": 2,
  "scopeType": "LOCATION",
  "scopeLocationIds": ["STORE-001", "STORE-002"],
  "effectiveStartDate": "2026-01-13"
}
```

**Use Case:** District manager who has authority over two specific stores.

**Result:**
- User 124 has "Manager" role permissions **only at STORE-001 and STORE-002**
- Permission checks at STORE-003 will fail
- `GET /api/roles/check-permission?userId=124&permission=inventory:adjust:approve&locationId=STORE-003` returns `false`
- `GET /api/roles/check-permission?userId=124&permission=inventory:adjust:approve&locationId=STORE-001` returns `true`

### Example: Manager at Multiple Locations

```json
{
  "userId": 125,
  "roleId": 3,
  "scopeType": "LOCATION",
  "scopeLocationIds": ["STORE-001", "STORE-003", "STORE-007"],
  "effectiveStartDate": "2026-01-13"
}
```

---

## Time-Bound Role Assignments

Assign temporary roles with automatic expiration.

### Temporary Manager Assignment (30 days)

```bash
POST /api/roles/assignments
Content-Type: application/json
Authorization: Bearer {JWT_TOKEN}

{
  "userId": 126,
  "roleId": 2,
  "scopeType": "GLOBAL",
  "effectiveStartDate": "2026-01-13",
  "effectiveEndDate": "2026-02-12"
}
```

**Use Case:** Temporary manager coverage during vacation.

**Behavior:**
- Role is effective from 2026-01-13 to 2026-02-12
- On 2026-02-13, the role automatically expires
- No manual revocation needed
- `GET /api/roles/assignments/user/126` will **not** return this assignment after 2026-02-12

### Weekend Supervisor Assignment

```json
{
  "userId": 127,
  "roleId": 4,
  "scopeType": "GLOBAL",
  "effectiveStartDate": "2026-01-18",
  "effectiveEndDate": "2026-01-19"
}
```

**Use Case:** Weekend-only supervisor authority.

---

## Revoking Access

### Revoke a Role Assignment

```bash
DELETE /api/roles/assignments/1
Authorization: Bearer {JWT_TOKEN}
```

**Response:**
```
204 No Content
```

**Behavior:**
- Sets `effectiveEndDate` to yesterday (immediate revocation)
- Does NOT delete the assignment record (audit trail preserved)
- User loses permissions immediately
- Audit fields updated: `lastModifiedAt` and `lastModifiedBy`

### Verify Revocation

```bash
GET /api/roles/assignments/user/123
Authorization: Bearer {JWT_TOKEN}
```

**Response:**
```json
[]
```

The revoked assignment is no longer returned because it's no longer effective.

---

## Integrating with Business Services

### Example: Accounting Service Permission Check

```java
package com.positivity.accounting.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class RefundService {
    
    private final RestTemplate restTemplate;
    
    public RefundService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }
    
    public boolean processRefund(Long userId, String locationId, RefundRequest request) {
        // Check if user has permission to approve refund
        String url = "http://pos-security-service/api/roles/check-permission" +
                    "?userId=" + userId + 
                    "&permission=financial:refund:approve" +
                    "&locationId=" + locationId;
        
        Boolean hasPermission = restTemplate.getForObject(url, Boolean.class);
        
        if (!hasPermission) {
            throw new SecurityException("User does not have permission to approve refunds at this location");
        }
        
        // Process the refund
        return executeRefund(request);
    }
}
```

### Example: Price Override Authorization

```java
@Service
public class PricingService {
    
    private final RoleManagementServiceClient securityClient;
    
    public void overridePrice(Long userId, String locationId, PriceOverride override) {
        // Check permission
        boolean canOverride = securityClient.userHasPermission(
            userId, 
            "pricing:price:override", 
            locationId
        );
        
        if (!canOverride) {
            // Log security event
            auditService.logAuthorizationDenied(
                userId, 
                "pricing:price:override", 
                locationId
            );
            
            throw new AccessDeniedException(
                "User does not have permission to override prices at location " + locationId
            );
        }
        
        // Apply override
        applyPriceOverride(override);
        
        // Log the override
        auditService.logPriceOverride(userId, override);
    }
}
```

### Example: Inventory Adjustment with Scope Check

```java
@Service
public class InventoryService {
    
    public void adjustInventory(Long userId, String locationId, InventoryAdjustment adjustment) {
        // Check permission with location scope
        if (!hasPermission(userId, "inventory:adjustment:approve", locationId)) {
            throw new AccessDeniedException(
                "User does not have inventory adjustment authority at " + locationId
            );
        }
        
        // Execute adjustment
        processAdjustment(adjustment);
    }
    
    private boolean hasPermission(Long userId, String permission, String locationId) {
        String url = String.format(
            "http://pos-security-service/api/roles/check-permission?userId=%d&permission=%s&locationId=%s",
            userId, permission, locationId
        );
        return restTemplate.getForObject(url, Boolean.class);
    }
}
```

---

## Common Patterns

### Pattern 1: Manager Hierarchy

```bash
# Create roles
POST /api/roles {"name": "Cashier", "description": "Basic POS operations"}
POST /api/roles {"name": "ShiftLead", "description": "Shift lead with some overrides"}
POST /api/roles {"name": "StoreManager", "description": "Store manager with full authority"}

# Assign permissions (additive, no inheritance)
PUT /api/roles/permissions 
{
  "roleId": 1,
  "permissionNames": ["pos:order:create", "pos:payment:accept"]
}

PUT /api/roles/permissions
{
  "roleId": 2,
  "permissionNames": [
    "pos:order:create", 
    "pos:payment:accept",
    "pricing:discount:approve_small"
  ]
}

PUT /api/roles/permissions
{
  "roleId": 3,
  "permissionNames": [
    "pos:order:create",
    "pos:payment:accept",
    "pricing:discount:approve_small",
    "pricing:discount:approve_large",
    "financial:refund:approve",
    "inventory:adjustment:approve"
  ]
}
```

### Pattern 2: Multi-Location District Manager

```bash
# Single user, multiple locations
POST /api/roles/assignments
{
  "userId": 150,
  "roleId": 3,
  "scopeType": "LOCATION",
  "scopeLocationIds": [
    "STORE-001",
    "STORE-002",
    "STORE-005",
    "STORE-010"
  ],
  "effectiveStartDate": "2026-01-13"
}
```

### Pattern 3: Temporary Elevated Access (Break-Glass)

```bash
# Grant temporary admin access for emergency
POST /api/roles/assignments
{
  "userId": 200,
  "roleId": 99,  # BREAK_GLASS_ADMIN role
  "scopeType": "GLOBAL",
  "effectiveStartDate": "2026-01-13T15:00:00",
  "effectiveEndDate": "2026-01-13T19:00:00"
}
```

---

## Security Best Practices

### 1. Always Check Permissions at Operation Invocation
```java
// ✅ GOOD
public void sensitiveOperation(Long userId, String locationId) {
    if (!securityService.userHasPermission(userId, "domain:resource:action", locationId)) {
        throw new AccessDeniedException("Insufficient permissions");
    }
    // execute operation
}

// ❌ BAD
public void sensitiveOperation(Long userId) {
    // No permission check - assumes caller verified
    // execute operation
}
```

### 2. Use Location Scope for Multi-Tenant Operations
```java
// ✅ GOOD
securityService.userHasPermission(userId, "inventory:adjust:approve", currentLocationId)

// ❌ BAD
securityService.userHasPermission(userId, "inventory:adjust:approve", "GLOBAL")
```

### 3. Log All Authorization Failures
```java
if (!hasPermission) {
    log.warn("Authorization denied: user={}, permission={}, location={}", 
             userId, permission, locationId);
    auditService.logAuthorizationDenied(userId, permission, locationId);
    throw new AccessDeniedException("Insufficient permissions");
}
```

### 4. Validate Permissions Exist Before Assignment
The framework automatically validates that permissions are registered before allowing role-permission assignments. Always register permissions first:

```bash
# 1. Register permission
POST /api/permissions/register {"name": "new:feature:action", ...}

# 2. Assign to role
PUT /api/roles/permissions {"roleId": 1, "permissionNames": ["new:feature:action"]}
```

---

## Troubleshooting

### Issue: Permission Check Returns False Unexpectedly

**Check:**
1. Is the role assignment currently effective? (Check effective dates)
2. Does the role have the required permission?
3. Does the location scope cover the requested location?
4. Is the user ID correct?

```bash
# Debug steps
GET /api/roles/assignments/user/{userId}  # See effective assignments
GET /api/roles/permissions/user/{userId}  # See all granted permissions
GET /api/roles/{roleName}                 # See role's permissions
```

### Issue: Role Assignment Not Taking Effect

**Check:**
1. `effectiveStartDate` is not in the future
2. `effectiveEndDate` is not in the past or null
3. Role assignment was successfully created (check response)

### Issue: Location-Scoped Permission Always Fails

**Check:**
1. `scopeType` is set to "LOCATION" (not "GLOBAL")
2. `scopeLocationIds` contains the location being checked
3. Location ID format matches exactly (case-sensitive)

---

## API Reference Summary

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/roles` | POST | Create role |
| `/api/roles` | GET | List all roles |
| `/api/roles/{name}` | GET | Get role by name |
| `/api/roles/permissions` | PUT | Update role permissions |
| `/api/roles/assignments` | POST | Assign role to user |
| `/api/roles/assignments/{id}` | DELETE | Revoke role assignment |
| `/api/roles/assignments/user/{userId}` | GET | Get user's assignments |
| `/api/roles/permissions/user/{userId}` | GET | Get user's permissions |
| `/api/roles/check-permission` | GET | Check if user has permission |
| `/api/permissions/register` | POST | Register new permission |

---

## Related Documentation

- **ISSUE-2-VERIFICATION.md** - Comprehensive verification of RBAC implementation
- **pos-security-service/docs/RBAC_POLICY.md** - Policy decisions and rationale
- **pos-security-service/docs/BASELINE_PERMISSIONS.md** - Standard permission registry
- **pos-security-service/README.md** - Module overview

---

**Last Updated:** 2026-01-13  
**Version:** 1.0  
**Status:** ✅ Production-Ready
