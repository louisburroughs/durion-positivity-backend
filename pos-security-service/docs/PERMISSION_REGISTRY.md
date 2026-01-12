# Permission Registry System Documentation

## Overview

The Permission Registry System implements a foundational security framework for the Positivity POS system, providing centralized permission management with scoped RBAC (Role-Based Access Control).

## Permission Naming Convention

All permissions must follow the standardized format:

```
domain:resource:action
```

### Rules

1. **All lowercase** - No uppercase letters allowed
2. **Singular nouns** - Use singular form for resources
3. **Action verbs** - Use clear action verbs (view, create, edit, delete, approve, etc.)
4. **Alphanumeric with underscores** - Only lowercase letters, numbers, and underscores
5. **Three-part format** - Must contain exactly three parts separated by colons

### Examples

| Permission | Description |
|------------|-------------|
| `pricing:price_book:view` | View price books in the pricing domain |
| `pricing:price_book:edit` | Edit price books in the pricing domain |
| `inventory:adjustment:approve` | Approve inventory adjustments |
| `security:role:assign` | Assign roles to users |
| `workexec:workorder:cancel` | Cancel work orders |
| `product:catalog:publish` | Publish product catalogs |

### Invalid Examples

❌ `Pricing:PriceBook:Edit` - Contains uppercase  
❌ `pricing-pricebook-edit` - Wrong separator  
❌ `pricing:edit` - Missing resource  
❌ `pricing:price_books:edit` - Plural noun

## Permission Registration Process

### 1. Create Permission Manifest

Each service should maintain a `permissions.yaml` file in its resources directory:

```yaml
domain: pricing
serviceName: pos-price-service
version: "1.0"
permissions:
  - name: pricing:price_book:view
    description: View price books and pricing rules
  - name: pricing:price_book:create
    description: Create new price books
  - name: pricing:price_book:edit
    description: Edit existing price books
  - name: pricing:price_book:delete
    description: Delete price books
  - name: pricing:price_book:publish
    description: Publish price books to make them active
```

### 2. Register Permissions via API

Services should register their permissions during deployment or startup:

```bash
curl -X POST https://security-service/api/permissions/register \
  -H "Content-Type: application/json" \
  -d @permissions.yaml
```

### 3. Registration Response

```json
{
  "success": true,
  "message": "Processed 5 permissions: 5 registered, 0 updated, 0 skipped",
  "totalPermissions": 5,
  "registeredPermissions": 5,
  "updatedPermissions": 0,
  "skippedPermissions": 0,
  "errors": []
}
```

## Role Management

### Creating Roles

Roles are containers for permissions. The foundational security framework provides the capability to create roles, but business-specific roles should be defined in domain integration stories.

```bash
# Create a role
curl -X POST https://security-service/api/roles \
  -H "Content-Type: application/json" \
  -d '{
    "name": "PricingAnalyst",
    "description": "Can view and edit pricing data"
  }'
```

### Assigning Permissions to Roles

```bash
# Assign permissions to a role
curl -X PUT https://security-service/api/roles/permissions \
  -H "Content-Type: application/json" \
  -d '{
    "roleId": 1,
    "permissionNames": [
      "pricing:price_book:view",
      "pricing:price_book:edit"
    ]
  }'
```

## Role Assignments with Scope

### Scope Types

- **GLOBAL**: Permission applies to all resources regardless of location
- **LOCATION**: Permission applies only to resources in specified locations

### Creating Role Assignments

```bash
# Global role assignment
curl -X POST https://security-service/api/roles/assignments \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 123,
    "roleId": 1,
    "scopeType": "GLOBAL",
    "effectiveStartDate": "2026-01-01"
  }'

# Location-scoped role assignment
curl -X POST https://security-service/api/roles/assignments \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 456,
    "roleId": 2,
    "scopeType": "LOCATION",
    "scopeLocationIds": ["LOC-123", "LOC-456"],
    "effectiveStartDate": "2026-01-01",
    "effectiveEndDate": "2026-12-31"
  }'
```

