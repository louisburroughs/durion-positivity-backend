# Shop Management & Work Order Guide

This guide covers day-to-day shop operations on the Durion Positivity platform: scheduling appointments, managing bays and mobile units, building estimates, running work orders through to completion, and tracking technician time and parts. It is written for service advisors, shop managers, and integration developers rather than as a technical reference.

The functionality described here spans two services that work together:

- **pos-shop-manager** — appointments, scheduling, bay/mobile unit configuration, conflict detection, and workorder operational context.
- **pos-workorder** — estimates, work order lifecycle, technician assignment, labor tracking, parts usage, change requests, and invoicing.

---

## Concepts

| Term               | What it means                                                                                                                                                    |
| ------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Appointment**    | A scheduled service visit. Links a customer, a vehicle, a time slot, and one or more shop resources (bay or mobile unit).                                        |
| **Bay**            | A fixed service stall at a location. An appointment is assigned to a bay for the duration of the visit.                                                          |
| **Mobile Unit**    | A vehicle-based service unit that can perform work on-site at a customer location. Treated like a bay for scheduling purposes.                                   |
| **Assignment**     | The record that links an appointment to a specific bay or mobile unit and to one or more mechanics.                                                              |
| **Estimate**       | An itemised list of parts and labor created for a customer before work begins. Must be approved (with customer signature) before being promoted to a work order. |
| **Work Order**     | The active record that tracks a vehicle being serviced. Created by promoting an approved estimate.                                                               |
| **Change Request** | A request to add work to an in-progress work order that was not on the original estimate. Requires customer approval before proceeding.                          |
| **Labor Entry**    | A timed record of a technician working on a service line. Start/stop times are recorded; hours are used for payroll and billing.                                 |
| **WIP**            | Work In Progress — the live dashboard of all active work orders at a location.                                                                                   |
| **Pick List**      | The inventory items that need to be pulled from stock to fulfil the parts lines on a work order.                                                                 |

---

## Shop Configuration

### Locations and shop info

Shop location and service details are available via the Shop API. Technician identity can be looked up by person ID within a location, and service entity details can be retrieved by service ID.

**Required role(s):** Admin

### Bays

Bays are the physical service stalls at a location. The Bay API supports the following operations:

- **List all bays** — returns every bay in the system, or all bays at a specific location.
- **Get a specific bay** — retrieve a single bay by its location ID and bay ID.
- **Create a bay** — add a new bay to a location.
- **Bulk manage bays** — create or update multiple bay records in a single request.
- **Delete a bay** — remove a bay from a location. Returns 404 if the bay does not exist.

| Operation               | Required role(s) |
| ----------------------- | ---------------- |
| View bays               | Admin            |
| Create a bay            | Admin            |
| Edit / bulk manage bays | Admin            |
| Delete a bay            | Admin            |

### Mobile Units

Mobile units are treated as schedulable resources in the same way bays are. They have a base location and a set of declared service capabilities. The Mobile Unit API mirrors the Bay API:

- List all mobile units or get a specific one.
- Create a mobile unit (validates base location and capabilities).
- Bulk manage mobile units.
- Delete a mobile unit.

| Operation                       | Required role(s) |
| ------------------------------- | ---------------- |
| View mobile units               | Admin            |
| Create a mobile unit            | Admin            |
| Edit / bulk manage mobile units | Admin            |
| Delete a mobile unit            | Admin            |

---

## Appointments

### Creating an appointment

An appointment books a time slot for a customer vehicle at a location. The system performs conflict detection during creation and rejects the request (400) if the requested slot is already taken. A 422 is returned if the source estimate or work order is in a state that cannot be scheduled.

Appointments support idempotent creation via an `Idempotency-Key` header — submitting the same key twice returns the original result without creating a duplicate.

**Required role(s):** Admin

### Retrieving an appointment

Retrieve a single appointment by its ID.

**Required role(s):** Admin

### Rescheduling an appointment

An appointment can be rescheduled to a new time slot as long as it is in a reschedulable status. The request must include the new time and a reason. If the reason is `OTHER`, a notes field is required. Returns 409 if the appointment is in a state that does not allow rescheduling.

