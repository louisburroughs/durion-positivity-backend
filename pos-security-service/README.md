# POS Security Service

## Overview

The POS Security Service implements the foundational security framework for the Positivity POS system, providing:

- **Central Permission Registry** - Single source of truth for all system permissions
- **Role Management** - Create roles and assign permissions
- **Scoped RBAC** - Role-based access control with location scope support
- **User Role Assignments** - Assign roles to users with effective dating
- **Authorization API** - Check user permissions with scope validation
- **Break-Glass Access** - Emergency elevated access with full audit trail
- **Time-Bound Roles** - Automatic expiration of temporary role assignments

This implementation is based on the **clarification resolutions for Issue #2 (Clarification #42)**, which established the authoritative RBAC policy for the system.

## Architecture Decisions

### Permission Naming Convention

All permissions must follow: `domain:resource:action`

**Examples:**

- `pricing:price_book:edit`
- `inventory:adjustment:approve`
- `security:role:assign`

### Permission Registration

Services declare permissions in a `permissions.yaml` manifest and register them via API during deployment.

### Scoped RBAC

Role assignments support:

- **GLOBAL** scope - Applies to all resources
- **LOCATION** scope - Applies only to specified location IDs

### Story Split

1. **Foundational Security (this service)** - Permission registry, role framework, authorization
2. **Domain Integrations** - Each domain service declares its permissions and defines business roles

## Key Components

### Entities

#### Permission

```java
@Entity
public class Permission {
    private String name;              // e.g., "pricing:price_book:edit"
    private String description;
    private String domain;            // parsed from name
    private String resource;          // parsed from name
    private String action;            // parsed from name
    private String registeredByService;
    private Instant registeredAt;
}
```

#### Role

```java
@Entity
public class Role {
    private String name;
    private String description;
    private Set<Permission> permissions;  // Many-to-many
    private Instant createdAt;
    private String createdBy;
}
```

#### RoleAssignment

```java
@Entity
public class RoleAssignment {
    private User user;
    private Role role;
    private ScopeType scopeType;          // GLOBAL or LOCATION
    private Set<String> scopeLocationIds; // for LOCATION scope
    private LocalDate effectiveStartDate;
    private LocalDate effectiveEndDate;
    private String createdBy;
}
```

### Services

#### PermissionRegistryService

- Validates and registers permissions from service manifests
- Enforces naming convention with regex validation
- Provides query methods for permissions

#### RoleManagementService

- Creates roles and assigns permissions
- Creates and revokes role assignments
- Checks user permissions with scope validation
- Returns effective permissions for users

### REST API

#### Permission Registry Endpoints

- `POST /api/permissions/register` - Register service permissions
- `GET /api/permissions` - Get all permissions (admin)
- `GET /api/permissions/domain/{domain}` - Get permissions by domain
- `GET /api/permissions/validate/{name}` - Validate permission format
- `GET /api/permissions/exists/{name}` - Check if permission exists

#### Role Management Endpoints

- `POST /api/roles` - Create role (admin)
- `GET /api/roles` - List all roles
- `GET /api/roles/{name}` - Get role by name
- `PUT /api/roles/permissions` - Update role permissions (admin)
- `POST /api/roles/assignments` - Create role assignment
- `GET /api/roles/assignments/user/{userId}` - Get user assignments
- `GET /api/roles/permissions/user/{userId}` - Get user permissions
- `GET /api/roles/check-permission` - Check permission (with scope)
- `DELETE /api/roles/assignments/{id}` - Revoke assignment

## Usage

### For Domain Services

#### 1. Create Permission Manifest

Create `src/main/resources/permissions.yaml`:

```yaml
domain: pricing
serviceName: pos-price-service
version: "1.0"
permissions:
  - name: pricing:price_book:view
    description: View price books and pricing rules
  - name: pricing:price_book:edit
    description: Edit existing price books
  - name: pricing:price_book:publish
    description: Publish price books to make them active
```

#### 2. Register Permissions on Startup

```java
@Component
@RequiredArgsConstructor
public class PermissionRegistrationInitializer {
    private final RestTemplate restTemplate;
    
    @PostConstruct
    public void registerPermissions() {
        PermissionRegistrationRequest request = loadFromYaml();
        
        restTemplate.postForEntity(
            "https://security-service/api/permissions/register",
            request,
            PermissionRegistrationResponse.class
        );
    }
}
```

#### 3. Protect Endpoints

```java
@RestController
@RequestMapping("/api/prices")
public class PriceController {
    
    @PreAuthorize("hasAuthority('pricing:price_book:edit')")
    @PutMapping("/{id}")
    public ResponseEntity<PriceBook> update(@PathVariable Long id, 
                                           @RequestBody PriceBookRequest req) {
        // Implementation
    }
}
```

### For Administrators

#### Create a Role

```bash
curl -X POST https://security-service/api/roles \
  -H "Content-Type: application/json" \
  -d '{
    "name": "PricingAnalyst",
    "description": "Can view and edit pricing data"
  }'
```

