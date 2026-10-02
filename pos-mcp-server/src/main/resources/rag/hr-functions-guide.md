# HR Functions Guide — pos-people

This guide describes the workforce management capabilities of the `pos-people` service. It is written for HR administrators, managers, and integration developers who need to understand what the service does and how to use it, rather than how it is built.

---

## Overview

`pos-people` is the authoritative HR and workforce management service in the Durion Positivity platform. It owns the lifecycle of every employee record, tracks where people are assigned, records when they work, and manages the approval of time. Person identity records, user–person links and the people screens' role assignments live in the sibling service `pos-people-contact`, and are described here because HR administrators use them alongside employee records. Employee records, job roles and technician staffing are covered in more depth by the employee guide (`people.employees`).

The service exposes a REST API. All endpoints require a valid bearer token, and each operation is protected by a specific permission scope described below.

Security note: which roles hold each permission in this guide is tenant configuration in `pos-security-service` (the baseline seed plus whatever a security administrator grants); see the role-permission matrix (`security.role-permission-matrix`). Work-session endpoints require authentication, and a caller may act only for themself unless they hold `people:timekeeping:approve`.

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

An employee is created with a first and last name, a preferred name (optional), a unique employee number, a status, a hire date and, optionally, a job role. Contact information (primary email and phone) can be provided at creation time.

The `duplicatePolicy` field controls what happens if a potential duplicate (same employee number, primary email or phone) is detected. The default is `STRICT`, which rejects the request with 409. `BALANCED` accepts a suspected duplicate and returns warnings in the response instead.

**Required permission(s):** `people:employee:create`

### Updating an employee

Employee profile information can be updated at any time: name, employee number, status, hire date, termination date, and contact details.

**Required permission(s):** `people:employee:edit`

### Disabling (offboarding) an employee

Disabling an `ACTIVE` employee sets the status `DISABLED`; other statuses are refused (409). It does not disable the person's user account, which is a separate security task. The `assignmentPolicy` field controls what happens to their active staffing assignments:

- `IMMEDIATE` — all active assignments are ended right away (default).
- `GRACE_PERIOD` — assignments are ended on the date specified in `assignmentEndDate`, allowing a transition period.

A `DISABLED` employee can be re-enabled (back to `ACTIVE`); ended assignments are not restored. `TERMINATED` is irreversible.

**Required permission(s):** `people:employee:activation`

### Employee statuses

| Status       | Meaning                                                |
| ------------ | ------------------------------------------------------ |
| `ACTIVE`     | Currently employed and working.                        |
| `ON_LEAVE`   | Temporarily absent (e.g. medical, parental leave).     |
| `SUSPENDED`  | Access restricted pending investigation or other hold. |
| `TERMINATED` | Employment has ended.                                  |
| `DISABLED`   | Administratively disabled; record is inactive.         |

### Viewing an employee

Retrieve the full employee profile by the employee's person ID, including personal contact detail (address, personal phone and email, emergency contact). Looking an employee up by employee number returns a slim record and needs only `people:employee:view`.

**Required permission(s):** `people:employee_pii:view`

### Searching employees

Search returns a paged list of slim employee rows (id, employee number, name, status, active flag) matching a
case-insensitive substring against first name, last name, preferred name, and employee number. Leaving the query
blank lists every employee, paged. Use this for listing or typeahead lookups; use "Viewing an employee" instead
once the employee's id is already known.

**Required permission(s):** `people:employee:view`

---

## Person Records

Person records are the underlying identity layer beneath employee records, owned by `pos-people-contact`. They can exist independently of an employment relationship (e.g. for contractors or historical records).

### Available operations

- **List all people** — returns all person records in the system.
- **Get by ID** — retrieve a single person by their UUID.
- **Create** — add a new person.
- **Resolve** — find the best-matching person by identity score, or create one if no match is found. This is useful when ingesting data from external sources where the exact ID is not known.
- **Update** — modify a person's details.
- **Delete** — remove a person record. This is a hard delete; use with caution.

