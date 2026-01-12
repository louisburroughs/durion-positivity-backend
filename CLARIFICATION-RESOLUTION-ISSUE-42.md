# Clarification Resolution Summary - Issue #42

## Clarification Issue
- **Issue Number:** #43
- **Origin Story:** Issue #42 - [BACKEND] [STORY] Security: Define Roles and Permission Matrix for Product/Pricing
- **Status:** RESOLVED

## Decisions Made

### 1. Domain Ownership & Story Split

**Decision:** Split the work into foundational and integration stories.

**Primary Domain:** `domain:security`

**Story Split:**
1. **Foundational Security Story (domain:security)** - IMPLEMENTED IN THIS PR
   - Permission model and naming convention
   - Central permission registry with validation
   - Role framework (create/assign roles, scopes, effective dates)
   - RoleAssignment entity with scope support (GLOBAL/LOCATION)
   - Authorization evaluation semantics (RBAC + scoped checks)
   - API endpoints for permission registration and role management

2. **Integration Stories (domain:pricing, domain:product, etc.)** - DEFERRED
   - Declare domain-specific permissions via manifest
   - Define business roles (e.g., PricingAnalyst, ProductAdmin)
   - Bind permissions to roles
   - Apply authorization checks to domain operations

### 2. Permission Naming Convention

**Decision:** Approved `domain:resource:action` as the global standard.

**Format:** `<domain>:<resource>:<action>`

**Rules:**
- All lowercase
- Singular nouns for resources
- Action verbs (view, create, edit, delete, approve, assign, etc.)
- Alphanumeric with underscores only
- Must be exactly three parts separated by colons

**Examples:**
- ✅ `pricing:price_book:edit`
- ✅ `inventory:adjustment:approve`
- ✅ `security:role:assign`
- ✅ `workexec:workorder:cancel`
- ❌ `Pricing:PriceBook:Edit` (uppercase)
- ❌ `pricing-pricebook-edit` (wrong separator)
- ❌ `pricing:edit` (missing resource)

**Validation:** Implemented in `PermissionRegistryService.isValidPermissionName()`

### 3. Permission Discovery/Registration

**Decision:** Declarative, code-adjacent manifest with central registration.

**Mechanism:**
- Each service declares permissions in a static YAML manifest (e.g., `permissions.yaml`)
- Manifest is checked into the service repository
- Versioned with the code

**Registration Flow:**
1. Service maintains `src/main/resources/permissions.yaml`
2. On deployment or startup, service calls Security Service API: `POST /api/permissions/register`
3. Security Service validates naming convention and stores in central registry
4. Duplicate permissions are updated, invalid ones are rejected with error messages

**Implementation:**
- `PermissionRegistrationRequest` DTO
- `PermissionController.registerPermissions()` endpoint
- `PermissionRegistryService` with validation logic
- Pattern matching: `^[a-z][a-z0-9_]*:[a-z][a-z0-9_]*:[a-z][a-z0-9_]*$`

**Example Manifest:**
```yaml
domain: pricing
serviceName: pos-price-service
version: "1.0"
permissions:
  - name: pricing:price_book:view
    description: View price books and pricing rules
  - name: pricing:price_book:edit
    description: Edit existing price books
```

### 4. Initial Role Definitions

**Decision:** Out of scope for the foundational security story.

**Foundational Story Includes (IMPLEMENTED):**
- Ability to create roles (`POST /api/roles`)
- Assign permissions to roles (`PUT /api/roles/permissions`)
- Scope roles (GLOBAL/LOCATION)
- Effective dating (start/end dates)
- Full audit trail (created/modified by/at)

**Deferred to Domain Stories:**
- Creation of business-specific roles (ProductAdmin, PricingAnalyst, InventoryController, etc.)
- Mapping those roles to domain permissions
- Role hierarchies or inheritance (if needed)

**Rationale:** Roles are organizational and business-contextual. Security provides the machinery, not the org chart.

### 5. Location Overrides & Authorization Model

**Decision:** Use RBAC with scoped assignments (NOT token claims, NOT full ABAC).

**Model:**
- Role assignments include scope attributes:
  - `scopeType`: GLOBAL | LOCATION
  - `scopeLocationIds`: Set of location identifiers

**Authorization Check Logic:**
1. Retrieve all effective role assignments for user (checks effective dates)
2. For each assignment, check if it covers the requested location:
   - GLOBAL scope: Always covers any location
   - LOCATION scope: Check if locationId is in scopeLocationIds set
3. If scope matches, check if the role has the requested permission
4. Return true if any matching assignment has the permission