#### Assign Permissions to Role

```bash
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

#### Assign Role to User (Global Scope)

```bash
curl -X POST https://security-service/api/roles/assignments \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 123,
    "roleId": 1,
    "scopeType": "GLOBAL",
    "effectiveStartDate": "2026-01-01"
  }'
```

#### Assign Role to User (Location Scope)

```bash
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

## Authorization Flow

1. Service registers permissions during deployment
2. Administrator creates roles and assigns permissions to them
3. Administrator assigns roles to users with optional location scope
4. When checking authorization:
   - System retrieves user's effective role assignments
   - Filters by effective dates (start/end)
   - For each assignment, checks if scope covers target location
   - Checks if role has the required permission
   - Returns true if any matching assignment grants the permission

## Database Schema

### Tables

- `permissions` - Central registry of all permissions
- `roles` - Role definitions
- `role_permissions` - Many-to-many join table
- `role_assignments` - User role assignments with scope
- `role_assignment_scope_locations` - Location IDs for scoped assignments
- `users` - User accounts (existing)
- `user_roles` - Legacy many-to-many (to be migrated)

## Security Considerations

1. **Permission names are immutable** - Once registered, cannot be changed
2. **Least privilege** - Only grant necessary permissions
3. **Audit trail** - All operations track who/when
4. **No token claims** - Never encode permissions in JWT tokens
5. **Scope validation** - Always validate location for sensitive operations

## Testing

Requires Java 21 runtime.

```bash
# Run unit tests
mvn test

# Run with integration tests
mvn verify
```

## Documentation

### Core Documentation

- **[RBAC_POLICY.md](../../durion/domains/security/docs/RBAC_POLICY.md)** - **START HERE** - Authoritative access control policy
  - Answers to clarification questions (Issue #2/Clarification #42)
  - Permission registry decisions and baseline operations
  - Flat role model (no inheritance)
  - HR integration approach (identity vs authorization)
  - Location-scoped permission model
  - Time-bound roles and break-glass access
  
- **[PERMISSION_REGISTRY.md](../../durion/domains/security/docs/PERMISSION_REGISTRY.md)** - Technical permission management
  - Permission naming rules and validation
  - Registration process and API
  - Role management patterns
  - Authorization check examples
  - Domain service integration patterns
  
- **[BASELINE_PERMISSIONS.md](../../durion/domains/security/docs/BASELINE_PERMISSIONS.md)** - Reference permission sets
  - Core system permissions by domain
  - Risk level classifications
  - Usage guidelines for domain services
  - Example `permissions.yaml` manifests
  
- **[POLICY_ENGINE_DESIGN.md](../../durion/domains/security/docs/POLICY_ENGINE_DESIGN.md)** - Threshold enforcement
  - Policy-based authorization (beyond simple permissions)
  - Threshold handling without permission explosion
  - Policy versioning and audit trail
  - Implementation roadmap
  
- **[BREAK_GLASS_PATTERN.md](../../durion/domains/security/docs/BREAK_GLASS_PATTERN.md)** - Emergency access
  - Break-glass workflow and API
  - Configuration and TTL management
  - Audit events and monitoring
  - Post-incident review process

## Next Steps

This is the foundational framework. Domain services should now:

1. Create their `permissions.yaml` manifests
2. Implement permission registration on startup
3. Define business-specific roles
4. Protect their endpoints with permission checks

Example follow-up stories:

- [domain:pricing] Define pricing permissions and roles
- [domain:product] Define product/catalog permissions and roles
- [domain:inventory] Define inventory permissions and roles

## Related Issues

- **Origin Story:** Issue #2 - [BACKEND] [STORY] Permission Management: Define POS Roles and Permission Matrix
- **Clarification Issue:** Issue #42 (Origin #2) - Resolved with definitive policy decisions
- **Related Issues:**
  - Issue #37 - Inventory adjustment permissions (implemented)
  - Issue #30 - Cycle count permissions (implemented)

## Clarification Decisions (Issue #42)

This implementation reflects the following **authoritative decisions**:

1. **Permission Granularity:** Operation-specific permissions with policy engine for thresholds (not separate permissions per threshold)
2. **Role Model:** Flat roles with no inheritance; permissions are additive
3. **HR Integration:** HR is authoritative for identity; POS owns authorization
4. **Permission Scope:** Explicit location scoping; no implicit cross-location access
5. **Time-Bound Roles:** All role assignments support effective dates and automatic expiration
6. **Break-Glass:** First-class emergency access pattern with mandatory audit

See [RBAC_POLICY.md](../../durion/domains/security/docs/RBAC_POLICY.md) for complete policy documentation.

## Architecture Compliance

✅ Split foundational security from domain integrations  
✅ Enforces `domain:resource:action` naming globally  
✅ Implements declarative manifest + central registry  
✅ Provides role framework, defers business role definitions  
✅ Uses scoped RBAC, not token claims or ABAC
