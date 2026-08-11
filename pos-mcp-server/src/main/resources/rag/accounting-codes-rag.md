---
rag_id: accounting.codes
rag_scope: accounting
required_permissions:
  - accounting:je:view
---

## Purpose

RAG id: accounting.codes
RAG scope: accounting
Required permissions: accounting:je:view
Audience: internal staff.

Token catalog for accounting-domain lexical retrieval.

## Wave 4 Permission Tokens

- accounting:je:view
- accounting:je:create
- accounting:je:post
- accounting:je:reverse
- accounting:period:override
- accounting:report:export
- reporting:view:financial-statements

## Journal Tokens

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

## Account Tokens

AccountType:
- ASSET
- LIABILITY
- EQUITY
- REVENUE
- EXPENSE

AccountSubtype:
- RECEIVABLE
- PAYABLE
- BANK_CASH
- UNDEPOSITED_FUNDS
- TAX_PAYABLE
- CURRENT_ASSET
- FIXED_ASSET
- CURRENT_LIABILITY
- SALES
- COST_OF_SALES
- OPERATING_EXPENSE
- OTHER

GLAccountStatus:
- ACTIVE
- INACTIVE
- NOT_YET_ACTIVE

## Export Tokens

ExportFormat:
- PDF
- CSV
- XLSX
- JSON

ExportStatus:
- PENDING
- IN_PROGRESS
- COMPLETED
- FAILED

## Event Tokens

- ACCOUNTING_JOURNAL_ENTRY_LIST
- ACCOUNTING_JOURNAL_ENTRY_CREATE
- ACCOUNTING_JOURNAL_ENTRY_UPDATE
- ACCOUNTING_JOURNAL_ENTRY_POST
- ACCOUNTING_JOURNAL_ENTRY_REVERSE
- REPORT_INCOME_STATEMENT_GENERATE
- REPORT_BALANCE_SHEET_GENERATE
- REPORT_TRIAL_BALANCE_GENERATE
- REPORT_DRILLDOWN_ACCOUNTS
- REPORT_DRILLDOWN_JOURNAL_LINES
- REPORT_GENERAL_LEDGER_GENERATE
- REPORT_AGED_RECEIVABLES_GENERATE
- REPORT_AGED_PAYABLES_GENERATE
- REPORT_TAX_LIABILITY_GENERATE
- ACCOUNTING_REPORT_EXPORT_REQUEST
- ACCOUNTING_REPORT_EXPORT_STATUS
- ACCOUNTING_REPORT_EXPORT_LIST
- ACCOUNTING_REPORT_EXPORT_DOWNLOAD

## Verified Facts

- _Verified: pos-accounting permissions.yaml permissions relevant to journal/report/export surfaces._
- _Verified: pos-accounting enums JournalEntryStatus, JournalEntryType, ManualJEReasonCode, AccountType, AccountSubtype, GLAccountStatus, ExportFormat, ExportStatus._
- _Verified: pos-accounting EventTypes registry and controller @EmitEvent ids for listed tokens._
