# CRM Permission Enforcement Implementation Summary

## Overview
Successfully implemented backend permission checks in the `pos-customer` service using Spring Security's `@PreAuthorize` annotations integrated with the centralized CRM Permission Registry.

---

## Files Created

### 1. CrmSecurityConfig.java
**Location:** `pos-customer/src/main/java/com/positivity/customer/config/CrmSecurityConfig.java`

**Purpose:** Configure Spring Security with method-level security enabled.

**Key Features:**
- `@EnableMethodSecurity(prePostEnabled = true)` enables `@PreAuthorize` annotations
- Stateless session management (JWT-based)
- Public endpoints: actuator health, Swagger, service discovery
- All `/v1/crm/**` endpoints require authentication
- Permission checks enforced at method level via `@PreAuthorize`

**Spring Beans:**
- `SecurityFilterChain` - HTTP security configuration
- `PasswordEncoder` - BCrypt password encoder

---

## Files Modified

### 1. CustomerController.java
**Location:** `pos-customer/src/main/java/com/positivity/customer/controller/CustomerController.java`

**Permissions Added:**

| Method | Endpoint | Permission | CRUD Operation |
|--------|----------|-----------|-----------------|
| `getAllCustomers()` | GET /v1/crm | `crm:party:view` | Read (All) |
| `getCustomerById()` | GET /v1/crm/{id} | `crm:party:view` | Read (Single) |
| `createCustomer()` | POST /v1/crm | `crm:party:create` | Create |
| `updateCustomer()` | PUT /v1/crm/{id} | `crm:party:edit` | Update |
| `deleteCustomer()` | DELETE /v1/crm/{id} | `crm:party:deactivate` | Delete |

**Changes:**
- Added import: `com.positivity.customer.security.CrmPermissionRegistry`
- Added import: `org.springframework.security.access.prepost.PreAuthorize`
- Added `@PreAuthorize` annotation to each method with appropriate permission constant

---

### 2. CrmAccountsController.java
**Location:** `pos-customer/src/main/java/com/positivity/customer/controller/CrmAccountsController.java`

**Permissions Added:**

| Method | Endpoint | Permission | Operation |
|--------|----------|-----------|-----------|
| `getAccountTier()` | GET /v1/crm/accounts/{accountId}/tier | `crm:party:view` | Read |
| `resolveAccountTiers()` | POST /v1/crm/accounts/tierResolve | `crm:party:view` | Read |

**Changes:**
- Added import: `com.positivity.customer.security.CrmPermissionRegistry`
- Added import: `org.springframework.security.access.prepost.PreAuthorize`
- Added `@PreAuthorize` annotation to each method with appropriate permission constant

---

## Permission Constants Used

All permissions use type-safe constants from `CrmPermissionRegistry`:

```java
// Party/Customer Permissions
CrmPermissionRegistry.PARTY_VIEW        // crm:party:view
CrmPermissionRegistry.PARTY_CREATE      // crm:party:create
CrmPermissionRegistry.PARTY_EDIT        // crm:party:edit
CrmPermissionRegistry.PARTY_DEACTIVATE  // crm:party:deactivate
```

These constants are defined in:
- **File:** `pos-customer/src/main/java/com/positivity/customer/security/CrmPermissionRegistry.java`
- **Purpose:** Single source of truth for all 27 CRM permissions
- **Usage:** Prevents typos in `@PreAuthorize` expressions

---

## Security Architecture

### Permission Flow

1. **Request arrives at CRM endpoint**
   - Example: `GET /v1/crm` (getAllCustomers)

2. **SecurityFilterChain processes request**
   - Verifies authentication (JWT token)
   - Routes to dispatcher servlet

3. **Spring Security evaluates @PreAuthorize**
   - Annotation: `@PreAuthorize("hasAuthority('crm:party:view')")`
   - Checks if authenticated user has permission authority

4. **Permission Authority Resolution**
   - User authorities come from JWT token
   - Authorities contain permission strings like `crm:party:view`
   - Supplied by pos-security-service during authentication

5. **Method executes or access denied**
   - Success: 200 OK with resource
   - Denied: 403 Forbidden

### Role → Permission Mapping

To simplify authorization, business roles are expanded to fine-grained CRM permission authorities. The mapping is defined in:

- **File:** `pos-customer/src/main/java/com/positivity/customer/security/CrmRolePermissionMapping.java`
- **Roles:** CSR, FLEET_MANAGER, ADMIN
- **Behavior:**
   - CSR: party view/search; contact view/create/edit; contact role view/assign; communication preference view/edit; vehicle view/search; vehicle-party association view; processing log/suspense view
   - FLEET_MANAGER: CSR set plus party edit; vehicle create/edit; vehicle-party association create/edit; vehicle preferences view/edit
   - ADMIN: all CRM permissions including high-risk operations (deactivate, merge)

Authorities derived from roles must be present in the JWT or injected by the security gateway. Endpoint-level checks continue to use `hasAuthority('crm:...')` semantics.

### Permission Registration

Permissions are automatically registered when `pos-customer` service starts:

1. **CrmPermissionInitializer** (`@Configuration` bean)
   - Runs as `ApplicationRunner` on startup
   - Calls `CrmPermissionRegistry.buildCrmPermissionRegistration()`
   - POSTs registration payload to `pos-security-service` at `/v1/permissions/register`
   - Handles registration failures gracefully (non-blocking)

2. **pos-security-service** stores permissions
   - Validates format: `domain:resource:action` (all lowercase)
   - Creates/updates in permission database
   - Returns registration response to caller

3. **JWT token issuance**
   - When user authenticates, pos-security-service issues JWT
   - JWT includes user authorities/permissions
   - User makes subsequent requests with JWT
   - Spring Security extracts authorities from JWT

---