**Required role(s):** Admin

### Cancelling an appointment

Cancel a scheduled appointment. Returns 409 if the appointment cannot be cancelled in its current state.

**Required role(s):** Admin

### Appointment statuses

| Status              | Meaning                                        |
| ------------------- | ---------------------------------------------- |
| `SCHEDULED`         | Booked and confirmed.                          |
| `CHECKED_IN`        | Vehicle has arrived.                           |
| `WORK_IN_PROGRESS`  | Active service underway.                       |
| `WAITING_FOR_PARTS` | Service paused pending parts arrival.          |
| `QUALITY_CHECK`     | Work completed; undergoing quality inspection. |
| `READY_FOR_PICKUP`  | Vehicle ready for customer collection.         |
| `COMPLETED`         | Vehicle collected; visit closed.               |
| `CANCELLED`         | Appointment cancelled.                         |
| `INVOICED`          | Invoice generated from this appointment.       |
| `REOPENED`          | Previously completed appointment reopened.     |

### Resource assignments

An appointment can be assigned to a bay or mobile unit, and one or more mechanics can be linked to it. Assignments can be created with an optional override flag if the system detects a conflict (see below).

- **Create an assignment** — link a bay/mobile unit and mechanics to an appointment.
- **List assignments** — retrieve all assignments for an appointment.

| Operation         | Required role(s) |
| ----------------- | ---------------- |
| Create assignment | Admin            |
| View assignments  | Admin            |

### Conflict override

When creating an assignment or scheduling an appointment, the system checks for overlapping bookings of the same resource (bay, mobile unit, or mechanic). If a conflict is detected, the request is rejected by default.

An operator with schedule-editing access can explicitly override a detected conflict by calling the conflict override endpoint. The override is recorded with a reason for audit purposes. Returns 403 if the caller lacks the required authority.

**Required role(s):** Admin

---

## Schedule View

The schedule view returns a read-only snapshot of all appointments, assignments, and resource usage at a location for a given date. It is designed for use by the dispatch UI.

Parameters:

- `locationId` (required) — the location to view.
- `date` (required, format `YYYY-MM-DD`) — the date to view.
- `resourceType` (optional) — filter to a specific resource type (bay or mobile unit).
- `resourceId` (optional) — filter to a single resource.
- `includeAvailabilityOverlay` (optional, default `false`) — overlay HR availability data from the people service.
- `range` (optional, default `LOCATION_HOURS`) — the time window to show.

**Required role(s):** Admin

### Workorder operational context

For a given location and work order, this endpoint assembles the full operational context: bay assignment, mechanic details, vehicle information, and customer details. It is a read-only view used by the shop operations UI.

**Required role(s):** Admin

### Shop audit trail

Every scheduling and assignment change is recorded in the shop audit trail. The audit log is immutable — there are no delete or update endpoints.

- **Search the audit trail** — filter by appointment, location, date range, actor, or action type. At least one filter criterion is required.
- **Get a single audit entry** — retrieve a specific entry by its UUID.

**Required role(s):** Admin

---

## Estimates

An estimate is the starting point for every service visit. It captures the customer, vehicle, location, and the parts and labor that will be performed.

### Estimate lifecycle

```
DRAFT → PENDING_APPROVAL → APPROVED → (promoted) → Work Order
      ↘ DECLINED ↗ (reopen)
            ↘ EXPIRED
```

| Status             | Meaning                                                      |
| ------------------ | ------------------------------------------------------------ |
| `DRAFT`            | Being built; items can be added and edited freely.           |
| `PENDING_APPROVAL` | Submitted to the customer for approval; snapshot taken.      |
| `APPROVED`         | Customer has signed and accepted the estimate.               |
| `DECLINED`         | Customer declined; can be reopened within the expiry window. |
| `EXPIRED`          | Approval window has passed without a decision.               |
| `SCHEDULED`        | Work scheduled from an approved estimate.                    |
| `INVOICED`         | Invoice has been generated.                                  |
| `CANCELLED`        | Cancelled; no further action.                                |
| `ARCHIVED`         | Closed and archived.                                         |

### Creating an estimate

