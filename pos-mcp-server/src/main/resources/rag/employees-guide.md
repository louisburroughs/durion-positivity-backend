# Employees Guide — Employee Records, Job Roles, Technician Assignment and Time Off

## Purpose

RAG id: `people.employees`  
RAG scope: `hr`  
Required permissions: `people:employee:view`  
Audience: HR administrators, store and shop managers, and administrators.  
This document is reference context only and grants no access; access is enforced by permission codes at request time.

This guide explains what an employee is on the Durion Positivity platform and how it relates to the other records a
person can have: the person identity, the user account they sign in with, the job role on their HR record, the
application roles that give them access, and the staffing assignments that place them at a location. It also states
plainly what the platform does **not** model yet (paid time off and personal shift rosters). Clocking in and out, time
entries, adjustments and exceptions are covered by the HR functions guide (`people.human-resources`); day-to-day
scheduling of technicians belongs to shop management (`shop.management`, `shop.locations`).

---

## Four records, one person

Many systems keep the people who work for a business in an HR record (personal and employment data, time and
attendance, skills) and separately control who may use which system (see Sources [1], [2]). The platform keeps that
split and makes the link explicit:

| Record | Owner | What it holds |
| --- | --- | --- |
| **Person** | `pos-people-contact` | The human identity: names, contact points, addresses. The stable identifier other services use (`personId`). |
| **Employee** | `pos-people` | Employment facts about a person: employee number, status, hire and termination dates, job role. |
| **User account** | `pos-security-service` | A sign-in identity: username, password, account-state flags and application roles. |
| **User–person link** | `pos-people-contact` | Which person is behind a user account. One active link per user, keyed by username. |

So an employee does not need a user account (a technician who never signs in), and a user account does not need an
employee record (a customer who registered online). Creating one never creates the other: hiring an employee does not
open a login, and creating a user account does not make anyone an employee. See the users and roles guide
(`admin.users-roles`) for accounts.

An employee's identifier at the API is the **person id**. The employee number is a separate, human-entered value
(for example `EMP-0001`), unique within the business; the platform does not generate it, so ask for it exactly.

---

## Employee records

### Statuses

| Status | Meaning on the platform |
| --- | --- |
| `ACTIVE` | Employed and able to hold staffing assignments. |
| `ON_LEAVE` | Temporarily away. Set by updating the employee; only the status and when it took effect are recorded. |
| `SUSPENDED` | Access or work on hold. Set by updating the employee. |
| `TERMINATED` | Employment has ended. Irreversible: a terminated employee cannot be re-enabled. |
| `DISABLED` | Administratively disabled (offboarded). Reversible through the enable action. |

Every status change stamps `statusEffectiveAt`. A termination date may not be earlier than the hire date (422).

### Creating, finding and changing employees

| Task | How | Permission |
| --- | --- | --- |
| Hire a new employee | Create with first and last name, employee number, status and hire date; optional preferred name, contact information and job role. Identity fields go to `pos-people-contact`; employment fields stay in `pos-people`. | `people:employee:create` |
| Import many employees | Bulk ingest: every row is created `ACTIVE` under the strict duplicate check, and each row succeeds or fails on its own (the reply lists per-row results). | `people:employee:create` |
| Search or list | Case-insensitive search over first, last and preferred name and employee number, filterable by status, paged (20 by default, 100 at most). Optional extra columns: username, contact information, role assignments, location, job role, allowed actions. | `people:employee:view` |
| Count by status | One count per status for the current search, for the register's tiles. | `people:employee:view` |
| Look up by employee number | Returns the person id, number, status and an active flag. | `people:employee:view` |
| Read the full profile | Includes personal contact detail: address, personal phone and email, emergency contact. | `people:employee_pii:view` |
| Update | Full replacement of the profile, including status and job role. | `people:employee:edit` |
| Offboard (disable) | Sets `DISABLED` and ends the staffing assignments; see Offboarding. | `people:employee:activation` |
| Re-enable a disabled employee | `DISABLED` to `ACTIVE`, with the last-read `updatedAt` as a concurrency check. | `people:employee:activation` |

**Duplicates.** By default (`duplicatePolicy: STRICT`) a create or update is refused with 409 when another employee
already has the same employee number, primary email or phone. `BALANCED` accepts a suspected duplicate and returns
warnings instead. Identity checks run against a replica, so a record created a moment ago may not be seen yet.

