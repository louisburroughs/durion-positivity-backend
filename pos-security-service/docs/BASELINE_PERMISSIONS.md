# Baseline Permissions Manifest

> **Purpose:** Core system permissions for foundational business operations  
> **Domain:** Cross-cutting (Security + Core Domains)  
> **Version:** 1.0  
> **Status:** Reference Implementation  
> **Date:** 2026-01-13

This manifest defines the **initial set of business-risk operations** requiring permission controls. Domain services should register their specific permissions following this pattern.

---

## Security Domain Permissions

### Permission Registration

```yaml
domain: security
serviceName: pos-security-service
version: "1.0"

permissions:
  # Role Management
  - name: security:role:view
    description: View role definitions and assignments
    
  - name: security:role:create
    description: Create new roles
    
  - name: security:role:edit
    description: Edit existing role definitions
    
  - name: security:role:delete
    description: Delete roles
    
  - name: security:role:assign
    description: Assign roles to users
    riskLevel: CRITICAL
    
  # User Management
  - name: security:user:view
    description: View user accounts and details
    
  - name: security:user:create
    description: Create new user accounts
    
  - name: security:user:edit
    description: Edit user account details
    
  - name: security:user:disable
    description: Disable user accounts
    riskLevel: HIGH
    
  - name: security:user:reset_password
    description: Reset user passwords
    riskLevel: HIGH
    
  # Audit and Monitoring
  - name: security:audit_log:view
    description: View system audit logs
    riskLevel: MEDIUM
    
  - name: security:break_glass:access
    description: Request emergency elevated access
    riskLevel: CRITICAL
```

---

## Financial Domain Permissions

### Permission Registration

```yaml
domain: financial
serviceName: pos-finance-service
version: "1.0"

permissions:
  # Price Override
  - name: financial:price_override:apply
    description: Apply price overrides to line items
    riskLevel: HIGH
    notes: Subject to policy engine threshold checks
    
  - name: financial:price_override:approve
    description: Approve price override requests
    riskLevel: HIGH
    notes: May require higher authority for large overrides
    
  # Refunds
  - name: financial:refund:issue
    description: Issue refunds to customers
    riskLevel: HIGH
    
  - name: financial:refund:approve
    description: Approve refund requests
    riskLevel: CRITICAL
    notes: Threshold-based approval via policy engine
    
  # Invoice Management
  - name: financial:invoice:view
    description: View invoices
    riskLevel: LOW
    
  - name: financial:invoice:create
    description: Create invoices
    riskLevel: MEDIUM
    
  - name: financial:invoice:cancel
    description: Cancel issued invoices
    riskLevel: HIGH
    
  # Payment Processing
  - name: financial:payment:process
    description: Process customer payments
    riskLevel: MEDIUM
    
  - name: financial:payment:void
    description: Void recorded payments
    riskLevel: CRITICAL
    
  - name: financial:payment:refund
    description: Refund payments to customers
    riskLevel: HIGH
    
  # Credit Memos
  - name: financial:credit_memo:apply
    description: Apply credit memos to customer accounts
    riskLevel: MEDIUM
```

---

## Inventory Domain Permissions

### Permission Registration

```yaml
domain: inventory
serviceName: pos-inventory-service
version: "1.0"

permissions:
  # Inventory Viewing
  - name: inventory:stock:view
    description: View inventory stock levels and details
    riskLevel: LOW
    
  # Inventory Adjustments
  - name: inventory:adjustment:create
    description: Create inventory adjustment requests
    riskLevel: MEDIUM
    
  - name: inventory:adjustment:approve
    description: Approve and post inventory adjustments
    riskLevel: HIGH
    notes: Subject to quantity/value thresholds
    
  - name: inventory:adjustment:cancel
    description: Cancel pending adjustment requests
    riskLevel: MEDIUM
    
  # Inventory Transfers
  - name: inventory:transfer:create
    description: Create inventory transfer requests between locations
    riskLevel: MEDIUM
    
  - name: inventory:transfer:approve
    description: Approve inventory transfers
    riskLevel: HIGH
    
  - name: inventory:transfer:receive
    description: Receive transferred inventory at destination
    riskLevel: MEDIUM
    
  # Cycle Counts
  - name: inventory:cycle_count:create
    description: Create cycle count tasks
    riskLevel: LOW
    
  - name: inventory:cycle_count:record
    description: Record cycle count results
    riskLevel: MEDIUM
    
  - name: inventory:cycle_count:reconcile
    description: Reconcile and post cycle count variances
    riskLevel: HIGH
```

