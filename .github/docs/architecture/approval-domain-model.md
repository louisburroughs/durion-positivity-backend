# Customer Approval Domain Model

**Domain:** workexec  
**Last Updated:** 2026-01-08  
**Status:** Design - Based on Clarification #207

## Overview

This document defines the domain model for customer approvals in the Durion Positivity POS system. The approval system is designed to handle digital customer approvals for both Estimates and Work Orders, including signature capture, state management, and versioning.

---

## Core Entities

### 1. CustomerApproval

The central entity for recording customer approvals.

**Purpose:** Records a customer's digital approval or denial of an estimate or work order, including signature data and metadata.

**Entity Structure:**

```java
@Entity
@Table(name = "customer_approval")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerApproval {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    // Entity Reference (Generic)
    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false)
    private ApprovalEntityType entityType; // ESTIMATE or WORK_ORDER
    
    @Column(name = "entity_id", nullable = false)
    private Long entityId;
    
    // Approval Details
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ApprovalStatus status; // PENDING, APPROVED, DENIED
    
    @Column(name = "customer_id", nullable = false)
    private Long customerId;
    
    // Signature Data (Dual Format)
    @Lob
    @Column(name = "signature_image_data", columnDefinition = "TEXT")
    private String signatureImageData; // Base64-encoded PNG
    
    @Lob
    @Column(name = "signature_json_data", columnDefinition = "TEXT")
    private String signatureJsonData; // JSON stroke data
    
    @Column(name = "signature_mime_type")
    private String signatureMimeType; // "image/png"
    
    // Denial Information
    @Column(name = "denial_reason", columnDefinition = "TEXT")
    private String denialReason; // Required if status = DENIED
    
    @Column(name = "denial_timestamp")
    private Instant denialTimestamp;
    
    // Legal/Audit Metadata
    @Column(name = "approval_timestamp")
    private Instant approvalTimestamp;
    
    @Column(name = "ip_address")
    private String ipAddress;
    
    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;
    
    @Column(name = "geolocation")
    private String geolocation; // JSON: {"lat": 123.45, "lon": 67.89}
    
    @Column(name = "consent_version")
    private String consentVersion; // Version of terms shown to customer
    
    @Column(name = "authentication_method")
    private String authenticationMethod; // e.g., "email", "sms", "oauth"
    
    // Audit Fields
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    
    @Column(name = "created_by")
    private String createdBy;
    
    @Column(name = "updated_at")
    private Instant updatedAt;
    
    @Column(name = "updated_by")
    private String updatedBy;
    
    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
```

---

### 2. Estimate (Enhanced)

Extended to support versioning and approval workflow.

**Entity Structure:**

```java
@Entity
@Table(name = "estimate")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Estimate {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    // Version Management
    @Column(name = "base_estimate_id", nullable = false)
    private String baseEstimateId; // Groups versions: "EST-12345"
    
    @Column(name = "version", nullable = false)
    private Integer version; // 1, 2, 3, etc.
    
    @Column(name = "version_label")
    private String versionLabel; // "EST-12345-v1"
    
    // Status
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private EstimateStatus status; // DRAFT, PENDING_APPROVAL, APPROVED, DENIED, SUPERSEDED
    
    // Versioning Relationships
    @Column(name = "superseded_by")
    private Long supersededBy; // Points to newer version ID
    
    @Column(name = "supersedes")
    private Long supersedes; // Points to older version ID
    
    @Column(name = "superseded_at")
    private Instant supersededAt;
    
    // Approval Reference
    @Column(name = "approval_id")
    private Long approvalId; // References CustomerApproval
    
    // Business Fields
    @Column(name = "customer_id", nullable = false)
    private Long customerId;
    
    @Column(name = "vehicle_id")
    private Long vehicleId;
    
    @Column(name = "shop_id", nullable = false)
    private Long shopId;
    
    @Column(name = "total_amount", precision = 10, scale = 2)
    private BigDecimal totalAmount;
    
    @Lob
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    
    // Audit Fields
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    
    @Column(name = "created_by")
    private String createdBy;
    
    @Column(name = "updated_at")
    private Instant updatedAt;
    
    @Column(name = "updated_by")
    private String updatedBy;
    
    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        
        // Auto-generate version label
        if (versionLabel == null && baseEstimateId != null && version != null) {
            versionLabel = String.format("%s-v%d", baseEstimateId, version);
        }
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
```

