# Events and Observability Guide

## Purpose
RAG id: `events.observability`  
RAG scope: `events`  
Required permissions: `nlti:audit:read`  
Audience: admins and managers.  
This document is reference context only and grants no access; access is enforced by permission codes at request time.

This document grounds questions such as "what happened to this workorder," "who changed this appointment," "why did inventory change," and "why did the assistant return this answer." It focuses on audit reconstruction and event interpretation.

## Event model concept
The existing accounting and inventory RAG documents both emphasize immutability, auditability, idempotency, and event-driven movement modeling. Inventory changes should be represented as immutable events such as received, consumed, transferred, or adjusted. Accounting entries should be traceable and posted entries should not be edited. The assistant should use the same pattern across domains: identify the entity, collect events, order them by time, and explain the resulting state.

## Event types to look for
Common event categories:

- Workorder lifecycle: estimate submitted, estimate approved, promoted to workorder, assignment, labor added, parts added, change request created/approved/declined, completed, invoice generated, reopened.
- Appointment/schedule: created, assigned, rescheduled, cancelled, conflict override, checked in, status change.
- Inventory: received, reserved, picked, consumed, transferred, adjusted, returned, scrapped.
- Order/procurement: order created, line added, PO issued, goods received, receipt exception, reconciliation event.
- Invoice/accounting: invoice generated, finalized, payment received, journal entry created, posted, reversed, AP approved/rejected/paid.
- Security/admin: user created, role assigned, permission changed, approval configuration changed.
- MCP/NLTI: chat executed, request submitted, audit read.

## Verified facts (pos-events / pos-event-receiver / pos-mcp-server / pos-security-service)
- **Emission mechanism:** methods annotated `@EmitEvent(id=..., apiVersion="1")`; an aspect publishes an `EventEmitted` domain event. Event types are declared as `EventTypeRegistration` constants in per-service `*EventTypes.java` registries (owning service = the module). Naming convention `{DOMAIN}_{RESOURCE}_{ACTION}` — e.g. `WORKORDER_CREATE`, `WORKORDER_APPROVE`, `WORKORDER_REOPEN` (pos-workorder); `ORDER_PRICE_OVERRIDE_APPLY/APPROVE/REJECT` (pos-order). Treat specific names as verified only against the owning `*EventTypes.java`.
- **Two payload shapes (distinct):**
  1. *Telemetry* (`EventEmitted`/`EmittedEvent`, pos-event-receiver): `eventId`, `id`, `apiVersion`, `timestamp`, `elapsedMs`, `publishedAt` — latency/volume only; NO entity id/actor/correlation. Rolled up hourly (count, avg/p95/p99 elapsed).
  2. *Audit ledger* (the real "who did what to which entity"): NLTI `nlti_audit_event` (`correlationId`, `sessionId`, `requestId`, `eventType` ∈ {REQUEST,INTENT,PLAN,CONFIRMATION,EXECUTION_STEP,EXECUTION_COMPLETE,EXECUTION_FAILED}, `actorSubjectId`, `payloadRef/Hash`); security audit search supports `actorId`, `aggregateId`, `workorderId`, `productId`, `movementId`, `correlationId`, date range.
- **Reconstruct "what happened to entity X":** pos-event-receiver only serves time-window aggregates (`/v1/events/summary/lastHour|lastDay|lastWeek`), NOT per-entity history. Use the audit ledgers: `GET /v1/audit/events` (pos-security-service, filter by aggregateId/workorderId/productId/actorId/correlationId) and `GET /v1/nlt/audit` (pos-mcp-server, by correlationId/eventType/date).
- **Permissions:** `nlti:audit:read` (NLTI ledger), `security:audit:view` (security audit), `security:audit:export`, `crm:integration:audit` (CRM ingestion). 

> TODO(verify): exact event-type constant names per owning `*EventTypes.java` before quoting a specific one to a user.

## Reconstructing "what happened to entity X"
The assistant should reconstruct a timeline rather than jump to a conclusion.

1. Normalize the identifier: workorder number, appointment id, invoice number, PO number, SKU, VIN, account code, claim code, user id, or RAG document id.
2. Identify the owning domain and likely related domains.
3. Retrieve or ask for events in time order.
4. Extract actor, timestamp, action, source system, previous state, new state, reason, and correlation/idempotency key when present.
5. Identify gaps: missing event, duplicate event, out-of-order event, failed validation, or permission-blocked source.
6. Explain the current state as the cumulative result of the verified events.

## Interpreting idempotency and duplicates
The existing shop guide states appointment creation supports an `Idempotency-Key` header. When a user reports duplicate appointments, duplicate orders, duplicate receipts, or duplicate journal entries, the assistant should check whether an idempotency key or source request id exists. It should not assume duplicate posting without event evidence.

## Observability for RAG and assistant answers
Gate 5 changes retrieval visibility from role-only to permission-aware filtering. To explain an assistant answer, look for query scope, retrieved document ids, `rag_scope`, required permissions, caller permission codes, dense/lexical retrieval candidates, rerank result, and final tool/API grounding. A document with no matching permission should not be visible unless it is public/authenticated context.

## Common event-tracing questions
| Question | Event path |
|---|---|
| "Who changed this appointment?" | Appointment events, reschedule/cancel/assignment events, actor, timestamp, reason. |
| "Why did on-hand inventory drop?" | Inventory consumption, adjustment, transfer, return, scrap, or workorder parts event. |
| "Why can't this invoice be finalized?" | Workorder completion, invoice generation, pending change request, accounting or validation events. |
| "Why did the assistant hide an admin doc?" | RAG document metadata and caller permission-code intersection. |
