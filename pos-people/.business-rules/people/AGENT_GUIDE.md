# AGENT_GUIDE.md — People Domain

---

## Purpose

The People domain manages user and employee lifecycle, identity, roles, assignments, and timekeeping-related personnel data within the modular POS system. It serves as the authoritative source for user status, employee profiles, role assignments, location assignments, and manages offboarding workflows that revoke access while preserving historical data for audit and payroll.

---

## Domain Boundaries

- **Authoritative Data Ownership:**
  - User and Person lifecycle states (`ACTIVE`, `DISABLED`, `TERMINATED`).
  - Employee profiles and contact information.
  - Role definitions and role assignments with scope constraints.
  - Person-to-location assignments with effective dating and primary flags.
  - Timekeeping break records and time entry approval states.
  - Payroll-related timekeeping entries ingested from Work Execution domain.

- **Exclusions:**
  - Authentication and authorization enforcement (delegated to Security Service).
  - Job time and work session facts (owned by Work Execution and Shop Management).
  - Scheduling and dispatch decisions (consumers of People data).

---

## Key Entities / Concepts

| Entity                      | Description                                                                                  |
|-----------------------------|----------------------------------------------------------------------------------------------|
| **User**                    | Represents system login identity; status controls authentication eligibility.                |
| **Person**                  | Represents the employee or individual; linked to User; holds employment status and profile. |
| **EmployeeProfile**         | Detailed employee data including contact info, employment dates, and status.                 |
| **Role**                    | Defines a permission set with allowed scopes (`GLOBAL`, `LOCATION`).                         |
| **RoleAssignment**          | Links a User to a Role with scope, effective dates, and audit metadata.                      |
| **PersonLocationAssignment**| Assigns a Person to a Location with role, primary flag, and effective dating.                |
| **Break**                   | Records start/end of breaks during a workday, including type and auto-end flags.             |
| **TimeEntry**               | Represents work time records; used for active timers and payroll.                            |
| **TimePeriodApproval**      | Append-only records of manager approvals or rejections of time entries per pay period.       |
| **TimekeepingEntry**        | Payroll timekeeping facts ingested from Shop Management (WorkSessionCompleted events).       |

---

## Invariants / Business Rules

### User & Person Status Lifecycle

- `ACTIVE`: User can authenticate and be assigned work.
- `DISABLED`: User cannot authenticate; identity retained; reversible offboarding.
- `TERMINATED`: Employment ended; cannot authenticate; irreversible.
- Disabling a user is a logical soft delete; no physical data removal.
- Disabled users must be excluded from authentication, assignment pickers, and scheduling.

### User Deactivation Workflow

- Disabling a user atomically updates User and Person statuses to `DISABLED`.
- Active assignments are ended immediately or scheduled per policy.
- Active timers (`TimeEntry`) are forcibly stopped or queued for saga retries.
- A `user.disabled` event is emitted for downstream consumers.
- Downstream failures do not rollback disable; authentication is blocked immediately.
- Saga retry queue with exponential backoff handles downstream command failures.
- Dead Letter Queue (DLQ) triggers manual intervention after 24h of retry failures.

### Role and RoleAssignment

- Roles have allowed scopes: `GLOBAL`, `LOCATION`, or both.
- RoleAssignments link Users to Roles with scope, location (if applicable), and effective dates.
- RoleAssignments are immutable in core fields; modifications require ending and recreating assignments.
- Multiple concurrent role assignments allowed; permissions computed as scope-aware union.
- Revocation is via setting `effectiveEndDate` (soft delete).
- No hard deletes except for test or privileged purge.

### PersonLocationAssignment

- Persons can have multiple assignments with effective start/end timestamps.
- Exactly one primary assignment per person per role at any point in time.
- Overlapping assignments for the same `(personId, locationId, role)` are prohibited.
- Creating a new primary assignment automatically demotes existing primary assignments atomically.
- All changes are audited and emit versioned domain events.

### Employee Profile

- `employeeNumber` and `primaryEmail` must be unique.
- Mandatory fields: `legalName`, `employeeNumber`, `status`, `hireDate`.
- Status lifecycle: `ACTIVE`, `ON_LEAVE`, `SUSPENDED`, `TERMINATED`.
- Contact info completeness enforced as a workflow gate before assignment or activation.
- Duplicate detection with hard-block (409) on high-confidence matches; soft warnings on ambiguous matches.

### Breaks (Timekeeping)

- Only one active (`IN_PROGRESS`) break per mechanic at a time.
- Breaks must have a valid `breakType` (`MEAL`, `REST`, `OTHER`).
- Breaks can only be started/ended within an active clock-in session.
- Breaks auto-end at clock-out with audit flags and event generation.
- Break start/end times must not overlap.

### Time Entry Approval

- Manager approves or rejects all `PENDING_APPROVAL` time entries for an employee per pay period atomically.
- Rejection requires reason code and notes.
- Approved entries are immutable; adjustments handled via separate workflow.
- Approval/rejection emits audit logs and domain events.

### Payroll Timekeeping Ingestion

- People domain ingests finalized work sessions (`WorkSessionCompleted`) from Shop Management.
- Idempotent ingestion keyed by `(tenantId, sessionId)`.
- Corrections handled via explicit correction events; no silent overwrites.
- Default approval status is `PENDING_APPROVAL`.

---

## Events / Integrations