---

## Work Execution Domain Permissions

### Permission Registration

```yaml
domain: workexec
serviceName: pos-work-order-service
version: "1.0"

permissions:
  # Work Order Management
  - name: workexec:workorder:view
    description: View work orders
    riskLevel: LOW
    
  - name: workexec:workorder:create
    description: Create new work orders
    riskLevel: MEDIUM
    
  - name: workexec:workorder:edit
    description: Edit existing work orders
    riskLevel: MEDIUM
    
  - name: workexec:workorder:cancel
    description: Cancel work orders
    riskLevel: HIGH
    
  - name: workexec:workorder:close
    description: Close completed work orders
    riskLevel: MEDIUM
    
  # Mechanic Assignment
  - name: workexec:mechanic:assign
    description: Assign mechanics to work orders
    riskLevel: MEDIUM
    
  - name: workexec:mechanic:reassign
    description: Reassign work orders to different mechanics
    riskLevel: MEDIUM
    
  # Scheduling
  - name: workexec:schedule:view
    description: View work order schedules
    riskLevel: LOW
    
  - name: workexec:schedule:edit
    description: Edit work order schedules
    riskLevel: MEDIUM
    
  - name: workexec:schedule:override
    description: Override scheduling constraints
    riskLevel: HIGH
    
  # Time Entry
  - name: workexec:time_entry:record
    description: Record time entries for work performed
    riskLevel: LOW
    
  - name: workexec:time_entry:edit
    description: Edit time entries
    riskLevel: HIGH
    notes: Affects payroll and billing
    
  - name: workexec:time_entry:clock_in_override
    description: Override clock-in restrictions (e.g., early clock-in)
    riskLevel: HIGH
    
  # Vehicle Ownership
  - name: workexec:vehicle_ownership:transfer
    description: Transfer vehicle ownership records
    riskLevel: CRITICAL
```

---

## Product & Catalog Domain Permissions

### Permission Registration

```yaml
domain: product
serviceName: pos-catalog-service
version: "1.0"

permissions:
  # Product Viewing
  - name: product:catalog:view
    description: View product catalog
    riskLevel: LOW
    
  # Product Management
  - name: product:product:create
    description: Create new products
    riskLevel: MEDIUM
    
  - name: product:product:edit
    description: Edit product details
    riskLevel: MEDIUM
    
  - name: product:product:delete
    description: Delete products
    riskLevel: HIGH
    
  - name: product:product:discontinue
    description: Mark products as discontinued
    riskLevel: MEDIUM
    
  # Catalog Publishing
  - name: product:catalog:publish
    description: Publish catalog changes to make them live
    riskLevel: HIGH
    
  # Fitment Management
  - name: product:fitment:edit
    description: Edit vehicle fitment rules
    riskLevel: MEDIUM
    
  - name: product:fitment:approve
    description: Approve fitment rule changes
    riskLevel: HIGH
```

---

## Pricing Domain Permissions

### Permission Registration

```yaml
domain: pricing
serviceName: pos-price-service
version: "1.0"

permissions:
  # Price Book Management
  - name: pricing:price_book:view
    description: View price books and pricing rules
    riskLevel: LOW
    
  - name: pricing:price_book:create
    description: Create new price books
    riskLevel: MEDIUM
    
  - name: pricing:price_book:edit
    description: Edit existing price books
    riskLevel: HIGH
    
  - name: pricing:price_book:delete
    description: Delete price books
    riskLevel: HIGH
    
  - name: pricing:price_book:publish
    description: Publish price books to make them active
    riskLevel: CRITICAL
    
  # Fee Management
  - name: pricing:fee:create
    description: Create fee schedules
    riskLevel: MEDIUM
    
  - name: pricing:fee:edit
    description: Edit fee schedules
    riskLevel: HIGH
    
  - name: pricing:fee:waive
    description: Waive fees for customers
    riskLevel: HIGH
```

---

## Customer Domain Permissions

### Permission Registration

