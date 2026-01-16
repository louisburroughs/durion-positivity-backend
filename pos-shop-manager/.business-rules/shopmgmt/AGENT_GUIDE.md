# AGENT_GUIDE.md — shopmgmt Domain

---

## Purpose

The **shopmgmt** domain manages appointment scheduling and resource assignment within the modular POS system for automotive service shops. It is responsible for creating, rescheduling, and displaying assignments of appointments linked to estimates and work orders, ensuring operational efficiency, referential integrity, and coordination with downstream services.

---

## Domain Boundaries

- **Authoritative for:**
  - Appointment lifecycle management (creation, rescheduling)
  - Assignment of bays, mobile units, and mechanics to appointments
  - Conflict detection and resolution in scheduling
  - Referential integrity of appointment links to estimates and work orders
  - Emitting domain events related to appointments and assignments

- **Not responsible for:**
  - Work duration calculation (delegated to Work Execution service)
  - Mechanic qualifications and HR data (delegated to People/HR service)
  - Customer notifications (Notification service handles delivery)
  - Financial transactions or parts inventory

---

## Key Entities / Concepts

- **Appointment**: Scheduled service event linked immutably to an estimate or work order.
- **Estimate / Work Order**: Source documents authorizing or defining service scope.
- **Bay / Mobile Unit**: Physical or mobile service locations with type constraints.
- **Mechanic**: Technician assigned based on required skills and availability.
- **Assignment**: The allocation of bay/mobile unit and mechanic to an appointment.
- **Conflict**: Scheduling or resource availability issues categorized as Hard or Soft.
- **AssignmentStatus**: States reflecting assignment completeness (e.g., AWAITING_SKILL_FULFILLMENT).
- **Events**: Domain events such as `AppointmentCreatedFromEstimate`, `AppointmentRescheduled`, `AssignmentUpdated`.

---

## Invariants / Business Rules

- **Appointment Eligibility**: Only estimates in APPROVED/QUOTED or work orders not COMPLETED/CANCELLED and not previously linked can create appointments.
- **Duration Authority**: Work Execution service duration overrides estimates if discrepancy >20% or 30 minutes; scheduler overrides require permissions and reasons.
- **Skill Matching**: All required skills must be met for auto-assignment; otherwise, appointment status is `AWAITING_SKILL_FULFILLMENT`.
- **Bay Type Constraints**: Appointment bay assignment must match required bay types; mobile units are mutually exclusive with bays.
- **Conflict Handling**:
  - Hard conflicts block creation/rescheduling unless overridden with permission and reason.
  - Soft conflicts warn and allow override.
- **Assignment Strategy**: Configurable per facility; default auto-assign bay immediately, defer mechanic assignment for complex/long services.
- **Referential Integrity**: Links to estimate/work order are immutable and enforced uniquely for active appointments.
- **Reschedule Limits**: Max 2 reschedules without manager approval; minimum notice enforced with escalation for short notice.
- **Audit Trail**: All creation, rescheduling, overrides, and assignment changes are logged immutably with actor, reason, and timestamps.

---

## Events / Integrations

- **Emitted Events**:
  - `AppointmentCreatedFromEstimate`
  - `AppointmentCreatedFromWorkOrder`
  - `AppointmentRescheduled`
  - `AssignmentUpdated`
  - `AppointmentCreationAttempted`
  - `ConflictDetected`
  - `ConflictOverridden`
  - `EstimateStatusChanged`
  - `ReferentialLinkEstablished`
  - `AssignmentDecision`
  - `WorkExecutionNotified`
  - `NotificationFailed` (via Notification service integration)
  - `AuditLog` entries

- **Consumed Services**:
  - **Work Execution Service**: authoritative duration and skill data
  - **People/HR Service**: mechanic qualifications and availability
  - **Notification Service**: customer and mechanic notifications
  - **Audit Service**: immutable audit logging