| Event Name                         | Direction         | Description                                                                                  |
|-----------------------------------|-------------------|----------------------------------------------------------------------------------------------|
| `user.disabled`                   | Outbound          | Emitted on user disable; consumed by Security, Work Execution, Scheduling services.          |
| `PersonLocationAssignmentChanged` | Outbound          | Versioned event emitted on assignment create/update/end.                                    |
| `RoleAssignmentCreated/Ended`      | Outbound          | Audit and integration events on role assignment lifecycle changes.                           |
| `EmployeeProfileCreated/Updated`   | Outbound          | Audit events emitted on employee profile changes.                                           |
| `BreakStarted/Ended/AutoEnded`     | Outbound          | Audit events for break lifecycle actions.                                                   |
| `TimeEntriesApprovedEvent`         | Outbound          | Emitted after manager approves time entries.                                                |
| `TimeEntriesRejectedEvent`         | Outbound          | Emitted after manager rejects time entries.                                                 |
| `WorkSessionCompleted`             | Inbound           | Event from Shop Management to ingest payroll timekeeping entries.                           |

---

## API Expectations (High-Level)

- **User Management:**
  - Disable user endpoint with confirmation and assignment termination options (TBD).
- **Employee Profile:**
  - Create (`POST /employees`) and update (`PUT /employees/{id}`) endpoints with duplicate detection and validation.
- **Role Assignment:**
  - Create, view, modify, and revoke role assignments with scope validation (TBD).
- **Person Location Assignment:**
  - CRUD endpoints supporting effective dating and primary flag management (TBD).
- **Break Management:**
  - Start and end break endpoints requiring break type and enforcing session constraints (TBD).
- **Time Entry Approval:**
  - Approve/reject time entries per employee per pay period atomically (TBD).
- **Reports:**
  - Attendance vs job time discrepancy report endpoint (TBD).
- **Integration:**
  - Event-driven ingestion of work sessions from Shop Management.

---

## Security / Authorization Assumptions

- All API calls require authentication.
- Role-based access control enforced:
  - Only Admins can disable users, assign roles, and manage assignments.
  - HR Administrators manage employee profiles.
  - Managers approve/reject time entries for their direct reports.
- Permission checks include:
  - `user.disable` for disabling users.
  - `people.employee.create` and `people.employee.update` for employee profiles.
  - `timekeeping:approve` for time entry approvals.
- Sensitive operations require confirmation dialogs or elevated permissions (e.g., keeping assignments active on disable).
- Downstream services rely on emitted events for enforcing access revocation and assignment exclusions.

---

## Observability (Logs / Metrics / Tracing)

- **Audit Logs:**
  - Immutable audit events for all state-changing operations (user disable, role assignment changes, employee profile changes, break lifecycle, time entry approvals).
- **Metrics:**
  - Counters for successful and failed user deactivations.
  - Saga retry and DLQ metrics for offboarding commands.
  - Role assignment API call success/failure rates.
  - Employee profile creation/update counts and duplicate detection.
  - Break start/end counts, including auto-ended breaks.
  - Time entry approval/rejection counts.
  - Report generation latency and error rates.
- **Logging Levels:**
  - INFO: Successful operations (e.g., user disable initiated/completed).
  - WARN: Downstream command failures queued for retry, duplicate detection warnings.
  - ERROR: Core domain operation failures.
  - CRITICAL: Commands moved to DLQ requiring manual intervention.
- **Tracing:**
  - Distributed tracing for user disable workflows, role assignment changes, and time entry approvals.
  - Correlation IDs propagated through saga retries and event processing.

---

## Testing Guidance

- **Unit Tests:**
  - Validate business rules and invariants for status transitions, role scope validation, assignment overlaps, break exclusivity, and approval gating.
- **Integration Tests:**
  - Test end-to-end user disable workflow including downstream event emission and saga retry behavior.
  - Verify role assignment creation, modification, and revocation with scope constraints.
  - Employee profile create/update with duplicate detection and validation.
  - Break start/end including auto-end at clock-out.
  - Time entry approval/rejection atomicity and audit logging.
- **Contract Tests:**
  - Validate event schemas and payloads for domain events.
  - Verify integration with Security, Work Execution, and Shop Management services.
- **Error Handling Tests:**
  - Simulate downstream failures and verify retry queue and DLQ behavior.
  - Test permission denied scenarios.
  - Validate input validation and error responses.
- **Performance Tests:**
  - Load test report generation and bulk ingestion endpoints.
- **Manual Testing:**
  - Confirm UI workflows for disabling users, managing roles, and break recording align with domain rules.

---

## Common Pitfalls

- **Partial disable rollback:** Avoid rolling back user disable on downstream failures; authentication must be blocked immediately.
- **Assignment overlap:** Ensure strict enforcement of no overlapping assignments for the same `(personId, locationId, role)`.
- **Role scope mismatch:** Validate role assignment scopes against role metadata to prevent invalid global/location combinations.
- **Duplicate detection false negatives:** Implement robust normalization (e.g., lowercase emails, E.164 phones) to prevent duplicate employee profiles.
- **Break concurrency:** Prevent multiple simultaneous active breaks per mechanic.
- **Saga retry starvation:** Monitor retry queue depth and DLQ to avoid unnoticed failures.
- **Audit gaps:** Ensure all state changes emit audit events with sufficient context.
- **Immutable fields:** Do not allow direct modification of core fields in role assignments; require ending and recreating assignments.
- **Time zone handling:** Carefully handle time zone conversions for effective dating and report generation.
- **Event ordering:** Enforce monotonic ordering for HR roster ingestion to prevent stale updates.
- **Security enforcement:** Do not expose sensitive user status or role information to unauthorized callers.

---

# End of AGENT_GUIDE.md
