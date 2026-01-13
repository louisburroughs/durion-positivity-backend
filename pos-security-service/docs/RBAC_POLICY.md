# Role-Based Access Control (RBAC) Policy

> **Authority:** Security Domain  
> **Status:** Active  
> **Version:** 1.0  
> **Last Updated:** 2026-01-13  
> **Origin:** Clarification Issue #42 (Issue #2 Resolution)

## Purpose

This document defines the **authoritative access control model** for the Positivity POS system. All authorization decisions, role definitions, and permission grants must conform to this policy.

## Core Principles

1. **Explicit Authorization** - All operations requiring business-risk control must have explicit permission checks
2. **Least Privilege** - Users receive only permissions necessary for their job function
3. **Separation of Duties** - High-risk operations (e.g., approve vs create) require different permissions
4. **Auditability** - All permission checks, role assignments, and grants are logged
5. **Additive Permissions** - No deny rules; permissions are additive only
6. **Data-Driven Configuration** - Permissions and roles are data, not code

---

## Question 1: Permission Registry and Granularity

### Decision: Operation-Specific Permissions

Permissions are **operation-specific**, not role-scoped. This means:

- Permission = **capability** (e.g., `APPROVE_REFUND`)
- Role = **bundle of permissions** (e.g., "Manager" has multiple permissions)
- Threshold enforcement = **policy engine**, not permission explosion

### ✅ Correct Approach

```yaml
permissions:
  - name: financial:refund:issue
    description: Issue refunds to customers
  - name: financial:refund:approve
    description: Approve refund requests
```

Then apply **policy rules** for thresholds:

```java
// Policy engine checks threshold dynamically
if (refundAmount > $100 && !hasRole("DistrictManager")) {
    requireApproval();
}
```

### ❌ Wrong Approach (Avoid Permission Explosion)

```yaml
# DON'T create threshold-specific permissions
permissions:
  - name: financial:refund:approve_under_100
  - name: financial:refund:approve_100_to_500
  - name: financial:refund:approve_over_500
```

This approach creates combinatorial growth and maintenance burden.

### Baseline Permission Registry

The following is the **initial set of business-risk operations** requiring permissions:

#### Financial Operations

| Permission | Description | Risk Level |
|------------|-------------|------------|
| `financial:price_override:apply` | Apply price overrides | HIGH |
| `financial:price_override:approve` | Approve price override requests | HIGH |
| `financial:refund:issue` | Issue refunds to customers | HIGH |
| `financial:refund:approve` | Approve refund requests | CRITICAL |
| `financial:invoice:cancel` | Cancel issued invoices | HIGH |
| `financial:payment:void` | Void recorded payments | CRITICAL |
| `financial:credit_memo:apply` | Apply credit memos | MEDIUM |

#### Operational Controls

| Permission | Description | Risk Level |
|------------|-------------|------------|
| `workexec:mechanic:assign` | Assign mechanics to work orders | MEDIUM |
| `workexec:schedule:override` | Override scheduling rules | MEDIUM |
| `workexec:time_entry:edit` | Edit employee time entries | HIGH |
| `workexec:time_entry:clock_in_override` | Override clock-in restrictions | HIGH |
| `workexec:vehicle_ownership:transfer` | Transfer vehicle ownership | CRITICAL |

#### Security and Administrative

| Permission | Description | Risk Level |
|------------|-------------|------------|
| `security:role:assign` | Assign roles to users | CRITICAL |
| `security:user:disable` | Disable user accounts | HIGH |
| `security:audit_log:view` | View system audit logs | MEDIUM |
| `security:break_glass:access` | Emergency elevated access | CRITICAL |

### Permission Extensibility

- **Owner:** Security domain owns the permission registry
- **Registry Format:** Data-driven (stored in database, not code)
- **New Permissions:** Added via migration or config update, **not code release**
- **Enforcement:** New features **must declare** required permissions before launch

---

## Question 2: Role Hierarchies and Inheritance

### Decision: Flat Roles (No Inheritance)