---

### 3. WorkOrder (Enhanced)

Extended to support approval workflow and transfer tracking.

**Entity Structure:**

```java
@Entity
@Table(name = "work_order")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkOrder {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    // Business References
    @Column(name = "shop_id", nullable = false)
    private Long shopId;
    
    @Column(name = "vehicle_id")
    private Long vehicleId;
    
    @Column(name = "customer_id", nullable = false)
    private Long customerId;
    
    // Approval Reference
    @Column(name = "approval_id")
    private Long approvalId; // References CustomerApproval
    
    // Estimate Version Tracking
    @Column(name = "current_estimate_id")
    private Long currentEstimateId; // Current estimate version
    
    @Column(name = "original_estimate_id")
    private Long originalEstimateId; // First estimate version
    
    // Status Management
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private WorkOrderStatus status;
    
    // Transfer Tracking
    @Column(name = "transfer_reason", columnDefinition = "TEXT")
    private String transferReason;
    
    @Column(name = "transferred_at")
    private Instant transferredAt;
    
    @Column(name = "transferred_to_estimate_id")
    private Long transferredToEstimateId; // New estimate version
    
    // Denial Tracking (if applicable)
    @Column(name = "denial_reason", columnDefinition = "TEXT")
    private String denialReason;
    
    @Column(name = "denial_timestamp")
    private Instant denialTimestamp;
    
    // Line Items
    @OneToMany(mappedBy = "workOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WorkOrderService> services;
    
    @OneToMany(mappedBy = "workOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WorkOrderPart> parts;
    
    // Audit Fields
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    
    @Column(name = "created_by")
    private String createdBy;
    
    @Column(name = "updated_at")
    private Instant updatedAt;
    
    @Column(name = "updated_by")
    private String updatedBy;
    
    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
```

---

### 4. ApprovalAuditLog

Comprehensive audit trail for all approval-related actions.

**Entity Structure:**

```java
@Entity
@Table(name = "approval_audit_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApprovalAuditLog {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "approval_id", nullable = false)
    private Long approvalId;
    
    @Column(name = "action", nullable = false)
    private String action; // CREATED, APPROVED, DENIED, VIEWED, EXPORTED
    
    @Column(name = "actor_id")
    private Long actorId; // User who performed action
    
    @Column(name = "actor_type")
    private String actorType; // CUSTOMER, EMPLOYEE, SYSTEM
    
    @Column(name = "previous_status")
    @Enumerated(EnumType.STRING)
    private ApprovalStatus previousStatus;
    
    @Column(name = "new_status")
    @Enumerated(EnumType.STRING)
    private ApprovalStatus newStatus;
    
    @Lob
    @Column(name = "details", columnDefinition = "TEXT")
    private String details; // JSON with action-specific details
    
    @Column(name = "ip_address")
    private String ipAddress;
    
    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;
    
    @PrePersist
    protected void onCreate() {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }
}
```

---

## Enumerations

### ApprovalEntityType

```java
public enum ApprovalEntityType {
    ESTIMATE("Estimate"),
    WORK_ORDER("Work Order");
    
    private final String displayName;
    
    ApprovalEntityType(String displayName) {
        this.displayName = displayName;
    }
    
    public String getDisplayName() {
        return displayName;
    }
}
```

### ApprovalStatus

```java
public enum ApprovalStatus {
    PENDING("Pending"),
    APPROVED("Approved"),
    DENIED("Denied");
    
    private final String displayName;
    
    ApprovalStatus(String displayName) {
        this.displayName = displayName;
    }
    
    public String getDisplayName() {
        return displayName;
    }
}
```

### EstimateStatus

