# Break-Glass Access Pattern

> **Purpose:** Emergency elevated access with maximum auditability  
> **Risk Level:** CRITICAL  
> **Status:** Active Pattern  
> **Version:** 1.0  
> **Date:** 2026-01-13

## Overview

**Break-glass access** provides temporary elevated permissions for emergency situations where standard authorization would block critical operations.

This pattern balances **operational necessity** with **security and audit requirements**.

## When to Use Break-Glass

Break-glass access should be used **only** for:

✅ **Production incidents** requiring immediate intervention  
✅ **Data recovery** operations that cannot wait for approval  
✅ **Security incidents** requiring rapid response  
✅ **System failures** where normal authorization is unavailable

### ❌ Do NOT Use Break-Glass For

- Routine administrative tasks
- Convenience (avoiding normal approval workflows)
- Bypassing intentional security controls for non-emergency reasons
- Testing or development activities

## Break-Glass Role Definition

The system defines a special role with elevated permissions:

```yaml
role: BREAK_GLASS_ADMIN
description: Emergency elevated access for critical incidents
scope: GLOBAL  # Always global - no location restrictions
permissions:
  # Security Operations
  - security:role:assign
  - security:user:disable
  - security:user:reset_password
  - security:audit_log:view
  
  # Financial Operations
  - financial:refund:approve
  - financial:payment:void
  - financial:invoice:cancel
  - financial:price_override:approve
  
  # Operational Controls
  - workexec:schedule:override
  - workexec:time_entry:edit
  - inventory:adjustment:approve
  
  # System Administration
  - system:config:edit
  - system:database:restore
  - system:service:restart
```

**Important:** The exact permission set should be reviewed and adjusted based on organizational risk tolerance.

## Break-Glass Workflow

### Request Flow

```
┌─────────────────────────────────────────────────────────┐
│  1. INCIDENT OCCURS                                     │
│     • System down, data corruption, security breach     │
└─────────────────────┬───────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────┐
│  2. AUTHORIZED USER REQUESTS BREAK-GLASS ACCESS         │
│     • Provide justification                             │
│     • Reference incident ticket (optional)              │
└─────────────────────┬───────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────┐
│  3. SYSTEM GRANTS TEMPORARY ROLE                        │
│     • Creates time-limited role assignment              │
│     • TTL: 1-4 hours (configurable)                     │
│     • Emits BreakGlassAccessGranted event               │
└─────────────────────┬───────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────┐
│  4. USER PERFORMS EMERGENCY OPERATIONS                  │
│     • All actions logged with break-glass context       │
│     • Audit level: CRITICAL                             │
└─────────────────────┬───────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────┐
│  5. ROLE EXPIRES AUTOMATICALLY                          │
│     • No manual revocation needed                       │
│     • Emits BreakGlassAccessExpired event               │
└─────────────────────┬───────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────┐
│  6. POST-INCIDENT REVIEW                                │
│     • Security team reviews all break-glass logs        │
│     • Validate actions were justified                   │
│     • Document in incident report                       │
└─────────────────────────────────────────────────────────┘
```

## API Usage

### Request Break-Glass Access

```bash
POST /api/security/break-glass/request
Content-Type: application/json
Authorization: Bearer <user-token>

{
  "justification": "Production database corruption. Need to restore from backup immediately. Customer orders failing.",
  "incidentId": "INC-2026-001",  # Optional
  "requestedDurationMinutes": 120,  # 2 hours
  "emergencyContact": "+1-555-0123"  # Optional
}
```

### Response

```json
{
  "roleAssignmentId": 999,
  "grantedAt": "2026-01-13T14:30:00Z",
  "expiresAt": "2026-01-13T16:30:00Z",
  "durationMinutes": 120,
  "message": "Break-glass access granted. All actions will be audited. Access expires at 2026-01-13T16:30:00Z."
}
```

### Check Break-Glass Status

```bash
GET /api/security/break-glass/status
Authorization: Bearer <user-token>
```

Response:

```json
{
  "hasActiveBreakGlass": true,
  "roleAssignmentId": 999,
  "grantedAt": "2026-01-13T14:30:00Z",
  "expiresAt": "2026-01-13T16:30:00Z",
  "remainingMinutes": 87
}
```

### Revoke Break-Glass Access Early (Optional)

```bash
POST /api/security/break-glass/revoke
Authorization: Bearer <user-token>

{
  "roleAssignmentId": 999,
  "reason": "Emergency resolved. No longer need elevated access."
}
```

## Configuration

### TTL Configuration

```yaml
breakGlass:
  defaultTTLMinutes: 60       # Default 1 hour
  maxTTLMinutes: 240          # Max 4 hours
  minTTLMinutes: 30           # Min 30 minutes
  autoRevoke: true            # Automatically expire after TTL
```

### Authorization Configuration

```yaml
breakGlass:
  authorizedRoles:
    - "SystemAdministrator"
    - "OperationsLead"
    - "IncidentCommander"
  requireJustification: true
  requireIncidentId: false    # Optional incident tracking
  requireDualApproval: false  # Set to true for high-security environments
```

### Audit Configuration

```yaml
breakGlass:
  audit:
    logLevel: "CRITICAL"
    retentionYears: 7
    alertSecurityTeam: true
    alertChannel: "slack"  # or email, pagerduty
```

## Audit Events

### BreakGlassAccessGranted