A new estimate is created in `DRAFT` status. Required fields are customer ID, vehicle ID, and location ID. Supports idempotent creation via `Idempotency-Key` header.

**Required role(s):** Admin

### Viewing estimates

Estimates can be retrieved individually by ID, or listed by customer, shop, or location.

**Required role(s):** Admin

### Adding and editing line items

While an estimate is in `DRAFT` status, line items (parts and labor) can be added, updated, and removed.

- **Add item** — provide item type (`PART` or `LABOR`), description, quantity, unit price, and tax code.
- **Update item** — change any field on an existing line item.
- **Remove item** — soft-delete a line item. The estimate must remain in `DRAFT` status.

| Operation        | Required role(s) |
| ---------------- | ---------------- |
| Add line item    | Admin            |
| Edit line item   | Admin            |
| Remove line item | Admin            |

### Calculating taxes and totals

Once items are added, the estimate's subtotal, tax amount, and total can be calculated. The estimate must be in `DRAFT` status. The calculation calls the tax service to apply the correct rate.

**Required role(s):** Admin

### Submitting for customer approval

A complete draft estimate can be submitted for customer approval. This transition:

1. Validates that the estimate has a customer, vehicle, line items, and calculated totals.
2. Creates an immutable snapshot of the estimate at that point.
3. Moves the estimate to `PENDING_APPROVAL`.

**Required role(s):** No standard role — the `workorder:estimate:submit` permission must be explicitly granted via Security Admin.

### Customer approval

The customer approves the estimate by providing a signature (base64-encoded image), their name, and optional notes. For commercial accounts with purchase order enforcement enabled, a purchase order number is also required.

Selective line item approvals are supported — a customer can approve a subset of the estimate's items.

**Required role(s):** Admin

### Declining and reopening an estimate

An estimate can be declined from `DRAFT` or `APPROVED` status. A declined estimate can be reopened back to `DRAFT` within the configured expiry window.

| Operation                | Required role(s) |
| ------------------------ | ---------------- |
| Decline estimate         | Admin            |
| Reopen declined estimate | Admin            |

### Promoting an estimate to a work order

An approved estimate is promoted to a work order using the promote endpoint. Preconditions:

- Estimate must be in `APPROVED` status.
- Must not have expired.
- Must have at least one approved line item.
- Must not have already been promoted.

If the estimate was previously promoted, the existing work order is returned (idempotent behaviour). Supports `Idempotency-Key` header.

**Required role(s):** No standard role — the `workorder:estimate:promote` permission must be explicitly granted via Security Admin.

### Estimate PDF

A PDF document of the estimate can be generated for customer-facing use. The PDF is rendered by the document service and contains the header details, grouped line items, and financial totals.

**Required role(s):** Admin

### Estimate snapshots

An immutable snapshot of an estimate's complete state (all header fields and line items) can be created manually for audit and version history purposes. Snapshots are also created automatically when an estimate is submitted for approval.

**Required role(s):** Admin

---

## Work Orders

A work order is the live record that tracks a vehicle through the service process.

### Work order lifecycle

```
DRAFT → APPROVED → ASSIGNED → WORK_IN_PROGRESS → READY_FOR_PICKUP → COMPLETED
                 ↘                             ↗
                   AWAITING_PARTS / AWAITING_APPROVAL
```

Every transition is logged to the transition history. Cancelled is reachable from most states.

| Status              | Meaning                                                          |
| ------------------- | ---------------------------------------------------------------- |
| `DRAFT`             | Initial state; can transition to APPROVED or CANCELLED.          |
| `APPROVED`          | Customer has approved the work; can be assigned to a technician. |
| `ASSIGNED`          | Technician assigned; ready to start.                             |
| `WORK_IN_PROGRESS`  | Active work underway.                                            |
| `AWAITING_PARTS`    | Work paused; waiting for parts to arrive.                        |
| `AWAITING_APPROVAL` | Change request submitted; awaiting customer decision.            |
| `READY_FOR_PICKUP`  | Work complete; customer notified.                                |
| `COMPLETED`         | Vehicle collected; work order closed.                            |
| `CANCELLED`         | Cancelled.                                                       |

### Creating a work order