```java
public enum EstimateStatus {
    DRAFT("Draft"),
    PENDING_APPROVAL("Pending Customer Approval"),
    APPROVED("Approved"),
    DENIED("Denied"),
    SUPERSEDED("Superseded");
    
    private final String displayName;
    
    EstimateStatus(String displayName) {
        this.displayName = displayName;
    }
    
    public String getDisplayName() {
        return displayName;
    }
}
```

### WorkOrderStatus

```java
public enum WorkOrderStatus {
    PENDING_CUSTOMER_APPROVAL("Pending Customer Approval"),
    APPROVED("Approved"),
    DENIED("Denied"),
    IN_PROCESS("In Process"),
    ON_HOLD("On Hold"),
    TRANSFERRED("Transferred"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled");
    
    private final String displayName;
    
    WorkOrderStatus(String displayName) {
        this.displayName = displayName;
    }
    
    public String getDisplayName() {
        return displayName;
    }
}
```

---

## Signature Data Formats

### Signature JSON Schema

The JSON stroke data follows this structure:

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "properties": {
    "strokes": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "points": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "x": { "type": "number" },
                "y": { "type": "number" },
                "time": { "type": "integer" },
                "pressure": { "type": "number", "minimum": 0, "maximum": 1 }
              },
              "required": ["x", "y", "time"]
            }
          }
        },
        "required": ["points"]
      }
    },
    "width": { "type": "integer", "minimum": 1 },
    "height": { "type": "integer", "minimum": 1 },
    "timestamp": { "type": "string", "format": "date-time" },
    "deviceInfo": {
      "type": "object",
      "properties": {
        "type": { "type": "string" },
        "os": { "type": "string" },
        "browser": { "type": "string" }
      }
    }
  },
  "required": ["strokes", "width", "height", "timestamp"]
}
```

### Example Signature JSON

```json
{
  "strokes": [
    {
      "points": [
        { "x": 100, "y": 150, "time": 1704672000000, "pressure": 0.5 },
        { "x": 102, "y": 152, "time": 1704672000010, "pressure": 0.6 },
        { "x": 105, "y": 155, "time": 1704672000020, "pressure": 0.7 }
      ]
    },
    {
      "points": [
        { "x": 120, "y": 140, "time": 1704672000100, "pressure": 0.4 },
        { "x": 122, "y": 138, "time": 1704672000110, "pressure": 0.5 }
      ]
    }
  ],
  "width": 400,
  "height": 200,
  "timestamp": "2026-01-08T18:00:00Z",
  "deviceInfo": {
    "type": "touchscreen",
    "os": "iOS 16",
    "browser": "Safari 16.0"
  }
}
```

---

## Relationships

### Entity Relationship Diagram

```
┌─────────────────┐
│  CustomerApproval│
│  - id (PK)      │
│  - entityType   │◄───────┐
│  - entityId     │        │ References
│  - status       │        │
│  - signatureData│        │
└─────────────────┘        │
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
┌───────▼────────┐  ┌──────▼──────┐  ┌───────▼────────┐
│   Estimate     │  │  WorkOrder  │  │ ApprovalAuditLog│
│   - id (PK)    │  │  - id (PK)  │  │  - id (PK)     │
│   - baseEstId  │  │  - status   │  │  - approvalId  │
│   - version    │  │  - approvalId│  │  - action      │
│   - status     │  │  - currentEstId│ │  - timestamp   │
│   - supersededBy│ └─────────────┘  └─────────────────┘
│   - approvalId │         │
└────────────────┘         │
        │                  │
        │      References  │
        └──────────────────┘
         (currentEstimateId)
```

---

## Business Rules

### Approval Creation Rules

1. **Uniqueness:** Only one PENDING or APPROVED approval per entity at a time
2. **State Validation:** Entity must be in appropriate state to receive approval
3. **Signature Required:** For APPROVED status, signature data must be present
4. **Denial Reason:** For DENIED status, denial reason is mandatory

### State Transition Rules

#### Estimate States

```
DRAFT → PENDING_APPROVAL → APPROVED → SUPERSEDED
                        ↓
                      DENIED
