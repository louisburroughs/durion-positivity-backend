# HR Functions Guide — pos-people

This guide describes the workforce management capabilities of the `pos-people` service. It is written for HR administrators, managers, and integration developers who need to understand what the service does and how to use it, rather than how it is built.

---

## Overview

`pos-people` is the authoritative HR and workforce management service in the Durion Positivity platform. It owns the lifecycle of every employee and person record, tracks where people are assigned, records when they work, manages the approval of time, and controls what roles they hold in the system.

The service exposes a REST API. All endpoints require a valid bearer token, and each operation is protected by a specific permission scope described below.

Security note: the current built-in role mappings in `pos-security-service` grant the core HR employee/staffing/role-management permissions only to `ADMIN` by default. Work-session endpoints require authentication but no specific permission. Several advanced people/time/reporting/user-link permissions exist in `pos-people` but are not included in any standard role by default; those must be granted explicitly through Security Admin.

---

## Concepts

| Term                      | What it means                                                                                                                                                                                        |
| ------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Person**                | A human being in the system. A person record holds identity data (name, contact info) and is the root record everything else links to.                                                               |
| **Employee**              | A person in an employment relationship. An employee record extends a person with HR-specific fields: employee number, hire date, status, and contact details.                                        |
| **Staffing Assignment**   | A link between a person and a physical location, including their role at that location, whether it is their primary location, and the dates the assignment is active.                                |
| **Work Session**          | A clock-in/clock-out record for a person's shift, including any breaks taken during that shift.                                                                                                      |
| **Time Entry**            | A payroll-period record summarising hours worked, derived from work sessions. Time entries go through a review and approval workflow before being exported to payroll.                               |
| **Time Entry Adjustment** | A correction request against an existing time entry — for example, to add missed break time or change start/end timestamps.                                                                          |
| **Time Entry Exception**  | A flag raised when something about a time entry looks wrong (e.g. no clock-out recorded). Exceptions have a severity level and must be acknowledged, resolved, or waived before payroll can proceed. |
| **User–Person Link**      | A binding between a security system user account and a person record. This is how the platform knows which person is behind a logged-in user.                                                        |

---

## Employee Management

### Creating an employee

An employee is created with a legal name, a preferred name (optional), a unique employee number, a status, and a hire date. Contact information (primary email and phone) can be provided at creation time.

The `duplicatePolicy` field controls what happens if a potential duplicate is detected. The default is `STRICT`, which rejects the request. Use `LENIENT` or `IGNORE` if importing from a legacy system where duplicates are expected.

**Required permission(s):** `people:employee:create`  
**Roles with permission:** `ADMIN`

### Updating an employee

Employee profile information can be updated at any time: name, employee number, status, hire date, termination date, and contact details.

**Required permission(s):** `people:employee:edit`  
**Roles with permission:** `ADMIN`

### Disabling (offboarding) an employee

Disabling an employee marks them as inactive. An optional reason can be recorded. The `assignmentPolicy` field controls what happens to their active staffing assignments:

- `IMMEDIATE` — all active assignments are ended right away (default).
- `GRACE_PERIOD` — assignments are ended on the date specified in `assignmentEndDate`, allowing a transition period.

**Required permission(s):** `people:employee:deactivate`  
**Roles with permission:** `ADMIN`

### Employee statuses

| Status       | Meaning                                                |
| ------------ | ------------------------------------------------------ |
| `ACTIVE`     | Currently employed and working.                        |
| `ON_LEAVE`   | Temporarily absent (e.g. medical, parental leave).     |
| `SUSPENDED`  | Access restricted pending investigation or other hold. |
| `TERMINATED` | Employment has ended.                                  |
| `DISABLED`   | Administratively disabled; record is inactive.         |

### Viewing an employee

Retrieve an employee profile by their employee ID.

**Required permission(s):** `people:employee:view`  
**Roles with permission:** `ADMIN`

---

## Person Records

Person records are the underlying identity layer beneath employee records. They can exist independently of an employment relationship (e.g. for contractors or historical records).

### Available operations

- **List all people** — returns all person records in the system.
- **Get by ID** — retrieve a single person by their UUID.
- **Create** — add a new person.
- **Resolve** — find the best-matching person by identity score, or create one if no match is found. This is useful when ingesting data from external sources where the exact ID is not known.
- **Update** — modify a person's details.
- **Delete** — remove a person record. This is a hard delete; use with caution.

**Required permission(s):**
- List all people / get by ID: `people:person:view`
- Create / resolve: `people:person:create`
- Update: `people:person:edit`
- Delete: `people:person:delete`

**Roles with permission:** `None by default`

---

## Bulk Employee Import

For large-scale onboarding (e.g. system migrations or importing from an HRIS), employees can be created in bulk via a single request. Each record in the batch requires:

- `legalName`
- `employeeNumber`
- `hireDate` (format: `YYYY-MM-DD`)
- `preferredName` (optional)
- `primaryEmail` (optional)
- `primaryPhone` (optional)