Work orders are normally created by promoting an approved estimate. They can also be created directly by providing an estimate ID and customer ID. Supports idempotent creation via `Idempotency-Key`.

**Required role(s):** Any authenticated user

### Viewing work orders

Work orders can be listed in full, retrieved by ID, or filtered by customer or location.

**Required role(s):** Admin

### Transition history and snapshots

The full state-transition history for a work order can be retrieved at any time. Snapshots capture a point-in-time record of the work order's state.

**Required role(s):** Any authenticated user

### Customer approval

The work order can be approved by the customer with a signature. This transitions the work order from `DRAFT` to `APPROVED`. Requires a customer ID, base64 signature, signer name, and optional notes.

**Required role(s):** Admin

### Completing a work order

Completing a work order transitions it to `COMPLETED` and emits a work-completed event. Before calling complete, use the completion preconditions endpoint to check whether all blocking conditions are satisfied (e.g. unresolved change requests, pending parts, un-acknowledged customer denials).

**Required role(s):** Admin

### Reopening a completed work order

A completed work order can be reopened if additional work is needed. A mandatory reason must be provided. This is a controlled, elevated action.

**Required role(s):** Admin

### Cancelling a work order

Cancel a work order by deleting it. Only works on work orders that have not yet been completed.

**Required role(s):** Any authenticated user

### Generating an invoice

Once a work order is in `COMPLETED` status, an invoice draft can be generated. The invoice is created by calling the invoice service. Supports idempotent generation via `Idempotency-Key`.

**Required role(s):** No standard role — the `workorder:workorder:generate_invoice` permission must be explicitly granted via Security Admin.

---

## Technician Assignment

A technician (mechanic) is assigned to a work order to record who is doing the work.

### Assigning a technician

Provide the work order ID and the technician's person ID. If the work order is in `APPROVED` status, it transitions to `ASSIGNED` on successful assignment. Supports `Idempotency-Key`.

**Required role(s):** No standard role — the `workorder:workorder:assign-technician` permission must be explicitly granted via Security Admin.

### Reassigning a technician

A work order can be reassigned to a different technician. The reassignment reason must be provided and is recorded in the assignment history.

**Required role(s):** No standard role — the `workorder:workorder:assign-technician` permission must be explicitly granted via Security Admin.

### Viewing assignment history

The current assignment and full assignment history for a work order can be retrieved at any time.

**Required role(s):** Admin

---

## Change Requests

A change request is raised when additional work is discovered during the service visit that was not included in the original estimate. The additional work requires customer approval before it can proceed.

### Creating a change request

Provide the work order ID and the change request items (description, parts, labor). Items are placed in `PENDING_APPROVAL` status. Supports `Idempotency-Key`.

**Required role(s):** Admin

### Approving a change request

Approve a pending change request. Items move to `READY_TO_EXECUTE` status. An approval note is required.

**Required role(s):** Admin

### Declining a change request

Decline a change request. Items are cancelled and will not be billed. A decline note is required.

**Required role(s):** Admin

### Emergency override

In urgent situations (e.g. a safety-critical repair that cannot wait for customer decision), a manager can apply an emergency override to a change request. Items move to `READY_TO_EXECUTE`. This operation requires elevated permission.

After an emergency override, the customer must acknowledge the denial before the work order can be closed.

**Required role(s):** Admin

### Acknowledging a customer denial

If a customer denies a change request for safety-critical work, the denial must be formally acknowledged by the shop before the work order can be completed. Any authenticated user can record the acknowledgment.

**Required role(s):** Any authenticated user

### Checking if a work order can close

Before completing a work order, use the `canClose` endpoint to verify there are no unresolved change requests, un-acknowledged denials, or other blocking conditions.

**Required role(s):** Any authenticated user

---

## Labor Tracking

Labor entries record when a technician starts and stops work on a service line.

### Starting a labor session

To start a labor session, provide the work order ID and service line ID. The system records the current timestamp as the start time and creates a labor entry.

**Required role(s):** Admin

### Stopping a labor session

Stop an active labor session by providing the work order ID and labor entry ID. The system calculates the total hours worked.

**Required role(s):** Admin

### Adjusting labor hours

