# Natural-Language Assistant Capability Catalog

## Purpose
RAG id: `platform.capability-catalog`  
RAG scope: `master`  
Required permissions: `AUTHENTICATED`  
Audience: internal staff and admins.  
This document is reference context only and grants no access; access is enforced by permission codes at request time.

This catalog explains what internal users can ask the natural-language assistant and what kind of response to expect. It is not a promise that every requested action can be performed automatically. The assistant should answer inside the caller's security context, cite retrieved context when available, and say when a request requires a permission or source that is not available.

## Service advisor examples
A service advisor typically asks about customers, vehicles, estimates, workorders, appointments, parts availability, invoice status, and next actions.

Example requests:

| Staff phrasing | Expected answer |
|---|---|
| "Show me what's open for this customer." | A concise list of known appointments, estimates, active workorders, invoices, or flags the assistant can retrieve. |
| "What do I need before I can turn this estimate into a workorder?" | The required approval, customer/vehicle linkage, line items, and scheduling dependencies. |
| "Can I reschedule this appointment?" | Current appointment state, whether the state appears reschedulable, and the permission or manager action needed if blocked. |
| "What parts are needed for this workorder?" | Parts lines or pick-list context if available; otherwise a request for the workorder identifier. |
| "Why can't this workorder be invoiced?" | Missing completion, approval, parts/labor, or invoice readiness conditions that can be verified. |

## Technician examples
A technician asks operational questions about assigned work, labor, parts, WIP state, vehicle context, and change requests.

| Staff phrasing | Expected answer |
|---|---|
| "What am I working on next?" | Assigned appointments or workorders from schedule/WIP context, if visible to the caller. |
| "What parts are on this job?" | Parts lines, pick-list status, or a note that inventory details require inventory visibility. |
| "How do I add labor?" | The labor entry concept, required workorder context, and permission needed for the action. |
| "This job needs more work. What do I do?" | Create or request a change request; do not proceed without customer approval where required. |
| "Why is this waiting for parts?" | The workorder state, related parts demand, reservations, or TODO if the part source cannot be verified. |

## Dispatcher examples
A dispatcher asks about appointment timing, bays, mobile units, mechanics, conflicts, and the daily schedule.

| Staff phrasing | Expected answer |
|---|---|
| "What does the schedule look like for bay 2 today?" | A schedule-view style answer filtered by date, location, and resource when available. |
| "Can I move this appointment to 3 PM?" | Conflict and state considerations; reschedule permission requirements; audit reason if override is needed. |
| "Who is assigned to this appointment?" | Current bay/mobile unit and mechanic assignment context, if visible. |
| "Which jobs are waiting for parts?" | WIP or appointment/workorder statuses that indicate part dependency. |

## Location manager examples
A location manager asks about shop throughput, WIP, resource utilization, overrides, approvals, and blocked work.

| Staff phrasing | Expected answer |
|---|---|
| "What's blocking the shop right now?" | Active blockers: waiting for parts, pending approval, resource conflicts, incomplete labor, or invoice readiness. |
| "Who overrode this schedule conflict?" | Audit/event guidance and the identifiers needed to reconstruct the action. |
| "Which workorders need approval?" | Workorder, estimate, or change-request approval state if visible. |
| "What can I approve as manager?" | Permission-scoped approval concepts and a reminder that role names are not the visibility gate. |

## Account manager examples
An account manager asks about commercial accounts, invoices, billing readiness, customer status, and claim or account codes.

| Staff phrasing | Expected answer |
|---|---|
| "What invoices are tied to this account?" | Invoice/account summary if visible; if not, the assistant should ask for an invoice or account identifier. |
| "Why didn't this customer get billed?" | Workorder completion, invoice generation, finalization, or missing account/billing-rule context. |
| "What does this claim code refer to?" | Identifier interpretation if the code format is known; TODO if the claim-code source is not verified. |

## Accounting associate examples
An accounting associate asks about journal entries, AP, reconciliation, invoice posting, double-entry impacts, and exceptions.

| Staff phrasing | Expected answer |
|---|---|
| "What journal entry should this invoice create?" | A conceptual debit/credit explanation grounded in accounting rules; no posting unless permitted. |
| "Why is this receipt not reconciled?" | PO, goods receipt, vendor invoice, and AP handoff checks. |
| "Show the accounting impact of parts used on a workorder." | Inventory reduction, COGS, revenue/receivable concepts where supported. |
| "Can this posted entry be changed?" | Posted entries are immutable; corrections require reversing or compensating entries. |

## Admin examples
An admin asks about permission visibility, governance, preload RAG docs, audit trails, user setup, and blast radius.

| Staff phrasing | Expected answer |
|---|---|
| "Why can't this user see the admin docs?" | Required permission metadata and caller permission-code intersection. |
| "What permissions does this role need?" | Role-permission mapping if available; TODO for any unverified code. |
| "What happened to entity X?" | Event/audit reconstruction steps using entity identifiers, timestamps, actor, source, and correlation IDs when available. |
| "Can the assistant perform this action?" | The required permission, source grounding, and whether the action should be read-only, submitted, or escalated. |