- **Event Contract**: Events are idempotent, include `eventId`, `appointmentId`, `version`, and relevant metadata.

---

## API Expectations (High-Level)

- **Create Appointment**: Accepts source (estimate/work order), scheduling preferences, overrides, and user context; validates eligibility and conflicts; applies assignment strategy; emits creation events.  
  - *API details: TBD*

- **Reschedule Appointment**: Accepts appointment ID, new schedule, reason, override flags; validates conflicts and permissions; updates assignment preservation; emits reschedule events and triggers notifications.  
  - *API details: TBD*

- **View Assignment**: Provides read-only access to current bay/mobile and mechanic assignments with notes and timestamps; supports real-time updates via events or polling.  
  - *API details: TBD*

- **Conflict Checking**: Returns conflict results with categorization and suggested alternatives during create/reschedule workflows.  
  - *API details: TBD*

---

## Security / Authorization Assumptions

- **RBAC enforced** with scoped permissions:
  - `CREATE_APPOINTMENT` for appointment creation
  - `RESCHEDULE_APPOINTMENT` for rescheduling
  - `OVERRIDE_SCHEDULING_CONFLICT` for conflict overrides
  - `APPROVE_DURATION_OVERRIDE` for duration overrides beyond thresholds
  - `VIEW_ASSIGNMENTS` scoped by facility for assignment visibility
  - `EDIT_ASSIGNMENT_NOTES` limited to Shop Manager and Dispatcher roles

- **Facility scoping** applies to all operations to restrict access to relevant shop locations.

- **Audit logging** captures all permission-sensitive actions with actor identity and reason.

---

## Observability (Logs / Metrics / Tracing)

- **Logs**:
  - Appointment creation/reschedule attempts and outcomes
  - Conflict detection and override details
  - Assignment changes and preservation decisions
  - Permission denials and security events
  - Integration call successes/failures with Work Execution, People/HR, Notification services

- **Metrics**:
  - Appointment creation success rate
  - Conflict detection and override rates
  - Deferred assignment queue times
  - Duration estimation accuracy vs. Work Execution
  - Reschedule success and notification delivery rates
  - Assignment preservation ratio

- **Tracing**:
  - Distributed tracing across appointment lifecycle events and downstream notifications
  - Correlation IDs propagated through event chains for troubleshooting

---

## Testing Guidance

- **Unit Tests**:
  - Validate eligibility rules for appointment creation
  - Conflict detection logic with hard/soft categorization
  - Assignment strategy behavior per facility configuration
  - Permission enforcement for overrides and sensitive actions
  - Duration precedence and override rules

- **Integration Tests**:
  - Event emission and idempotency with Work Execution and Notification services
  - Referential integrity enforcement on appointment links
  - Reschedule workflows including conflict overrides and notification triggers
  - Assignment visibility and update propagation under simulated service outages

- **End-to-End Tests**:
  - Scheduler workflows creating and rescheduling appointments with realistic data
  - Permission boundary tests for different user roles
  - Notification failure scenarios and audit trail verification

- **Performance Tests**:
  - Conflict detection under load with multiple concurrent scheduling attempts
  - Real-time assignment update latency under event-driven and polling modes

---

## Common Pitfalls

- **Ignoring permission checks** on overrides leading to unauthorized scheduling conflicts.
- **Breaking referential integrity** by allowing mutable links to estimates/work orders.
- **Not handling Work Execution service unavailability**, causing duration or skill data gaps.
- **Overriding hard conflicts without audit or reason**, compromising scheduling reliability.
- **Failing to enforce minimum notice periods on reschedules**, risking operational disruption.
- **Assignment preservation logic errors** causing inconsistent bay/mechanic allocations.
- **Insufficient observability on event emission failures**, hindering incident response.
- **Not accounting for mobile unit GPS staleness**, leading to inaccurate assignment displays.
- **Overloading UI with frequent polling instead of event-driven updates**, increasing system load.

---

*End of AGENT_GUIDE.md*