**Roles are flat. No hierarchical inheritance.**

### Rationale

- **Auditability:** Flat roles make "who has what" explicit and traceable
- **No Implicit Privilege Leakage:** Inheritance can grant unintended permissions
- **Simpler Conflict Resolution:** No need to resolve deny-vs-allow in hierarchies
- **Explicit Authorization:** Every permission grant is visible and intentional

### Conflict Handling

Since there is no inheritance, there are **no conflicts**.

- Permissions are **additive only**
- Effective permissions = **union of all assigned roles**
- No deny rules (at least not in v1.0)

### Multi-Role Assignment Example

If a user has both `Cashier` and `Manager` roles:

```
User Alice:
  - Role: Cashier
    Permissions: [financial:invoice:view, financial:payment:process]
  - Role: Manager
    Permissions: [financial:refund:approve, security:audit_log:view]

Effective Permissions for Alice:
  [financial:invoice:view, financial:payment:process, 
   financial:refund:approve, security:audit_log:view]
```

No hierarchy. No inheritance. Just union.

---

## Question 3: HR Integration and Identity Sync

### Decision: HR is Identity Source, POS Owns Authorization

#### Authority Model

- **HR System (Authoritative For):**
  - Employee identity (name, employee ID, email)
  - Employment status (ACTIVE, TERMINATED, ON_LEAVE)
  - Department assignment (for organizational reference)

- **POS System (Authoritative For):**
  - Role assignments
  - Permission grants
  - Access scopes (location, global)
  - Effective dates for role assignments

### Synchronization Approach

**One-way sync** from HR → POS:

1. HR system sends identity updates (create, update, status change)
2. POS system updates user identity records
3. **No automatic role assignment** based on HR attributes

### Department Change Handling

When an employee changes departments in HR:

- ✅ **POS receives notification** of the department change
- ✅ **No automatic role change** occurs
- ✅ **Admin workflow may suggest** role updates based on new department
- ❌ **Roles are NOT automatically reassigned**

This prevents **silent privilege drift** where HR changes inadvertently grant/revoke POS access.

### Example Integration Flow

```
HR System              POS System              Admin Workflow
-----------            -----------             ---------------
1. Employee changes    → Identity sync →       (no action)
   department
   
2. Employment status   → User status=INACTIVE → Roles expire
   = TERMINATED           (audit event)          automatically
   
3. New hire created    → User created →        Admin assigns
   in HR                  (no roles)            roles manually
```

### Sync Frequency

- **Real-time for critical events:** TERMINATED status
- **Batch sync for non-critical:** Department, title, contact info
- **Manual for role assignment:** All role grants require explicit admin action

---

## Question 4: Permission Scope and Multi-Tenant Considerations

### Decision: Explicit Scope Model

Permissions are evaluated with **explicit scope** specified in role assignments.

### Scope Types

| Scope Type | Description | Example Use Case |
|------------|-------------|------------------|
| `GLOBAL` | Applies to all resources, all locations | Corporate CFO, System Administrator |
| `LOCATION` | Applies only to specified location(s) | Shop Manager at Location A |

### Role Assignment Structure

Each role assignment includes:

```java
RoleAssignment {
    userId: 123,
    roleId: 5,  // "Manager"
    scopeType: LOCATION,
    scopeLocationIds: ["LOC-001", "LOC-002"],
    effectiveStartDate: "2026-01-01",
    effectiveEndDate: "2026-12-31"  // Optional
}
```

### Cross-Location Authorization Rules

**Rule:** Location-scoped roles do **not** grant cross-location access.

**Example:**

```
User Bob:
  - Role: Manager
    Scope: LOCATION ["LOC-001"]

Bob attempts to approve refund at LOC-002:
  ❌ DENIED - Bob's Manager role does not cover LOC-002
  
Bob attempts to approve refund at LOC-001:
  ✅ ALLOWED - Bob's scope includes LOC-001
```

### District/Regional Manager Pattern

For multi-location managers:

**Option 1: Multiple Location Scope**