The response reports how many records succeeded and how many failed, with per-row error detail for failures. Successful rows are not rolled back if other rows fail.

**Required permission(s):** `people:employee:create`  
**Roles with permission:** `ADMIN`

---

## Staffing Assignments

Staffing assignments define where a person works and in what role. A person can have multiple assignments (e.g. split across locations), but only one can be flagged as their primary assignment.

### Creating an assignment

Provide the person ID, location ID, role, effective start date, and whether this is the primary assignment. An optional end date can be set for fixed-term placements. If an overlapping active assignment already exists for the same person and location, the request is rejected with a 409 conflict.

**Required permission(s):** `people:employee:edit`  
**Roles with permission:** `ADMIN`

### Viewing assignments

Retrieve all assignments for a given person by providing their person ID as a query parameter. Individual assignments can also be fetched by their assignment ID.

**Required permission(s):** `people:employee:view`  
**Roles with permission:** `ADMIN`

### Updating an assignment

Role, dates, and the primary flag can all be updated. Overlap validation applies the same way as on creation.

**Required permission(s):** `people:employee:edit`  
**Roles with permission:** `ADMIN`

### Ending an assignment

Assignments are soft-deleted — the record is retained for audit purposes but the assignment is marked as `ENDED`. Use this when a person transfers locations or leaves a role.

**Required permission(s):** `people:employee:edit`  
**Roles with permission:** `ADMIN`

### Assignment statuses

| Status   | Meaning                     |
| -------- | --------------------------- |
| `ACTIVE` | Assignment is current.      |
| `ENDED`  | Assignment has been closed. |

---

## Availability

The availability query returns which people are assigned to a location and available to work on a given date, based on their active staffing assignments.

- Filter by `locationId` to see availability at a specific site. If omitted, the current user's location is used.
- Filter by `date` (ISO format `YYYY-MM-DD`) to check a specific day.

The response includes each person's name, their role, whether the assignment is their primary one, and the assignment's effective date range.

### Current user's primary location

An authenticated user can query their own primary location without needing to know their person or assignment ID. This returns the location ID of their active primary staffing assignment.

**Required permission(s):** `people:availability:view`  
**Roles with permission:** `None by default`

---

## Work Sessions (Clock-In / Clock-Out)

Work sessions record when a person starts and stops a shift, and any breaks they take within it.

### Starting a session

Provide the person ID to open a new work session. The system records the current timestamp as the start time.

### Stopping a session

Provide the person ID to close the active session. The system records the current timestamp as the end time. After a session ends, the platform calculates the total hours worked and creates or updates the associated time entry.

### Breaks

While a session is active, breaks can be started and stopped against the session's ID. Break time is tracked separately and excluded from net hours worked.

**Required permission(s):** Authentication only  
**Roles with permission:** `Any authenticated user`

---

## Time Entries and Approval

Time entries represent a payroll-period record of hours worked for a given employee. They are created automatically from work sessions.

### Status lifecycle

```
DRAFT → SUBMITTED → PENDING_APPROVAL → APPROVED
                                     → REJECTED
```

| Status             | Meaning                                                          |
| ------------------ | ---------------------------------------------------------------- |
| `DRAFT`            | Initial state; the entry has been created but not yet submitted. |
| `SUBMITTED`        | Submitted by the employee or system; awaiting review.            |
| `PENDING_APPROVAL` | In the manager approval queue.                                   |
| `APPROVED`         | Approved and ready for payroll export.                           |
| `REJECTED`         | Rejected; the employee or system may need to resubmit.           |

### Batch approval and rejection

Multiple time entries can be approved or rejected in a single request by providing a list of time entry IDs.

Rejections require a `rejectionReason` for each entry — the API will return 400 if any decision in the batch is missing a reason.

**Required permission(s):**
- Batch approval: `people:timeEntry:approve`
- Batch rejection: `people:timeEntry:reject`

**Roles with permission:** `None by default`

---

## Time Entry Adjustments

Adjustments are correction requests made against an already-submitted or approved time entry. They go through their own approval workflow before taking effect.

### Creating an adjustment

An adjustment requires:

- `timeEntryId` — the entry to correct.
- `reasonCode` — a code identifying the type of correction (e.g. `MISSED_BREAK`).
- At least one of: `proposedStartAt`, `proposedEndAt`, or `minutesDelta` (a positive or negative number of minutes to add or subtract).
- `notes` (optional) — a free-text explanation.

The new adjustment starts in `PENDING` status.

**Required permission(s):** `people:timeAdjustment:create`  
**Roles with permission:** `None by default`

### Viewing adjustments

All adjustments for a given time entry can be listed by providing the time entry ID.

**Required permission(s):** `people:timeAdjustment:view`  
**Roles with permission:** `None by default`

### Approving an adjustment