**Required permission(s):**
- List all people / get by ID: `people-contact:person:view`
- Create / resolve: `people-contact:person:create`
- Update: `people-contact:person:edit`
- Delete: `people-contact:person:delete`

---

## Bulk Employee Import

For large-scale onboarding (e.g. system migrations or importing from an HRIS), employees can be created in bulk via a single request. Every record is created `ACTIVE` under the `STRICT` duplicate policy. The request names a `jobId` and a `locationId` for the batch, and each record in it requires:

- `firstName` and `lastName`
- `employeeNumber`
- `hireDate` (format: `YYYY-MM-DD`)
- `preferredName` (optional)
- `primaryEmail` (optional)
- `primaryPhone` (optional)

The response reports how many records succeeded and how many failed, with per-row error detail for failures. Successful rows are not rolled back if other rows fail.

**Required permission(s):** `people:employee:create`

---

## Staffing Assignments

Staffing assignments define where a person works and in what role. A person can have multiple assignments (e.g. split across locations), but only one can be flagged as their primary assignment.

### Creating an assignment

Provide the person ID, location ID, role (for example `TECHNICIAN`), effective start date, and whether this is the primary assignment. An optional end date can be set for fixed-term placements. The person must hold an `ACTIVE` employee record and the location must be active. If an overlapping assignment already exists for the same person, location and role, the request is rejected with a 409 conflict. A new primary assignment ends any overlapping old primary, and a person's first active assignment is always primary.

**Required permission(s):** `people:employee:edit`

### Viewing assignments

Retrieve all assignments for a given person by providing their person ID as a query parameter. Individual assignments can also be fetched by their assignment ID.

**Required permission(s):** `people:employee:view`

### Updating an assignment

Role, dates, and the primary flag can all be updated. Overlap validation applies the same way as on creation.

**Required permission(s):** `people:employee:edit`

### Ending an assignment

Assignments are soft-deleted — the record is retained for audit purposes but the assignment is marked as `ENDED`. Use this when a person transfers locations or leaves a role.

**Required permission(s):** `people:employee:edit`

### Assignment statuses

| Status   | Meaning                     |
| -------- | --------------------------- |
| `ACTIVE` | Assignment is current.      |
| `ENDED`  | Assignment has been closed. |

---

## Availability

The availability query returns which people are assigned to a location and available to work on a given date, based on their active staffing assignments.

- Filter by `locationId` to see availability at a specific site. If omitted, the current user's location is used. A location outside the caller's location reach is refused (403 `LOCATION_SCOPE_DENIED`).
- Filter by `date` (ISO format `YYYY-MM-DD`) to check a specific day.

The response includes each person's name, their role, whether the assignment is their primary one, and the assignment's effective date range.

### Current user's primary location

An authenticated user can query their own primary location without needing to know their person or assignment ID. This returns the location ID of their active primary staffing assignment, or the top-level location (flagged as defaulted) when they have none. A user can also list all of their locations active today.

**Required permission(s):**
- Availability at a location: `people:availability:view`
- Own primary location and own locations: `people:self:view`

---

## Work Sessions (Clock-In / Clock-Out)

Work sessions record when a person starts and stops a shift, and any breaks they take within it.

### Starting a session

Provide the person ID to open a new work session. The system records the current timestamp as the start time.

### Stopping a session

Provide the person ID to close the active session. The system records the current timestamp as the end time. After a session ends, the platform calculates the total hours worked and creates or updates the associated time entry.

### Breaks

While a session is active, breaks can be started and stopped against the session's ID. Break time is tracked separately and excluded from net hours worked.

**Required permission(s):** Authentication; acting for another person needs `people:timekeeping:approve`

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

### Viewing adjustments

All adjustments for a given time entry can be listed by providing the time entry ID.

**Required permission(s):** `people:timeAdjustment:view`

### Approving an adjustment