```yaml
domain: customer
serviceName: pos-customer-service
version: "1.0"

permissions:
  # Customer Management
  - name: customer:profile:view
    description: View customer profiles
    riskLevel: LOW
    
  - name: customer:profile:create
    description: Create new customer profiles
    riskLevel: MEDIUM
    
  - name: customer:profile:edit
    description: Edit customer profile details
    riskLevel: MEDIUM
    
  - name: customer:profile:merge
    description: Merge duplicate customer profiles
    riskLevel: HIGH
    
  # Fleet Management
  - name: customer:fleet:view
    description: View fleet information
    riskLevel: LOW
    
  - name: customer:fleet:edit
    description: Edit fleet details
    riskLevel: MEDIUM
    
  # Credit Management
  - name: customer:credit:view
    description: View customer credit terms
    riskLevel: MEDIUM
    
  - name: customer:credit:edit
    description: Edit customer credit terms
    riskLevel: HIGH
```

---

## Location Domain Permissions

### Permission Registration

```yaml
domain: location
serviceName: pos-location-service
version: "1.0"

permissions:
  # Location Management
  - name: location:shop:view
    description: View shop/location details
    riskLevel: LOW
    
  - name: location:shop:create
    description: Create new locations
    riskLevel: HIGH
    
  - name: location:shop:edit
    description: Edit location details
    riskLevel: MEDIUM
    
  - name: location:shop:close
    description: Close/deactivate locations
    riskLevel: CRITICAL
    
  # Bay Management
  - name: location:bay:edit
    description: Edit service bay configurations
    riskLevel: MEDIUM
```

---

## People Domain Permissions

### Permission Registration

```yaml
domain: people
serviceName: pos-people-service
version: "1.0"

permissions:
  # Employee Management
  - name: people:employee:view
    description: View employee information
    riskLevel: LOW
    
  - name: people:employee:create
    description: Create employee records
    riskLevel: MEDIUM
    
  - name: people:employee:edit
    description: Edit employee information
    riskLevel: MEDIUM
    
  - name: people:employee:terminate
    description: Terminate employee records
    riskLevel: HIGH
    
  # Skill Management
  - name: people:skill:assign
    description: Assign skills to employees
    riskLevel: MEDIUM
```

---

## Usage Guidelines

### For Domain Services

1. **Copy the relevant section** for your domain
2. **Customize permissions** based on your specific operations
3. **Add to `permissions.yaml`** in your service's resources
4. **Register on startup** using the Permission Registry API

### Example: Registering Permissions

```java
@Component
@RequiredArgsConstructor
public class PermissionRegistrationInitializer {
    private final RestTemplate restTemplate;
    private final ResourceLoader resourceLoader;
    
    @PostConstruct
    public void registerPermissions() throws IOException {
        Resource resource = resourceLoader.getResource("classpath:permissions.yaml");
        PermissionRegistrationRequest request = 
            new Yaml().load(resource.getInputStream());
        
        restTemplate.postForEntity(
            "http://security-service/api/permissions/register",
            request,
            PermissionRegistrationResponse.class
        );
    }
}
```

---

## Permission Naming Rules

All permissions must follow: `domain:resource:action`

### Rules

1. **Lowercase only** - No uppercase characters
2. **Singular nouns** - Use singular form for resources
3. **Clear action verbs** - view, create, edit, delete, approve, cancel, etc.
4. **Alphanumeric + underscore** - Only use `a-z`, `0-9`, and `_`

### Valid Examples

✅ `financial:refund:approve`  
✅ `inventory:adjustment:create`  
✅ `security:role:assign`  
✅ `workexec:time_entry:edit`

### Invalid Examples

❌ `Financial:Refund:Approve` - Contains uppercase  
❌ `financial-refund-approve` - Wrong separator  
❌ `financial:approve` - Missing resource  
❌ `financial:refunds:approve` - Plural noun

---

## Risk Levels

Permissions are categorized by business risk:

| Risk Level | Description | Examples |
|------------|-------------|----------|
| **LOW** | Read-only, no financial impact | View operations |
| **MEDIUM** | Write operations, moderate impact | Create, edit operations |
| **HIGH** | Operations affecting money or audit | Approve, void, override |
| **CRITICAL** | Highest-risk operations | Role assignment, break-glass, large financial |

Risk levels inform:
- Audit log retention (critical = 7 years)
- Alert thresholds
- Policy engine constraints

---

## References

- **Permission Registry Documentation:** `docs/PERMISSION_REGISTRY.md`
- **RBAC Policy:** `docs/RBAC_POLICY.md`
- **Policy Engine:** `docs/POLICY_ENGINE_DESIGN.md`
- **Origin:** Clarification Issue #42 - Permission registry decisions