A pending adjustment can be approved by a user with the approval permission. Once approved, the underlying time entry is corrected.

**Required permission(s):** `people:timeAdjustment:approve`  
**Roles with permission:** `None by default`

---

## Time Entry Exceptions

Exceptions are system-generated or manually raised flags that indicate something about a time entry needs human attention before it can move forward in the payroll workflow.

### Severity levels

| Severity   | Behaviour                                                           |
| ---------- | ------------------------------------------------------------------- |
| `WARNING`  | Advisory; does not block payroll processing but should be reviewed. |
| `BLOCKING` | Must be resolved or waived before the time entry can be approved.   |

### Creating an exception

Exceptions can be raised manually or by the system during timekeeping ingestion.

**Required permission(s):** `people:timeException:create`  
**Roles with permission:** `None by default`

### Viewing exceptions

Exceptions can be listed for all employees or filtered to a specific employee.

**Required permission(s):** `people:timeException:view`  
**Roles with permission:** `None by default`

### Resolving, acknowledging, or waiving an exception

| Action          | When to use                                                                                                |
| --------------- | ---------------------------------------------------------------------------------------------------------- |
| **Acknowledge** | You have seen the exception and are aware of it, but have not yet taken corrective action.                 |
| **Resolve**     | The underlying issue has been corrected. Optional resolution notes can be recorded.                        |
| **Waive**       | The exception is being dismissed without correction. A `waiveReason` is required — this is not reversible. |

**Required permission(s):**
- Acknowledge: `people:timeException:acknowledge`
- Resolve: `people:timeException:resolve`
- Waive: `people:timeException:resolve`

**Roles with permission:** `None by default`

---

## Access Control (Role Assignments)

Roles control what a person can do within the platform. The access control APIs allow managers and administrators to view, assign, and revoke roles on individual person records.

### Viewing available roles

Retrieve the list of roles that can be assigned to a person.

**Required permission(s):** `people:role:view`  
**Roles with permission:** `ADMIN`

### Viewing a person's current role assignments

Role assignments can be retrieved with optional history (including past assignments) and an optional end-date filter.

**Required permission(s):** `people:role:view`  
**Roles with permission:** `ADMIN`

### Assigning a role

Assign a role to a person by providing:

- `roleCode` — the role identifier.
- `locationId` (optional) — scope the role to a specific location.
- `startDate` / `endDate` (optional) — date range for the assignment.

**Required permission(s):** `people:role:assign`  
**Roles with permission:** `ADMIN`

### Revoking a role

Remove a role assignment from a person. An optional `endDate` can be provided to end the assignment at a specific point in the past rather than immediately.

**Required permission(s):** `people:role:revoke`  
**Roles with permission:** `ADMIN`

---

## User–Person Links

Every user account in the authentication system must be linked to a person record before the platform can associate that user's activity with an employee. This link is created during onboarding and removed during offboarding.

### Linking a user to a person

Provide the user ID (from the authentication system) and the person ID. If the link already exists, the existing link is returned without error. A user cannot be linked to more than one person — attempting this returns a 409 conflict.

**Required permission(s):** `people:userLink:write`  
**Roles with permission:** `None by default`

### Looking up the person for a user

Given a user ID, retrieve the person record that is linked to it. This is used by other services to resolve the person behind an authenticated request.

**Required permission(s):** `people:userLink:view`  
**Roles with permission:** `None by default`

### Looking up users for a person

Given a person ID, retrieve all user account IDs that are linked to that person.

**Required permission(s):** `people:userLink:view`  
**Roles with permission:** `None by default`

### Removing a link

Unlink a user from their person record. This is typically done as part of offboarding or when correcting a mis-linked account.

**Required permission(s):** `people:userLink:write`  
**Roles with permission:** `None by default`

---

## Reports

### Attendance vs. job time discrepancy report

Compares attendance records (from work sessions/time entries) against job time totals pulled from the work execution service. The report is broken down per technician, per location, and per day.

Parameters:

- `startDate` / `endDate` (inclusive, format `YYYY-MM-DD`)
- `timezone` (IANA format, e.g. `America/Chicago`)
- `locationId` (optional filter)
- `technicianIds` (optional list of person IDs to limit the report)
- `flaggedOnly` — when `true`, only returns rows where a discrepancy was detected

**Required permission(s):** `people:time:export:read` or `accounting:time:export`  
**Roles with permission:** `None by default`

### Approved time export

Returns all approved time entries for a date range and one or more locations, formatted for downstream payroll and accounting workflows. Each row includes the employee, location, date, hours worked (decimal), and approval metadata.

This endpoint is the stable read contract for accounting export integrations. Payroll identifier mapping is performed by the accounting domain, not here.

Parameters:

- `startDate` / `endDate` (inclusive)
- `locationId` — one or more location IDs (required)

**Required permission(s):** `people:time:export:read` or `accounting:time:export`  
**Roles with permission:** `None by default`