## Authorization Checks

### Checking User Permissions

```bash
# Check if user has permission (global)
curl -X GET "https://security-service/api/roles/check-permission?userId=123&permission=pricing:price_book:edit"

# Check if user has permission for specific location
curl -X GET "https://security-service/api/roles/check-permission?userId=123&permission=pricing:price_book:edit&locationId=LOC-123"
```

### How Authorization Works

1. System retrieves all effective role assignments for the user
2. For each assignment, checks if it covers the requested location:
   - GLOBAL scope always covers any location
   - LOCATION scope checks if locationId is in scopeLocationIds
3. If scope matches, checks if the role has the requested permission
4. Returns true if any matching assignment has the permission

## Effective Dating

Role assignments support effective dating for:
- **Temporary access** - Set both start and end dates
- **Future access** - Set start date in the future
- **Immediate access** - Start date defaults to today
- **Permanent access** - No end date (null)

### Example: Temporary Contractor Access

```json
{
  "userId": 789,
  "roleId": 3,
  "scopeType": "LOCATION",
  "scopeLocationIds": ["LOC-789"],
  "effectiveStartDate": "2026-02-01",
  "effectiveEndDate": "2026-03-31"
}
```

## API Endpoints Reference

### Permission Registry Endpoints

- `POST /api/permissions/register` - Register service permissions
- `GET /api/permissions` - Get all registered permissions (admin only)
- `GET /api/permissions/domain/{domain}` - Get permissions by domain
- `GET /api/permissions/validate/{permissionName}` - Validate permission name format
- `GET /api/permissions/exists/{permissionName}` - Check if permission exists

### Role Management Endpoints

- `POST /api/roles` - Create a new role (admin only)
- `GET /api/roles` - Get all roles
- `GET /api/roles/{name}` - Get role by name
- `PUT /api/roles/permissions` - Update role permissions (admin only)
- `POST /api/roles/assignments` - Create role assignment
- `GET /api/roles/assignments/user/{userId}` - Get user's role assignments
- `GET /api/roles/permissions/user/{userId}` - Get all user's permissions
- `GET /api/roles/check-permission` - Check if user has permission
- `DELETE /api/roles/assignments/{assignmentId}` - Revoke role assignment

## Integration Patterns

### Service Initialization

Each service should register its permissions during startup:

```java
@Component
@RequiredArgsConstructor
public class PermissionRegistrationInitializer {
    private final RestTemplate restTemplate;
    
    @PostConstruct
    public void registerPermissions() {
        PermissionRegistrationRequest request = loadPermissionsFromYaml();
        
        ResponseEntity<PermissionRegistrationResponse> response = 
            restTemplate.postForEntity(
                "https://security-service/api/permissions/register",
                request,
                PermissionRegistrationResponse.class
            );
        
        log.info("Permission registration: {}", response.getBody().getMessage());
    }
}
```

### Authorization in Controllers

```java
@RestController
@RequestMapping("/api/prices")
public class PriceController {
    
    @PreAuthorize("hasAuthority('pricing:price_book:edit')")
    @PutMapping("/{id}")
    public ResponseEntity<PriceBook> updatePriceBook(@PathVariable Long id, 
                                                     @RequestBody PriceBookRequest request) {
        // Implementation
    }
}
```

## Security Considerations

1. **Permission names are immutable** - Once registered, the name cannot be changed
2. **Least privilege principle** - Grant only necessary permissions
3. **Audit trail** - All role assignments track who created/modified them
4. **Scope validation** - Always validate location scope for sensitive operations
5. **Token claims** - Do NOT encode permissions in JWT tokens; query them from the security service

## Future Enhancements

The following features are out of scope for the foundational story but may be added later:

- **Attribute-Based Access Control (ABAC)** - More complex policy-based authorization
- **Permission groups/categories** - Organize related permissions
- **Dynamic permission evaluation** - Runtime policy evaluation
- **Permission delegation** - Allow users to delegate their permissions
- **Time-based permissions** - Permissions that are only valid at certain times
