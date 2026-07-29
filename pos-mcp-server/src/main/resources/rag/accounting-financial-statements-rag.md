---
rag_id: accounting.financial-statements
rag_scope: accounting
required_permissions:
  - reporting:view:financial-statements
---

## Purpose

RAG id: accounting.financial-statements
RAG scope: accounting
Required permissions: reporting:view:financial-statements
Audience: internal staff.

This document covers implemented financial statement/report endpoints in pos-accounting and how values are derived from posted journal entries and GL account metadata.

## Endpoints and Events

Base path: /v1/accounting/reports/financial

| Operation | Method and path | Permission | EmitEvent id |
| --- | --- | --- | --- |
| Income statement | GET /income-statement | reporting:view:financial-statements | REPORT_INCOME_STATEMENT_GENERATE |
| Balance sheet | GET /balance-sheet | reporting:view:financial-statements | REPORT_BALANCE_SHEET_GENERATE |
| Trial balance | GET /trial-balance | reporting:view:financial-statements | REPORT_TRIAL_BALANCE_GENERATE |
| Account drilldown | GET /drilldown/accounts | reporting:view:financial-statements | REPORT_DRILLDOWN_ACCOUNTS |
| Journal-line drilldown | GET /drilldown/journal-lines | reporting:view:financial-statements | REPORT_DRILLDOWN_JOURNAL_LINES |
| General ledger | GET /general-ledger | reporting:view:financial-statements | REPORT_GENERAL_LEDGER_GENERATE |
| Aged receivables | GET /aged-receivables | reporting:view:financial-statements | REPORT_AGED_RECEIVABLES_GENERATE |
| Aged payables | GET /aged-payables | reporting:view:financial-statements | REPORT_AGED_PAYABLES_GENERATE |
| Tax liability | GET /tax-liability | reporting:view:financial-statements | REPORT_TAX_LIABILITY_GENERATE |

## Data Derivation Facts

- Financial statement aggregation queries use POSTED journal entries.
- Income statement and balance sheet totals are derived from account-type/subtype mapped balances.
- Trial balance is grouped by account from posted debit/credit totals.
- General ledger uses posted entries in date range and computes running balance.
- Tax liability reconciliation includes Sales Tax Payable account code 2200.

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

## Verified Facts

- _Verified: pos-accounting FinancialReportingController endpoint mappings, permission guards, and event ids._
- _Verified: pos-accounting FinancialReportingServiceImpl and JournalEntryRepository use posted-entry aggregations for statements._
- _Verified: pos-accounting GLAccount entity and enums define account type/subtype/status tokens used in report mapping._