**Personal detail is narrower.** Searching and structural reads need `people:employee:view`; personal contact detail
needs `people:employee_pii:view`. A search that asks for contact columns without that permission simply leaves them
out rather than failing. The `allowedActions` list on a row (view personal detail, update, disable, enable) is a hint
for the screen; the server still checks each action.

### Offboarding

Disabling applies only to an `ACTIVE` employee and sets `DISABLED`. The staffing assignments are then ended according
to `assignmentPolicy`:

- `IMMEDIATE` (default): every active assignment ends now, at every location.
- `GRACE_PERIOD`: assignments stay active until `assignmentEndDate` (today or later), then a background job ends them.

Disabling an employee **does not disable their user account.** Ending the assignments narrows the location reach of any
location-scoped role they hold, and that revokes their live tokens, but the account itself stays enabled until a
security administrator disables or deletes it. The identity-compliance report (`people:compliance:view`) lists every
active user link whose person is `SUSPENDED`, `TERMINATED` or `DISABLED`: each row is an account that should have been
closed during offboarding. Re-enabling an employee does not bring back ended assignments.

---

## Job roles, application roles and assignment roles

Three different things are called "role", and they must not be confused.

| Term | Where it lives | What it does | Grants access? |
| --- | --- | --- | --- |
| **Job role** | `pos-people` (tenant's own list) | The employee's job title on the HR record, for example "Lead Technician". A code, a name and an optional description. | **No.** HR master data only; never read by security decisions. |
| **Application role** | `pos-security-service` | A named set of permissions (for example `SERVICE_ADVISOR`) assigned to a user account. | **Yes.** It is the only thing that grants permissions. |
| **Assignment role** | `pos-people` staffing assignment | What the person does at one location, for example `TECHNICIAN`. | Not directly; it places the person at a location, which a location-scoped application role then uses. |

In HR terms the ILO defines a job as "a set of tasks and duties performed, or meant to be performed, by one person"
(see Sources [3]); in role-based access control, by contrast, each user is assigned roles and each role carries the
privileges permitted to its holders, so access follows the role, not the person (see Sources [4]). The platform keeps the two apart on purpose: changing someone's job title never changes
what they can do in the system, and granting a permission never edits their HR record.

- List the job roles (`people:jobRole:view`) to pick one for an employee; add one with `people:jobRole:manage`. A code
  must be unique within the business.
- Application roles held by a person can be viewed and changed through `pos-people-contact`
  (`people-contact:role:view`, `people-contact:role:assign`, `people-contact:role:revoke`), which forwards to the
  security service through the person's user link. Granting access is an administration task: see the users and roles
  guide (`admin.users-roles`).

---

## Staffing assignments and technician assignment

A **staffing assignment** places a person at a location with an assignment role, effective dates (`effectiveFrom`, and
an optional `effectiveTo`, both whole days and inclusive) and a primary flag.

- The person must hold an `ACTIVE` employee record and the location must be active.
- Two assignments for the same person, location and role may not overlap (409).
- A person has one primary assignment at a time: a new primary ends the overlapping old one, and a person's first
  active assignment is always primary.
- Ending an assignment marks it `ENDED` and keeps the row; there is no physical delete.
- With a location-scoped `people:employee:edit`, the location must be within the caller's own reach
  (403 `LOCATION_SCOPE_DENIED`).

| Task | Permission |
| --- | --- |
| Create, change or end an assignment | `people:employee:edit` |
| List a person's assignments (with history) | `people:employee:view` |
| See who is assigned to a location on a date | `people:availability:view` |
| See your own locations | `people:self:view` |

**Technicians.** A person is a technician at a location when they have an active staffing assignment there with the
role `TECHNICIAN`. That is what feeds the shop's technician roster and mechanic roster in `pos-shop-manager`
(`shop:technician:view`), which also show the technician's skills. Putting a technician on a particular job is a
separate step in shop management: a workorder is assigned to a technician with `workorder:workorder:assign-technician`,
and an appointment's bay and mechanics with `shop:bay:assign` (see `shop.management`).

**Skills and credentials.** The skill registry (`people:skill:view`) is shared reference data: each skill with its
vendor codes (ASE) and the vehicle weight classes (GVWR) it covers. A person's credentials (`people:employee:view`)
show each certification with its status: `ACTIVE` or `EXPIRED` from the dates, `REVOKED` or `SUPERSEDED` when set on
purpose; a renewal is a new row beside the old one.

---

## Time off and schedules: what exists today

- **Paid time off is not modeled.** There is no leave request, leave type, balance, accrual or approval anywhere on
  the platform. The only trace of an absence is the `ON_LEAVE` employee status, set by an update.
- **There is no personal shift roster.** The technician roster's shift window is a placeholder taken from the location's
  opening hours (`shiftSource: LOCATION_HOURS`), identical for everyone at the location.
- **What does exist** is time worked: work sessions (clock in, breaks, clock out), time entries and their approval,
  adjustments, exceptions and time periods, described in the HR functions guide (`people.human-resources`).

When someone asks to book time off, say that the platform cannot record it and suggest recording the absence through
the business's usual process; do not invent a request.

---

## Permissions at a glance

| Area | Read | Change |
| --- | --- | --- |
| Employee register and lookups | `people:employee:view` | `people:employee:create`, `people:employee:edit` |
| Personal contact detail | `people:employee_pii:view` | `people:employee:edit` |
| Disable and enable | | `people:employee:activation` |
| Job roles | `people:jobRole:view` | `people:jobRole:manage` |
| Staffing assignments | `people:employee:view` | `people:employee:edit` |
| Availability at a location | `people:availability:view` | |
| Own record and locations | `people:self:view` | |
| Skills registry | `people:skill:view` | (seeded reference data) |
| Identity-compliance report | `people:compliance:view` | |
| A person's application roles | `people-contact:role:view` | `people-contact:role:assign`, `people-contact:role:revoke` |

Which roles hold these codes is tenant configuration: ask a security administrator, or see the role-permission matrix
(`security.role-permission-matrix`).

---

## Sources

Platform sources:

- `pos-people/src/main/java/com/positivity/people/internal/controller/EmployeeController.java`,
  `JobRoleController.java`, `StaffingAssignmentController.java`, `PeopleAvailabilityController.java`,
  `PeopleComplianceController.java`, `SkillController.java`, `PersonCredentialController.java`
- `pos-people/src/main/java/com/positivity/people/internal/enums/EmployeeStatus.java`, `AssignmentStatus.java`,
  `AssignmentTerminationPolicy.java`, `DuplicatePolicy.java`, `CredentialStatus.java`, `AllowedAction.java`;
  `internal/dto/EmployeeProfileDto.java`, `UpdateEmployeeRequest.java`, `CreateStaffingAssignmentRequest.java`
- `pos-people/src/main/resources/permissions.yaml`; `pos-people-contact/src/main/resources/permissions.yaml`;
  `pos-people-contact/README.md` (role assignments, ADR-0061)
- `pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/controller/TechnicianController.java`,
  `MechanicRosterController.java`; `internal/enums/ShiftSource.java`; `internal/service/StaffingScheduleService.java`
  ("PTO is not modeled anywhere in the platform yet")
- `pos-security-service/src/main/java/com/positivity/securityservice/internal/service/PeopleEventsListener.java`
  (employee facts are ignored); `pos-security-service/README.md` (Revocation on assignment change)
- `pos-workorder/src/main/resources/permissions.yaml` (`workorder:workorder:assign-technician`)
- `durion/docs/adr/0015-identity-entity-relationships.adr.md`, `durion/docs/adr/0043-user-person-linkage-authority.adr.md`,
  `durion/docs/adr/0061-location-scope-authorization-ownership.adr.md`
- `durion/domains/people/.business-rules/AGENT_GUIDE.md` (domain boundaries, DECISION-PEOPLE-004, -012, -014)

External sources:

1. "Human resource management system", Wikipedia, Wikimedia Foundation.
   <https://en.wikipedia.org/wiki/Human_resource_management_system> (accessed 2026-10-02).
2. "Identity and access management", Wikipedia, Wikimedia Foundation.
   <https://en.wikipedia.org/wiki/Identity_and_access_management> (accessed 2026-10-02).
3. "International Standard Classification of Occupations", Wikipedia, Wikimedia Foundation (quoting the ILO
   definition of a job). <https://en.wikipedia.org/wiki/International_Standard_Classification_of_Occupations>
   (accessed 2026-10-02).
4. "Role Based Access Control" project overview, Computer Security Resource Center, National Institute of Standards
   and Technology (NIST); archived project page. <https://csrc.nist.gov/projects/role-based-access-control>
   (accessed 2026-10-02).