```java
RoleAssignment {
    userId: 789,
    roleId: 5,  // "Manager"
    scopeType: LOCATION,
    scopeLocationIds: ["LOC-001", "LOC-002", "LOC-003", "LOC-004"]
}
```

**Option 2: Global Scope**

```java
RoleAssignment {
    userId: 789,
    roleId: 6,  // "DistrictManager"
    scopeType: GLOBAL
}
```

**No implicit scope widening.** Every location must be explicit.

---

## Question 5: Temporary Roles and Expiration

### Decision: Time-Bound Roles (Required)

All role assignments **must support** time-bound activation and expiration.

### Role Assignment Fields

```java
RoleAssignment {
    effectiveStartDate: LocalDate,  // Required
    effectiveEndDate: LocalDate      // Optional (null = no expiration)
}
```

### Automatic Expiration

- System **automatically deactivates** expired role assignments
- No manual cleanup required
- Expired assignments remain in database for audit trail

### Break-Glass Access Pattern

**Break-glass** is a first-class feature for emergency access.

#### Break-Glass Role Definition

```yaml
role: BREAK_GLASS_ADMIN
permissions:
  - security:*:*  # All security permissions (use cautiously)
  - financial:*:approve  # All financial approvals
  - workexec:*:override  # All operational overrides
scope: GLOBAL  # Break-glass is always global
```

#### Break-Glass Characteristics

| Attribute | Value | Rationale |
|-----------|-------|-----------|
| **Scope** | GLOBAL only | Emergency access should not be restricted |
| **TTL** | 1-4 hours max | Limit exposure window |
| **Justification** | Mandatory | Must record reason for use |
| **Audit Level** | CRITICAL | Every action logged with full context |
| **Dual Approval** | Optional | Consider for highest-risk environments |

#### Break-Glass Workflow

1. Authorized user requests break-glass access
2. System grants temporary role with short TTL
3. User provides **justification** (free text or incident ID)
4. System emits `BreakGlassAccessGranted` event
5. User performs emergency operations
6. Role expires automatically after TTL
7. System emits `BreakGlassAccessExpired` event
8. Security team reviews all break-glass audit logs

#### Event Schema

```java
BreakGlassAccessGranted {
    userId: 123,
    roleAssignmentId: 999,
    grantedAt: Instant,
    expiresAt: Instant,
    justification: "Production database corruption - need to restore from backup",
    grantedBy: 456,  // Approver (if dual approval used)
    incidentId: "INC-2026-001"  // Optional
}

BreakGlassAccessExpired {
    roleAssignmentId: 999,
    userId: 123,
    expiredAt: Instant,
    actionsPerformed: 12,  // Count of operations during break-glass
    affectedResources: ["db:backup", "db:restore"]
}
```

---

## Summary: Policy-Ready Access Control Model

This RBAC policy establishes:

1. ✅ **Operation-specific permissions** with policy-based threshold enforcement
2. ✅ **Flat roles** with additive permission unions
3. ✅ **HR as identity source**, POS owns authorization
4. ✅ **Explicit location scoping** with no implicit cross-location access
5. ✅ **Time-bound roles** with automatic expiration
6. ✅ **Break-glass pattern** for auditable emergency access

## Enforcement

- All domain services **must** declare permissions before feature launch
- All high-risk operations **must** require explicit permission checks
- All role assignments **must** specify scope type and effective dates
- All break-glass access **must** be logged and reviewed

## Audit Requirements

- Log all permission checks (allow/deny)
- Log all role assignments/revocations
- Log all break-glass access grants and expirations
- Retain audit logs for minimum 7 years (regulatory compliance)

## References

- **Origin Issue:** #2 - Permission Management: Define POS Roles and Permission Matrix
- **Clarification Issue:** #42 - Resolved with definitive decisions
- **Implementation:** `pos-security-service`
- **Related Documents:**
  - `PERMISSION_REGISTRY.md` - Technical permission format and registration
  - `INVENTORY_PERMISSIONS.md` - Example domain-specific permissions
