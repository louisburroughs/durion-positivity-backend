# Permission Registration Strategy

## Short Answer

**Services should register their own permissions via API during startup** (what you're already doing with `CrmPermissionInitializer`).

This is based on **DECISION-INVENTORY-006** from the Security Domain business rules: **"Permission registry is code-first (UI read-only)"**

## Architectural Decisions

### DECISION-INVENTORY-006: Code-First Permission Registry

From [durion/domains/security/.business-rules/AGENT_GUIDE.md](../domains/security/.business-rules/AGENT_GUIDE.md):

> **Permission registry is code-first**: Permissions are defined by the code/service that owns them, not configured in security service properties or admin UI.

**Implications:**

1. **Services declare** their permissions in code (typically via registry classes)
2. **Services register** permissions via API during deployment/startup
3. **Security service stores** permissions in database (read-only from UI perspective)
4. **No admin UI permission creation** - permissions come from service code

### Why This Pattern?

| Reason | Benefit |
|--------|---------|
| **Source of Truth** | Permissions live next to the code that enforces them |
| **Deployment Safety** | Services don't start until permissions are registered |
| **Version Control** | Permission taxonomy is tracked with code changes |
| **Auditability** | Know exactly which service registered each permission |
| **Prevents Orphans** | Unregistered permissions can't grant access accidentally |

## Implementation Pattern

### Step 1: Define Permissions in Code

Create a registry class (example from pos-customer):

```java
// src/main/java/com/positivity/customer/internal/security/CrmPermissionRegistry.java
public class CrmPermissionRegistry {
    
    public static PermissionRegistrationRequest buildCrmPermissionRegistration() {
        return PermissionRegistrationRequest.builder()
            .domain("crm")
            .serviceName("pos-customer")
            .permissions(Arrays.asList(
                Permission.builder()
                    .name("crm:party:view")
                    .description("View customer party records")
                    .build(),
                Permission.builder()
                    .name("crm:party:create")
                    .description("Create new customer party records")
                    .build(),
                // ... more permissions
            ))
            .build();
    }
}
```

### Step 2: Register During Startup

Use an `@Bean` implementing `ApplicationRunner` to register on startup:

```java
// CrmPermissionInitializer.java
@Slf4j
@Configuration
public class CrmPermissionInitializer {

    @Value("${gateway.url:http://localhost:8080}")
    private String gatewayUrl;

    @Bean
    public ApplicationRunner registerCrmPermissions(RestClient restClient) {
        return args -> {
            try {
                log.info("Starting CRM permission registration...");

                var request = CrmPermissionRegistry.buildCrmPermissionRegistration();

                var response = restClient
                        .post()
                        .uri(gatewayUrl + "/security-service/v1/permissions/register")
                        .header("X-API-Version", "1")
                        .body(request)
                        .retrieve()
                        .toEntity(Void.class);

                if (response.getStatusCode().is2xxSuccessful()) {
                    log.info("✓ CRM permissions registered successfully");
                } else {
                    log.warn("⚠ Unexpected response: {}", response.getStatusCode());
                }
            } catch (Exception e) {
                log.warn("⚠ Failed to register permissions (non-blocking): {}", e.getMessage());
                // Non-blocking: startup continues even if registration fails
                // Retries happen on next deployment
            }
        };
    }

    @Bean
    public RestClient restClient() {
        return RestClient.create();
    }
}
```

### Step 3: Enforce Authorization

Spring Security will use registered permissions:

```java
@RestController
@RequestMapping("/v1/crm")
public class PartyController {
    
    @GetMapping("/parties")
    @PreAuthorize("hasAuthority('crm:party:view')")
    public List<Party> listParties() {
        // ...
    }
    
    @PostMapping("/parties")
    @PreAuthorize("hasAuthority('crm:party:create')")
    public Party createParty(@RequestBody PartyRequest request) {
        // ...
    }
}
```

## Permission Naming Convention

All permissions follow: **`domain:resource:action`** (snake_case, lowercase)

**Examples:**

| Domain | Resource | Action | Full Permission |
|--------|----------|--------|-----------------|
| crm | party | view | `crm:party:view` |
| inventory | adjustment | approve | `inventory:adjustment:approve` |
| order | shipment | cancel | `order:shipment:cancel` |
| security | role | assign | `security:role:assign` |

**Validation:**
- No spaces
- All lowercase
- Exactly 3 parts separated by `:`
- Each part: alphanumeric + underscore only

## Service Permission Registration Registry

Document all permissions your service registers:

| Service | Domain | Registered Permissions | File |
|---------|--------|------------------------|------|
| pos-customer | crm | `crm:party:*`, `crm:contact:*`, `crm:vehicle:*` | [CrmPermissionRegistry.java](../../pos-customer/src/main/java/com/positivity/customer/internal/security/CrmPermissionRegistry.java) |
| pos-inventory | inventory | `inventory:adjustment:*`, `inventory:count:*` | (to be defined) |
| pos-order | order | `order:create`, `order:cancel`, `order:ship` | (to be defined) |

## Do NOT Do This

### ❌ Store permissions in application.yml

```yaml
# WRONG - permissions get out of sync with code
security:
  permissions:
    - name: crm:party:view
    - name: crm:party:create
```

### ❌ Manually insert permissions into security service database

```sql
-- WRONG - no audit trail, out of version control
INSERT INTO permission (name, domain, resource, action) 
VALUES ('crm:party:view', 'crm', 'party', 'view');
```

### ❌ Create permissions via admin UI

```
WRONG - breaks deployment automation, version control tracking
```

## Handling Permission Changes

### Adding a New Permission

1. Add to `CrmPermissionRegistry`
2. Deploy service (will re-register on startup)
3. Security service creates new permission (idempotent)
4. Create roles that include the permission
5. Assign roles to users

### Removing a Permission

1. Remove from `CrmPermissionRegistry`
2. First, remove permission from all roles via security service admin API
3. Deploy service (registration skips deleted permission)
4. Security service has no more assignments using it

### Renaming a Permission

1. Create new permission in `CrmPermissionRegistry`
2. Deploy (registers new permission)
3. Migrate role assignments from old → new permission via security API
4. Remove old permission from registry
5. Deploy again

## Testing Permissions

### Verify Registration

```bash
# Check logs during service startup
kubectl logs -f deployment/pos-customer | grep -i "permission"

# Should see:
# INFO: Starting CRM permission registration...
# INFO: ✓ CRM permissions registered successfully
```

### Query Registered Permissions

```bash
# List all permissions
curl -H "X-API-Version: 1" \
     http://localhost:8080/security-service/v1/permissions

# Check specific permission
curl -H "X-API-Version: 1" \
     http://localhost:8080/security-service/v1/permissions/exists/crm:party:view
```

### Verify Authorization

```bash
# Without permission (should be 403)
curl -H "Authorization: Bearer <token-without-permission>" \
     -H "X-API-Version: 1" \
     http://localhost:8080/customer/v1/crm/parties
# Response: 403 Forbidden

# With permission (should be 200)
curl -H "Authorization: Bearer <token-with-permission>" \
     -H "X-API-Version: 1" \
     http://localhost:8080/customer/v1/crm/parties
# Response: 200 OK
```

## Configuration Reference

### Local Development

```yaml
# application-local.yml
gateway:
  url: http://localhost:8080

server:
  port: 0
```

### Docker Compose

```yaml
# compose.yml
services:
  pos-customer:
    environment:
      - GATEWAY_URL=http://pos-api-gateway:8080
```

### Kubernetes

```yaml
# deployment.yaml
env:
  - name: GATEWAY_URL
    value: http://pos-api-gateway.default.svc.cluster.local:8080
```

## Related Documentation

- [Security Domain AGENT_GUIDE.md](../../domains/security/.business-rules/AGENT_GUIDE.md) - Core permission model decisions
- [ADR 0002: CRM Permission Taxonomy](../adr/0002-crm-permission-taxonomy.adr.md) - CRM permission spec
- [Inter-Service Communication](INTER_SERVICE_COMMUNICATION.md) - How to call security service
- [pos-security-service README](../pos-security-service/README.md) - API endpoints and schemas
