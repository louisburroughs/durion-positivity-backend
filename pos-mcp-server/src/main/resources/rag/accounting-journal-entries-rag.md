---
rag_id: accounting.journal-entries
rag_scope: accounting
required_permissions:
  - accounting:je:view
---

## Purpose

RAG id: accounting.journal-entries
RAG scope: accounting
Required permissions: accounting:je:view
Audience: internal staff.

This document describes the implemented journal-entry posting and reversal lifecycle in pos-accounting.

## Endpoints, Permissions, and Events

Base path: /v1/accounting/journal-entries

| Operation | Method and path | Permission | EmitEvent id |
| --- | --- | --- | --- |
| List journal entries | GET /v1/accounting/journal-entries | accounting:je:view | ACCOUNTING_JOURNAL_ENTRY_LIST |
| Create journal entry | POST /v1/accounting/journal-entries | accounting:je:create | ACCOUNTING_JOURNAL_ENTRY_CREATE |
| Update draft journal entry | PUT /v1/accounting/journal-entries/{journalEntryId} | accounting:je:create | ACCOUNTING_JOURNAL_ENTRY_UPDATE |
| Post journal entry | POST /v1/accounting/journal-entries/{journalEntryId}/post | accounting:je:post | ACCOUNTING_JOURNAL_ENTRY_POST |
| Reverse posted journal entry | POST /v1/accounting/journal-entries/{journalEntryId}/reverse | accounting:je:reverse | ACCOUNTING_JOURNAL_ENTRY_REVERSE |

## Core Lifecycle Rules

- Post requires DRAFT status.
- Reverse requires POSTED status.
- Posting and reversal both pass through accounting period lock checks.
- Balance validation tolerance is 0.0001.
- Posted and reversed entries are immutable states.

## Numbering and Reversal Facts

- Entry number assignment occurs at post/reverse with month scope key JE-YYYYMM.
- Entry number format is JE-{YYYYMM}-{seq}.
- Reverse operation writes a new posted reversal entry and marks original as REVERSED with race-safe conditional update.

## Status and Type Tokens

JournalEntryStatus:
- DRAFT
- PENDING
- POSTED
- REVERSED

JournalEntryType:
- EVENT_DRIVEN
- MANUAL

ManualJEReasonCode:
- ACCRUAL_ADJUSTMENT
- ERROR_CORRECTION
- RECLASSIFICATION
- DEPRECIATION
- OTHER

## Error Tokens

- UNBALANCED_ENTRY
- ENTRY_ALREADY_POSTED
- JE_ALREADY_REVERSED
- JE_NOT_POSTED
- PERIOD_CLOSED
- PERIOD_HARD_LOCKED

## Verified Facts

- _Verified: pos-accounting JournalEntryController endpoint mappings, PreAuthorize permissions, and @EmitEvent ids._
- _Verified: pos-accounting JournalEntryServiceImpl enforces DRAFT-only post, POSTED-only reverse, and BALANCE_TOLERANCE = 0.0001._
- _Verified: pos-accounting JournalEntryServiceImpl assigns JE-{YYYYMM}-{seq} numbers during post/reverse._
- _Verified: pos-accounting AccountingPeriodGate and AccountingExceptionHandler period lock and error-code behavior._
