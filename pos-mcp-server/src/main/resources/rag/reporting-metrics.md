# Reporting Metrics Guide

## Purpose
RAG id: `reporting.metrics`  
RAG scope: `reporting`  
Required permissions: `accounting:report:export`, `workorder:dashboard:view`  
Audience: managers and internal reporting users.  
This document is reference context only and grants no access; access is enforced by permission codes at request time.

Reporting is split across services: financial/period reporting is gated by `accounting:report:export` (pos-accounting), operational shop reporting by `workorder:dashboard:view` (pos-workorder). There is no generic `reporting:*:view` code; these two are the verified reporting-read permissions.

_Verified against `pos-accounting` and `pos-workorder` permissions.yaml._

## Metric interpretation rules
Reporting answers must define scope, time window, entity state, numerator, denominator, and filters. A metric without a date range or location is incomplete. The assistant should distinguish operational snapshots from accounting-period reports and should state when a metric is a count, ratio, duration, or currency measure.

## Work in progress (WIP)
WIP means active workorders at a location. A WIP count should identify the included statuses and whether waiting-for-parts, quality-check, ready-for-pickup, reopened, or invoiced items are included. If the source only provides a dashboard snapshot, the assistant should not describe it as a historical trend.

## Cycle time
Cycle time is the elapsed time from a defined start event to a defined end event. In shop operations, common choices are appointment check-in to completion, workorder creation to completion, or work start to ready-for-pickup. The assistant must state which definition was used. Do not compare cycle-time metrics unless their start/end events match.

## Throughput
Throughput is the number of completed units in a defined period. The unit may be completed workorders, completed appointments, invoices finalized, or vehicles ready for pickup. The assistant should not mix completed workorders with invoices unless the user asks for invoice throughput.

## Utilization
Utilization measures scheduled or active use of a resource against available capacity. For bays and mobile units, define available hours, scheduled hours, and active work hours. For technicians, define paid hours, scheduled hours, and labor-entry hours. If HR availability is included in schedule overlay, state that dependency.

## First-time completion / rework
First-time completion measures jobs completed without a reopen. Reopen IS a real, verified event: `WORKORDER_REOPEN` ("reopen a completed workorder with mandatory reason," permission `workorder:workorder:reopen_completed`) and `WORKORDER_ESTIMATE_REOPEN` (reopen a declined estimate to DRAFT, `workorder:estimate:reopen`). Note: `WorkorderStatus` has NO `REOPENED` value — `COMPLETED` is terminal and reopen is modeled as an event, not a status. The terms "comeback" / "rework" do NOT exist in the backend; define them only as business concepts and base any metric on the reopen events above.

_Verified: `pos-workorder` `EventTypes.java` (`WORKORDER_REOPEN`, `WORKORDER_ESTIMATE_REOPEN`); `WorkorderStatus` (no REOPENED)._

## Parts-related metrics
Inventory metrics from the existing inventory RAG include inventory turnover, DIO, fill rate, stockout rate, and shrinkage. For shop reporting, parts blockers include waiting-for-parts count, pick-list completion, stockout-related delay, and parts consumption by workorder. The assistant should separate operational availability from financial inventory valuation.

## Financial reporting metrics
Accounting RAG defines revenue, expenses, receivables, payables, journal entries, and posted-entry immutability. Financial reports must use accounting-period boundaries and posted or finalized states as verified by source. Do not treat estimate amount as revenue or unfinalized invoice as posted accounting impact unless the source confirms it.

## Common reporting questions
| Question | Required interpretation |
|---|---|
| "How busy is the shop today?" | Location, date, scheduled appointments, WIP, bay/mobile utilization. |
| "How many jobs did we finish this week?" | Date range, completion event, location(s), excluded reopened/cancelled items. |
| "Why is cycle time high?" | Compare blocked states: waiting for parts, approval delay, scheduling conflict, labor gap, quality check. |
| "Which location is behind?" | Requires comparable scope and all-location visibility. |