```

Rules:
- DRAFT is editable without versioning
- PENDING_APPROVAL requires customer approval
- APPROVED estimates require versioning for changes
- DENIED estimates can be revised (creates new draft)
- SUPERSEDED is automatic when new version is created

#### Work Order States

```
PENDING_CUSTOMER_APPROVAL → APPROVED → IN_PROCESS → COMPLETED
                         ↓             ↓
                       DENIED      ON_HOLD
                                      ↓
                                 TRANSFERRED
```

Rules:
- PENDING_CUSTOMER_APPROVAL requires approval to proceed
- DENIED requires reason
- TRANSFERRED requires reference to new estimate version
- Any state can transition to CANCELLED (with reason)

### Versioning Rules

1. **Trigger:** Change to APPROVED estimate creates new version
2. **Version Number:** Increment by 1 from previous
3. **Old Version:** Status → SUPERSEDED, supersededBy → new version ID
4. **Work Order Cascade:** All work orders → TRANSFERRED status
5. **Transfer Data:** Must include reason and reference to new estimate

---

## API Contracts

### Create Approval Request

```json
{
  "entityType": "WORK_ORDER",
  "entityId": 12345,
  "customerId": 67890,
  "signatureImageData": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg...",
  "signatureJsonData": "{\"strokes\": [...]}",
  "ipAddress": "192.168.1.100",
  "userAgent": "Mozilla/5.0...",
  "geolocation": "{\"lat\": 37.7749, \"lon\": -122.4194}",
  "consentVersion": "v1.2"
}
```

### Deny Approval Request

```json
{
  "approvalId": 123,
  "denialReason": "Customer requested changes to labor rates",
  "customerId": 67890
}
```

### Create Estimate Version Request

```json
{
  "baseEstimateId": "EST-12345",
  "sourceVersion": 1,
  "reason": "Customer requested additional services",
  "changes": {
    "description": "Added brake pad replacement",
    "totalAmount": 850.00
  }
}
```

---

## Database Indexes

Recommended indexes for performance:

```sql
-- CustomerApproval indexes
CREATE INDEX idx_customer_approval_entity ON customer_approval(entity_type, entity_id);
CREATE INDEX idx_customer_approval_customer ON customer_approval(customer_id);
CREATE INDEX idx_customer_approval_status ON customer_approval(status);
CREATE INDEX idx_customer_approval_created ON customer_approval(created_at);

-- Estimate indexes
CREATE INDEX idx_estimate_base_version ON estimate(base_estimate_id, version);
CREATE INDEX idx_estimate_status ON estimate(status);
CREATE INDEX idx_estimate_approval ON estimate(approval_id);
CREATE INDEX idx_estimate_superseded ON estimate(superseded_by);

-- WorkOrder indexes
CREATE INDEX idx_work_order_status ON work_order(status);
CREATE INDEX idx_work_order_approval ON work_order(approval_id);
CREATE INDEX idx_work_order_estimate ON work_order(current_estimate_id);
CREATE INDEX idx_work_order_transfer ON work_order(transferred_to_estimate_id);

-- ApprovalAuditLog indexes
CREATE INDEX idx_audit_approval ON approval_audit_log(approval_id);
CREATE INDEX idx_audit_timestamp ON approval_audit_log(timestamp);
CREATE INDEX idx_audit_actor ON approval_audit_log(actor_id, actor_type);
```

---

## Migration Strategy

### Phase 1: Core Approval System
1. Create CustomerApproval entity
2. Add approval_id to WorkOrder and Estimate
3. Implement basic approval workflow

### Phase 2: Versioning
1. Add version fields to Estimate
2. Implement version creation logic
3. Add transfer tracking to WorkOrder

### Phase 3: Audit Trail
1. Create ApprovalAuditLog entity
2. Implement audit logging for all operations
3. Add audit query endpoints

---

## References

- **Clarification Document:** `approval-workflow-clarification.md`
- **Origin Story:** Issue #207
- **Domain:** workexec
- **Related Modules:** pos-work-order, pos-customer

---

*This domain model is based on the clarification decisions documented in issue #207. Any changes should be reviewed through proper governance channels.*
