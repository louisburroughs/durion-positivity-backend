# Role to Permission Matrix

## Purpose
RAG id: `security.role-permission-matrix`  
RAG scope: `security`  
Required permissions: `security:role:view`, `security:permission:view`  
Audience: admin/security users.  
This document is reference context only and grants no access; access is enforced by permission codes at request time.

This matrix catalogs role-to-permission information visible in the supplied RAG bundle. It is not a complete security source. Gate 5 visibility must use caller permission codes, not role names alone.

## AUTHENTICATED
Authenticated users may receive general RAG reference context tagged `AUTHENTICATED`, such as the capability catalog, cross-domain playbooks, and glossary. Authentication does not imply access to domain records or admin/security documents.

## ADMIN
ADMIN has high blast-radius. Verified `pos-security-service` permissions include the security catalog reads and admin actions: `security:role:view`, `security:role:create`, `security:role:edit`, `security:role:assign`, `security:permission:view`, `security:permission:register`, `security:user:view`, `security:audit:view`, `security:audit:export`. (HR/people admin actions live in `pos-people`.)

_Verified against `pos-security-service/src/main/resources/permissions.yaml`. Note: there is no `security:user:create` — user creation/state is `security:user:view` + `security:user_account_state:view` plus role assignment._

## SERVICE_ADVISOR
The shop guide states SERVICE_ADVISOR has shop/workorder access for service advisor tasks. Verified examples include viewing shop location, bays/mobile units, creating appointments, viewing appointments, rescheduling/cancelling appointments, viewing assignments, conflict override through schedule editing or reschedule permission, viewing schedule, estimates, and workorder operational context where permitted.

Representative permissions visible in the bundle: `shop:location:view`, `shop:bay:view`, `appointments:create`, `appointments:view`, `appointments:reschedule`, `appointments:cancel`, `shop:schedule:view`, `workorder:workorder:view`, `workorder:estimate:create`.

## TECHNICIAN
The shop guide states TECHNICIAN can retrieve appointment/schedule context and workorder operational context where permitted. Technician questions usually involve assigned work, WIP, parts, and labor. Representative visible permissions include `appointments:view`, `shop:schedule:view`, `workorder:workorder:view`, `workorder:labor:view`, `workorder:parts:view`, and `workorder:wip:view`.

> TODO(verify): whether `workorder:labor:add` and `workorder:parts:add` are assigned to TECHNICIAN by default.

## LOCATION_MANAGER
The shop guide states LOCATION_MANAGER has broad location-level shop authority, including view/create/edit bays and mobile units, create/reschedule/cancel appointments, assign bays/mechanics, view schedules, and override conflicts. Representative permissions include `shop:location:view`, `shop:bay:view`, `shop:bay:create`, `shop:bay:edit`, `shop:bay:assign`, `appointments:create`, `appointments:view`, `appointments:reschedule`, `appointments:cancel`, `shop:schedule:view`, and `shop:schedule:edit`.

## ACCOUNTING_ASSOCIATE
The accounting RAG lists ACCOUNTING_ASSOCIATE as day-to-day accounting operations with view and AP operations. Visible permissions include `accounting:coa:view`, `accounting:mapping:view`, `accounting:posting_rules:view`, `accounting:je:view`, `accounting:events:view`, `accounting:export:view`, `accounting:ap:view`, `accounting:ap:approve`, `accounting:ap:reject`, and `accounting:ap:pay`.

## GL_ANALYST
The accounting RAG lists GL_ANALYST for GL setup, mappings, and draft entries. Visible permissions include view/create/edit variants for COA and mappings, `accounting:posting_rules:view`, `accounting:posting_rules:create`, `accounting:je:view`, `accounting:je:create`, `accounting:events:view`, `accounting:events:submit`, `accounting:export:view`, and `accounting:ap:view`.

> TODO(verify): individual create/edit/submit codes because the PDF text compresses some as `view/create/edit`.

## AP_CLERK
The accounting RAG states AP_CLERK includes all GL_ANALYST permissions plus `accounting:ap:approve`, `accounting:ap:reject`, and `accounting:ap:pay`.

## ACCOUNTANT
The accounting RAG states ACCOUNTANT includes all AP_CLERK permissions plus `accounting:coa:deactivate`, `accounting:mapping:deactivate`, `accounting:posting_rules:publish`, `accounting:je:post`, `accounting:je:reverse`, `accounting:events:retry`, and `accounting:events:reprocess`.

## CONTROLLER
The accounting RAG states CONTROLLER includes all ACCOUNTANT permissions plus `accounting:posting_rules:archive` and `accounting:export:request`.

## ACCOUNT_MANAGER
The accounting RAG aligns ACCOUNT_MANAGER more with commercial account and invoice operations than pure ledger administration. It states ACCOUNT_MANAGER has all ACCOUNTANT permissions plus `invoice:manage` and `invoice:billing-rules`.

_Verified: `invoice:billing-rules` is the exact code in `pos-invoice/src/main/resources/permissions.yaml` (alongside `invoice:manage`, `invoice:finalize`, `invoice:finalize:override`). Some invoice codes are intentionally two-part, not three-part._

## MCP/NLTI permissions
The supplied permission samples include `mcp:chat:execute`, `nlti:request:submit`, and `nlti:audit:read`. The accounting RAG states every built-in accounting role also receives `mcp:chat:execute`. Use `nlti:audit:read` for audit/observability RAG visibility when verified.

## Permission gaps to verify
The bundle does not verify a generic reporting read code, a tax read code, or complete admin/security read codes. Do not fabricate `reporting:*:view`, `tax:*:view`, `admin:*:*`, or `security:*:view` codes. Use TODO markers until the repository permissions files confirm them.