A pending adjustment can be approved by a user with the approval permission. Once approved, the underlying time entry is corrected.

**Required permission(s):** `people:timeAdjustment:approve`

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

### Viewing exceptions

Exceptions can be listed for all employees or filtered to a specific employee.

**Required permission(s):** `people:timeException:view`

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

---

## Access Control (Role Assignments)

Application roles control what a person can do within the platform. The access control APIs in `pos-people-contact` let managers and administrators view, assign, and revoke roles on individual person records; they forward to `pos-security-service` through the person's user–person link. These are not job roles: a job role on the employee record grants no permissions (see `people.employees`).

### Viewing available roles

Retrieve the list of roles that can be assigned to a person.

**Required permission(s):** `people-contact:role:view`

### Viewing a person's current role assignments

Role assignments can be retrieved with optional history (including past assignments) and an optional end-date filter.

**Required permission(s):** `people-contact:role:view`

### Assigning a role

Assign a role to a person by providing:

- `roleCode` — the role identifier (a role's name is its code).
- `startDate` / `endDate` (optional) — date-time window for the assignment, start inclusive and end exclusive.

An assignment carries no location: how far a role reaches is a property of the role (`ALL` or `LOCATION` scope, ADR-0061), combined with the person's staffing assignments.

**Required permission(s):** `people-contact:role:assign`

### Revoking a role

Remove a role assignment from a person. An optional `endDate` can be provided to end the assignment at a specific point in the past rather than immediately.

**Required permission(s):** `people-contact:role:revoke`

---

## User–Person Links

Every user account in the authentication system must be linked to a person record before the platform can associate that user's activity with an employee. Links are owned by `pos-people-contact` and keyed by username. A link is created during onboarding and removed during offboarding; deleting a user account removes its link.

### Linking a user to a person

Provide the username and the person ID. If the identical link already exists, it is returned without error. A username cannot be linked to more than one person — attempting this returns a 409 conflict.

**Required permission(s):** `people-contact:userLink:write`

### Looking up the person for a user

Given a username, retrieve the person record that is linked to it. This is used by other services to resolve the person behind an authenticated request.

**Required permission(s):** `people-contact:userLink:view`

### Looking up users for a person

Given a person ID, retrieve the user accounts linked to that person.

**Required permission(s):** `people-contact:userLink:view`

### Removing a link

Unlink a user from their person record. This is typically done as part of offboarding or when correcting a mis-linked account.

**Required permission(s):** `people-contact:userLink:write`

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

**Required permission(s):** `accounting:time:export`

### Approved time export

Returns all approved time entries for a date range and one or more locations, formatted for downstream payroll and accounting workflows. Each row includes the employee, location, date, hours worked (decimal), and approval metadata.

This endpoint is the stable read contract for accounting export integrations. Payroll identifier mapping is performed by the accounting domain, not here.

Parameters:

- `startDate` / `endDate` (inclusive)
- `locationId` — one or more location IDs (required)

**Required permission(s):** `accounting:time:export`

---

## Sources

Platform sources (corrections of 2026-10-02, #2385):

- `pos-people/src/main/java/com/positivity/people/internal/controller/EmployeeController.java`,
  `PersonBulkIngestController.java`, `StaffingAssignmentController.java`, `PeopleAvailabilityController.java`,
  `WorkSessionController.java`, `PeopleReportsController.java`, `TimeEntryExceptionController.java`
- `pos-people/src/main/java/com/positivity/people/internal/enums/DuplicatePolicy.java`, `EmployeeStatus.java`;
  `pos-people/src/main/resources/permissions.yaml`
- `pos-people-contact/src/main/java/com/positivity/peoplecontact/internal/controller/PersonController.java`,
  `PersonAccessController.java`, `UserPersonLinkController.java`; `pos-people-contact/README.md`;
  `pos-people-contact/src/main/resources/permissions.yaml`
- `durion/docs/adr/0061-location-scope-authorization-ownership.adr.md`