## Testing the Implementation

### Verify Permission Registration
```bash
# Check if permissions were registered (during pos-customer startup)
# Look for logs from CrmPermissionInitializer

# Example expected log output:
# INFO: Registering CRM permissions with Security Domain...
# INFO: CRM permissions registered successfully
```

### Test Permission Enforcement

**1. Without Authentication (should fail)**
```bash
curl -X GET http://localhost:8082/v1/crm
# Response: 401 Unauthorized
```

**2. With Authentication but insufficient permissions (should fail)**
```bash
# Assuming user has 'crm:party:create' but not 'crm:party:view'
curl -H "Authorization: Bearer <jwt-token-with-create-only>" \
     -X GET http://localhost:8082/v1/crm
# Response: 403 Forbidden
```

**3. With appropriate permission (should succeed)**
```bash
# Assuming user has 'crm:party:view' permission
curl -H "Authorization: Bearer <jwt-token-with-view>" \
     -X GET http://localhost:8082/v1/crm
# Response: 200 OK with list of customers
```

---

## Configuration Required

Update `application.yaml` in `pos-customer` to specify Security Service location:

```yaml
security:
  service:
    url: ${SECURITY_SERVICE_URL:http://localhost:8086}
    
app:
  name: pos-customer
  version: 1.0.0
```

Environment variable for non-local deployments:
```bash
export SECURITY_SERVICE_URL=http://pos-security-service:8086
```

---

## Integration with CRM Domain Taxonomy

This implementation enforces the 27 CRM permissions defined in:
- **ADR 0002:** [/durion/docs/adr/0002-crm-permission-taxonomy.md](../../adr/0002-crm-permission-taxonomy.md)
- **Taxonomy Document:** [/durion/domains/crm/CRM_PERMISSION_TAXONOMY.md](../../domains/crm/CRM_PERMISSION_TAXONOMY.md)

**Permission Categories Implemented:**
- **Party Permissions (6):** VIEW, CREATE, EDIT, DEACTIVATE, MERGE, EXPORT
- **Contact Permissions (8):** VIEW, CREATE, ROLE_ASSIGN, REMOVE, EMERGENCY_CONTACT_EDIT, PRIVACY_UPDATE, ESCALATION_OVERRIDE, EXPORT
- **Vehicle Permissions (10):** Plus integration, processing, and account tier permissions
- **Other:** 3 integration monitoring permissions

---

## Next Steps

### Phase 2: Comprehensive Endpoint Coverage
- [ ] Update all remaining contact endpoints with permission checks
- [ ] Update all vehicle management endpoints
- [ ] Add permission checks to integration monitoring endpoints
- [ ] Implement permission checks in service layer (not just controllers)

### Phase 3: Centralized Error Handling
- [ ] Create `@ControllerAdvice` for permission denied exceptions
- [ ] Return consistent 403 response format with error details
- [ ] Log permission denied events for audit trail

### Phase 4: Testing & Validation
- [ ] Create integration tests for permission enforcement
- [ ] Add test data with various permission combinations
- [ ] Validate permission registration on service startup
- [ ] Performance test permission checking overhead

### Phase 5: Monitoring & Observability
- [ ] Add metrics for permission denied events
- [ ] Log permission checks for audit trail
- [ ] Create dashboard for permission usage patterns
- [ ] Alert on suspicious permission access patterns

---

## Related Documentation

- **ADR 0002:** [CRM Permission Taxonomy Decision Record](../../adr/0002-crm-permission-taxonomy.md)
- **CRM Taxonomy:** [CRM Permission Taxonomy Definition](../../domains/crm/CRM_PERMISSION_TAXONOMY.md)
- **Approval Summary:** [CRM Permission Implementation Approval](../../domains/crm/APPROVAL_SUBMISSION.md)
- **Integration Guide:** [CRM Priority 1 Completion Guide](../../domains/crm/PRIORITY_1_COMPLETION.md)
- **Security Domain Inventory:** [Inventory Permissions Reference](../../domains/security/docs/INVENTORY_PERMISSIONS.md)

---

## Implementation Status

✅ **Completed:**
- CrmSecurityConfig.java - Spring Security configuration with method-level security
- CustomerController - All 5 endpoints protected with permission checks
- CrmAccountsController - Both endpoints protected with permission checks
- CrmPermissionRegistry.java - 27 permission constants defined
- CrmPermissionInitializer.java - Automatic permission registration on startup

🔄 **In Progress:**
- Backend permission enforcement infrastructure complete
- Ready for integration testing
- Waiting on remaining endpoint coverage

⏳ **Pending:**
- Comprehensive endpoint coverage for all CRM resources
- Centralized error handling for permission denied scenarios
- Integration tests for permission validation
- Monitoring and observability integration
- Documentation updates to API reference

---

## Security Considerations

1. **Type-Safe Permission Strings:** Using constants from `CrmPermissionRegistry` prevents typos in permission checks
2. **Centralized Permission Management:** All permissions defined in one place, reducing duplication
3. **Automatic Registration:** `CrmPermissionInitializer` ensures permissions are always in sync
4. **Graceful Degradation:** Non-blocking registration allows service startup even if Security Domain is unavailable (logs warning)
5. **Stateless Sessions:** JWT-based authentication scales horizontally without session replication
6. **Method-Level Security:** Fine-grained control over who can access what operations

### Centralized 403 Handling
- **AccessDeniedException → 403:** `CrmExceptionHandler` (`@ControllerAdvice`) standardizes responses for permission-denied cases.
- **Response shape:** `{ errorCode: "PERMISSION_DENIED", message, path, timestamp }`
- **Audit-friendly:** Logs a warning including request path; consistent payload for UI handling.

---

Generated: 2024
Implements: ADR 0002 - CRM Permission Taxonomy