**Implementation:**
- `RoleAssignment` entity with scope fields
- `ScopeType` enum (GLOBAL, LOCATION)
- `RoleAssignment.coversLocation(String locationId)` method
- `RoleManagementService.userHasPermission(userId, permission, locationId)` method

**Example:**
```json
{
  "userId": 456,
  "roleId": 2,
  "scopeType": "LOCATION",
  "scopeLocationIds": ["LOC-123", "LOC-456"],
  "effectiveStartDate": "2026-01-01",
  "effectiveEndDate": "2026-12-31"
}
```

**Explicitly NOT Implemented:**
- Location in JWT token claims (security risk, stale data)
- Full ABAC with arbitrary policy evaluation (unnecessary complexity for v1)

## Implementation Summary

### New Entities
1. **Permission** - Central registry of all permissions
   - Enforces naming convention
   - Tracks registration metadata (service, timestamp)
   - Parsed fields: domain, resource, action

2. **RoleAssignment** - User-Role assignments with scope
   - Many-to-one with User and Role
   - Scope type and location IDs
   - Effective start/end dates
   - Full audit trail

3. **ScopeType** (Enum) - GLOBAL, LOCATION

### Updated Entities
1. **Role** - Enhanced with permissions
   - Many-to-many with Permission
   - Audit fields (created/modified by/at)

### New Repositories
1. **PermissionRepository** - CRUD and queries for permissions
2. **RoleAssignmentRepository** - CRUD and effective date queries

### New Services
1. **PermissionRegistryService**
   - Register/update permissions from manifests
   - Validate permission naming format
   - Query permissions by domain/name

2. **RoleManagementService**
   - Create roles and assign permissions
   - Create/revoke role assignments
   - Check user permissions with scope validation
   - Get effective role assignments

### New Controllers
1. **PermissionController** - REST API for permission registry
   - POST `/api/permissions/register` - Register service permissions
   - GET `/api/permissions` - List all permissions (admin)
   - GET `/api/permissions/domain/{domain}` - Get by domain
   - GET `/api/permissions/validate/{name}` - Validate format
   - GET `/api/permissions/exists/{name}` - Check existence

2. **RoleController** - REST API for role management
   - POST `/api/roles` - Create role (admin)
   - GET `/api/roles` - List all roles
   - GET `/api/roles/{name}` - Get role by name
   - PUT `/api/roles/permissions` - Update role permissions (admin)
   - POST `/api/roles/assignments` - Create role assignment
   - GET `/api/roles/assignments/user/{userId}` - Get user assignments
   - GET `/api/roles/permissions/user/{userId}` - Get user permissions
   - GET `/api/roles/check-permission` - Check permission with scope
   - DELETE `/api/roles/assignments/{id}` - Revoke assignment

### Documentation
- **PERMISSION_REGISTRY.md** - Comprehensive guide covering:
  - Permission naming convention and examples
  - Registration process and manifest structure
  - Role management and assignment
  - Scoped RBAC authorization
  - API endpoint reference
  - Integration patterns for other services
  - Security considerations

### Configuration Files
- **permissions.yaml** - Example manifest for security service itself
  - Declares security:role:*, security:permission:*, security:user:* permissions

## Next Steps

### For Origin Story Issue #42
1. Update issue body with these clarification resolutions
2. Remove `blocked:clarification` label
3. Add `status:ready-for-dev` label
4. Link to this implementation PR
5. Update acceptance criteria to reflect scope split

### For Domain Integration Stories (NEW)
Create separate stories for each domain to:
1. Create `permissions.yaml` manifest declaring domain permissions
2. Implement permission registration on service startup
3. Define business-specific roles
4. Assign permissions to roles
5. Add authorization checks to domain endpoints

**Example Domain Stories:**
- [domain:pricing] Define pricing permissions and roles
- [domain:product] Define product/catalog permissions and roles
- [domain:inventory] Define inventory permissions and roles

### Testing (Requires Java 21 runtime)
- Unit tests for Permission validation logic
- Unit tests for RoleAssignment scope logic
- Integration tests for permission registration flow
- Integration tests for authorization checks
- Contract tests for API endpoints

## Architecture Compliance

This implementation follows the clarification decisions exactly:

✅ **Split foundational security from domain integrations**  
✅ **Enforces `domain:resource:action` naming globally**  
✅ **Implements declarative manifest + central registry**  
✅ **Provides role framework, defers business role definitions**  
✅ **Uses scoped RBAC, not token claims or ABAC**

The foundational security framework is now complete and ready for domain services to integrate.