If a labor entry needs correction, hours can be manually adjusted by providing the entry ID and the corrected values.

**Required role(s):** Admin

### Viewing labor history

All labor entries for a work order can be retrieved.

**Required role(s):** Admin

### Timer-based time tracking (mechanic self-service)

Technicians can start and stop their own timers without specifying a labor entry ID. The system resolves the mechanic's ID from the authenticated user's session.

- **Get active timers** — shows any running timers for the logged-in mechanic.
- **Start timer** — opens a new timer for the mechanic's current assignment.
- **Stop timer** — stops all active timers for the mechanic.

**Required role(s):** Admin

### Job time totals (reporting)

Aggregated hours worked across technicians and locations can be queried for a date range.

Parameters:

- `startDate` / `endDate` (required)
- `timezone` (required, IANA format, e.g. `America/Chicago`)
- `locationId` (optional)
- `technicianIds` (optional, list of person IDs to filter)

**Required role(s):** Admin

---

## Parts and Inventory

Parts usage on a work order is tracked through three stages: issuing from inventory, consuming against the work order, and returning any unused quantity.

### Issuing parts

Issue a part from inventory to the work order. This reserves the item for this job. Supports idempotency via `Idempotency-Key`.

**Required role(s):** Admin

### Consuming parts

Record the actual quantity of a part consumed during the service. Quantity cannot exceed the issued amount.

**Required role(s):** Admin

### Returning parts

Return unused parts back to inventory. Returns 201 on success.

**Required role(s):** Admin

### Parts usage history

Retrieve the full history of parts issued, consumed, and returned for a work order. An optional `partLineId` filter narrows the results to a specific line.

**Required role(s):** Admin

### Pick list

The pick list shows which inventory items need to be pulled from stock for a work order's parts lines.

- **Get pick list** — list all items that need to be picked.
- **Get pick tasks** — list the discrete pick tasks for warehouse execution.
- **Resolve scan** — record a barcode scan against a pick task line.
- **Confirm pick line quantity** — confirm the quantity picked for a specific line.
- **Complete pick task** — mark a pick task as fully fulfilled.

**Required role(s):** Admin

---

## WIP Dashboard

The WIP board provides a live view of all active work orders at a location.

- **List active WIP** — returns all in-progress work orders, paginated. Users with multi-location access see all locations; otherwise results are scoped to the user's assigned location.
- **Get WIP detail** — retrieve the full WIP record for a specific work order.

**Required role(s):** Admin

### Daily dispatch dashboard

The dispatch dashboard aggregates work order, mechanic, bay, and conflict data for a location and date. It is the primary view for the morning dispatch workflow.

Parameters:

- `locationId` (required)
- `date` (optional, defaults to today)

**Required role(s):** No standard role — the `workorder:dashboard:view` permission must be explicitly granted via Security Admin.

---

## Approval Configuration

Approval configurations define the rules for when and how estimates and change requests require customer approval. Different rules can be set for specific locations or customer accounts, with a system-level default as the fallback.

### Operations

- **List all configurations** — retrieve every approval configuration in the system.
- **Get configuration by ID** — retrieve a specific configuration.
- **Get applicable configuration** — given an optional location and customer ID, return the most specific matching rule set.
- **Create configuration** — add a new rule set.
- **Update configuration** — modify an existing rule set.
- **Delete configuration** — remove a rule set.

| Operation            | Required role(s) |
| -------------------- | ---------------- |
| View configurations  | Admin            |
| Create configuration | Admin            |
| Edit configuration   | Admin            |
| Delete configuration | Admin            |

---

## Invoice Generation

Once a work order is completed, an invoice draft is generated by the invoice service. The invoice is built from the approved and completed line items on the work order. Invoice generation supports idempotent retries via `Idempotency-Key`.

The work order must be in `COMPLETED` status. Attempting to generate an invoice for a work order in any other status returns 409.

Existing work order invoices can also be viewed.

| Operation              | Required role(s)                                                           |
| ---------------------- | -------------------------------------------------------------------------- |
| Generate invoice       | No standard role — grant `workorder:workorder:generate_invoice` explicitly |
| View workorder invoice | Admin                                                                      |