```json
{
  "eventType": "BreakGlassAccessGranted",
  "eventId": "evt_bg_001",
  "timestamp": "2026-01-13T14:30:00Z",
  "userId": 123,
  "userName": "john.doe@example.com",
  "roleAssignmentId": 999,
  "roleName": "BREAK_GLASS_ADMIN",
  "grantedAt": "2026-01-13T14:30:00Z",
  "expiresAt": "2026-01-13T16:30:00Z",
  "justification": "Production database corruption. Need to restore from backup immediately.",
  "incidentId": "INC-2026-001",
  "grantedBy": 456,  # If dual approval is used
  "ipAddress": "192.168.1.100",
  "userAgent": "Mozilla/5.0..."
}
```

### BreakGlassOperationPerformed

Every operation performed under break-glass access emits:

```json
{
  "eventType": "BreakGlassOperationPerformed",
  "eventId": "evt_bg_op_001",
  "timestamp": "2026-01-13T14:35:12Z",
  "userId": 123,
  "roleAssignmentId": 999,
  "operation": "database:backup:restore",
  "resourceId": "db_prod_001",
  "details": {
    "backupFile": "backup-2026-01-13-02-00.sql",
    "targetDatabase": "pos_prod"
  },
  "ipAddress": "192.168.1.100"
}
```

### BreakGlassAccessExpired

```json
{
  "eventType": "BreakGlassAccessExpired",
  "eventId": "evt_bg_002",
  "timestamp": "2026-01-13T16:30:00Z",
  "userId": 123,
  "roleAssignmentId": 999,
  "expiredAt": "2026-01-13T16:30:00Z",
  "totalOperations": 12,
  "operationsSummary": [
    {"operation": "database:backup:restore", "count": 1},
    {"operation": "system:service:restart", "count": 3},
    {"operation": "security:audit_log:view", "count": 8}
  ],
  "affectedResources": ["db_prod_001", "service_order", "service_inventory"]
}
```

## Monitoring and Alerting

### Real-Time Alerts

When break-glass access is granted, the system should:

1. ✅ Send **Slack/Email/PagerDuty** alert to security team
2. ✅ Display banner on admin dashboard
3. ✅ Log to SIEM (Security Information and Event Management) system

### Alert Content

```
🚨 BREAK-GLASS ACCESS GRANTED

User: john.doe@example.com (ID: 123)
Granted At: 2026-01-13 14:30:00 UTC
Expires At: 2026-01-13 16:30:00 UTC
Justification: Production database corruption. Need to restore from backup immediately.
Incident ID: INC-2026-001

All actions will be logged. Review logs at: https://pos.example.com/admin/audit?bg=999
```

## Post-Incident Review

After break-glass access expires, the security team should:

1. **Review Audit Logs** - Verify all actions were justified
2. **Validate Incident** - Confirm the emergency was legitimate
3. **Document Findings** - Record in incident report
4. **Process Improvement** - Identify if normal authorization should be adjusted

### Audit Review Checklist

- [ ] Verify justification matches actual operations performed
- [ ] Confirm all operations align with stated incident
- [ ] Check for any unauthorized or suspicious activity
- [ ] Validate no sensitive data was accessed inappropriately
- [ ] Document any policy violations or concerns
- [ ] Update incident report with break-glass usage summary

## Security Considerations

### Least Privilege

Even with break-glass access, users should:
- Use minimum permissions necessary
- Revoke access early if incident is resolved
- Document all actions clearly

### Dual Approval (Optional)

For high-security environments, require a second authorized user to approve break-glass requests:

```yaml
breakGlass:
  requireDualApproval: true
  approverRoles:
    - "ChiefSecurityOfficer"
    - "VPOperations"
  approvalTimeoutMinutes: 15  # Auto-deny if no approval
```

### Rate Limiting

Prevent abuse by limiting break-glass requests:

```yaml
breakGlass:
  rateLimits:
    perUser:
      maxRequests: 3
      windowHours: 24
    perOrganization:
      maxRequests: 10
      windowHours: 24
```

## Testing

### Unit Tests

```java
@Test
void shouldGrantBreakGlassAccessWithValidJustification() {
    BreakGlassRequest request = BreakGlassRequest.builder()
        .userId(123L)
        .justification("Emergency: Production database failure")
        .durationMinutes(60)
        .build();
    
    BreakGlassResponse response = breakGlassService.grantAccess(request);
    
    assertThat(response.isGranted()).isTrue();
    assertThat(response.getExpiresAt())
        .isAfter(Instant.now())
        .isBefore(Instant.now().plus(61, ChronoUnit.MINUTES));
}

@Test
void shouldRejectBreakGlassWithoutJustification() {
    BreakGlassRequest request = BreakGlassRequest.builder()
        .userId(123L)
        .justification("")  // Empty justification
        .durationMinutes(60)
        .build();
    
    assertThatThrownBy(() -> breakGlassService.grantAccess(request))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Justification is required");
}

@Test
void shouldAutoExpireAfterTTL() throws InterruptedException {
    // Grant 1-minute break-glass
    BreakGlassRequest request = BreakGlassRequest.builder()
        .userId(123L)
        .justification("Test auto-expiration")
        .durationMinutes(1)
        .build();
    
    BreakGlassResponse response = breakGlassService.grantAccess(request);
    
    // Wait for expiration
    Thread.sleep(65000);  // 65 seconds
    
    // Verify access expired
    boolean hasAccess = roleManagementService.hasBreakGlassAccess(123L);
    assertThat(hasAccess).isFalse();
}
```

## References

- **RBAC Policy:** `docs/RBAC_POLICY.md` - Section on break-glass pattern
- **Audit Requirements:** Core audit logging and retention policies
- **Incident Management:** Organization's incident response procedures
- **Origin:** Clarification Issue #42, Question 5 - Emergency access requirements
