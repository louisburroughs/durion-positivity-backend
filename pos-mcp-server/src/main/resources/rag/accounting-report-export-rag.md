---
rag_id: accounting.report-export
rag_scope: accounting
required_permissions:
  - accounting:report:export
---

## Purpose

RAG id: accounting.report-export
RAG scope: accounting
Required permissions: accounting:report:export
Audience: internal staff.

This document describes report export request, status, and download behavior implemented in pos-accounting.

## Endpoints, Permissions, and Events

Base path: /v1/accounting/reports/export

| Operation | Method and path | Permission | EmitEvent id |
| --- | --- | --- | --- |
| Request export | POST /v1/accounting/reports/export | accounting:report:export | ACCOUNTING_REPORT_EXPORT_REQUEST |
| Get export status | GET /v1/accounting/reports/export/{exportId} | accounting:report:export | ACCOUNTING_REPORT_EXPORT_STATUS |
| Download export | GET /v1/accounting/reports/export/{exportId}/download | reporting:view:financial-statements | ACCOUNTING_REPORT_EXPORT_DOWNLOAD |
| List exports | GET /v1/accounting/reports/export | accounting:report:export | ACCOUNTING_REPORT_EXPORT_LIST |

## Runtime Behavior

- Request flow currently renders in-process and stores terminal result.
- Download is available only for COMPLETED exports.
- Export artifacts are kept in bounded in-memory storage.

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

## Implementation Notes

- Current implementation supports CSV and PDF rendering paths.
- Unsupported formats transition to FAILED.

## Verified Facts

- _Verified: pos-accounting ReportExportController endpoint mappings, permission guards, and event ids._
- _Verified: pos-accounting ReportExportServiceImpl request/render/status/download flow and terminal-state handling._
- _Verified: pos-accounting ExportFormat and ExportStatus enum tokens._
