# Domain Map: durion-positivity-backend

Generated: 2026-07-17 (Phase 1 - Repo Domain Analyst)
Inputs used: [docs/sre-todo.md](../sre-todo.md), [docs/domain/brownfield-assessment.md](brownfield-assessment.md), [docs/generated/audit/current/context.json](../generated/audit/current/context.json), [AGENTS.md](../../AGENTS.md), [CLAUDE.md](../../CLAUDE.md), [docs/ARCHITECTURE_GUIDE.md](../ARCHITECTURE_GUIDE.md), controller source across all `pos-*` services.

## Service identity

| Field | Value |
|---|---|
| `service.name` | `durion-positivity-backend` |
| `service.team` | `TBD` (unresolved - see Gaps) |
| `service.tier` | `gold` (per `sre.config.yaml`) |
| Repository | `https://github.com/louisburroughs/durion-positivity-backend.git` |
| Branch / commit | `main` @ `cce505c0c` |
| Mode | consumed (`framework.path: .sre`, `outputs.root: .`) |
| Stack | Java 25 / Spring Boot 4.0.x, Maven multi-module reactor, PostgreSQL/TimescaleDB, Kafka, Eureka, Spring Cloud Gateway |
| Instrumentation state | `api-only-no-spans` (all 28 services) - Grafana OTel Java Agent v2.9.0, agent-only, preserve-and-extend (see [brownfield-assessment.md](brownfield-assessment.md)) |

This is a domain-driven, event-sourced POS platform built as ~28 independently deployable Spring Boot microservices (`pos-*`) plus `pos-api-gateway`, each owning its own database schema. Cross-domain communication is REST-through-gateway or load-balanced client calls for a small set of utility modules, and **Kafka domain events for all other module-to-module communication** (ADR-0044: event-only domain walls).

## Bounded contexts

Grouped by business capability. `Tier` reflects revenue/transaction criticality per the prioritization guidance (order, price, inventory, accounting, customer first).

Frontend feature(s) is the reverse of the [Feature-to-bounded-context mapping](#feature-to-bounded-context-mapping)
table in the Frontend section below - inverted here so each backend context row shows which UI feature
module(s) drive it. `-` means the context has no frontend feature surface (backend/system-to-system only).

| # | Bounded context | Service(s) | Tier | Responsibility | Frontend feature(s) |
|---|---|---|---|---|---|
| 1 | **Order Management** | `pos-order` | 1 | Sales-order/cart lifecycle, cart line management, price-override approval, order cancellation | `order` |
| 2 | **Pricing & Promotions** | `pos-price` | 1 | Contextual price quoting, promotion offers/eligibility, price restrictions, base-price normalization | `product` (price books/MSRP/location price overrides tab) |
| 3 | **Catalog & Product Master** | `pos-catalog` | 1 | Product/service/non-inventory item master, price books, item costs, UOM conversions, MSRP | `product` |
| 4 | **Inventory & Warehouse Operations** | `pos-inventory` | 1 | Availability, reservations/allocations, ASN/receiving, putaway, picking, cycle counts, purchase orders, replenishment, returns, shortages | `inventory`; `product` (availability/feeds tab) |
| 5 | **Accounting & Financial Reporting** | `pos-accounting` | 1 | GL accounts, journal entries, AP payments, vendor-bill matching, posting rules, financial statements | `accounting` |
| 6 | **Customer & CRM** | `pos-customer` | 1 | Party/account management, tier resolution, vehicle linkage, communication preferences, promotion redemption | `crm` |
| 7 | **Invoicing & Payments** | `pos-invoice` | 1 | Invoice finalize/revert, payment capture/void/refund, receipts, billing rules | `billing`; `workexec` (invoice-finalization step) |
| 8 | **Work Order & Service Execution** | `pos-workorder` | 1 | Estimates, workorder lifecycle, technician assignment, labor/parts tracking, change requests | `workexec` |
| 9 | **Shop Scheduling & Appointments** | `pos-shop-manager` | 2 | Appointments, technician assignment, schedule views, shop audit | `shopmgmt` |
| 10 | **Warranty Claims** | `pos-warranty` | 2 | Claim submission/eligibility/decision, settlements, part returns, provider/policy config | `-` |
| 11 | **Vehicle Inventory** | `pos-vehicle-inventory` | 2 | Owned-vehicle registry, VIN lookup, vehicle search, preferences | `crm` (create-vehicle) |
| 12 | **Vehicle Fitment** | `pos-vehicle-fitment` | 2 | Fitment hints, applicability filtering, manufacturer/make/model/vehicle-type taxonomy | `-` |
| 13 | **Vehicle Reference Data** | `pos-vehicle-reference-carapi`, `pos-vehicle-reference-nhtsa` | 3 | Read-only external vehicle-taxonomy lookups (CarAPI, NHTSA) | `-` |
| 14 | **People & Workforce Management** | `pos-people` | 2 | Employees, staffing assignments, time entries/adjustments, timekeeping approvals, work sessions | `people` |
| 15 | **People Identity & Contact** | `pos-people-contact` | 2 | Person profile, contact points, user-person linking, access-role assignment | `people` |
| 16 | **Location & Facilities** | `pos-location` | 2 | Locations, bays, mobile units + coverage rules, service areas, storage-location topology | `location`; `inventory` (by-location views); `product` (locations-roster tab) |
| 17 | **Tax Calculation** | `pos-tax` | 1 | Checkout/estimate tax calculation (transaction-critical support service) | `-` (invoked transparently during checkout/estimate calc, no dedicated UI page) |
| 18 | **Security & Identity** | `pos-security-service` | 1 | Authentication, JWT issuance/refresh, RBAC permission/role registry, authorization decisions, audit | `auth`; `security` |
| 19 | **API Gateway** | `pos-api-gateway` | 1 | Security boundary - JWT validation, `X-API-Version` header-to-path rewrite, authority header injection, request routing to Eureka-registered services | `-` (transport layer beneath every feature via `ApiBaseService`, no dedicated UI page) |
| 20 | **Domain Event Backbone** | `pos-event-receiver` | 2 | `@EmitEvent` audit-event ingestion, event-type registry (used by every module's `{Module}EventTypeInitializer`) | `accounting` (ingestion-monitor / event-envelope-contract pages) |
| 21 | **Bulk Data Ingestion** | `pos-bulk-loader` | 3 | TUS resumable upload, column mapping, job processing, review/correction queue for bulk imports | `bulk-import` |
| 22 | **Document Generation** | `pos-documents` | 3 | PDF rendering service | `-` |
| 23 | **Image Management** | `pos-image` | 3 | Product/document image retrieval by id or filename | `-` |
| 24 | **GenAI Assistant (NLTI)** | `pos-mcp-server` | 2 | Natural-language tool-invocation (NLTI) chat, streaming chat, tool-permission gating, system prompts, RAG document ingestion (LangChain4j + Ollama) | `shell` (chat panel, RAG ingest dialog) |

**Infrastructure-only, no business operations cataloged:**
- `pos-service-discovery` - Eureka server (`@EnableEurekaServer`), no controllers, no business logic.

**Non-deployed shared libraries** (not bounded contexts): `pos-archunit`, `pos-bulk-ingest-lib`, `pos-dependencies`, `pos-document-helper`, `pos-domain-events`, `pos-security-common`, `pos-shared-dtos`, `pos-tax-common`, `pos-coverage-aggregate`. `pos-events` also falls in this group (AOP `@EmitEvent` annotation module) despite appearing in the Planner's 28-service `detected_services` list with a Dockerfile - it has no `@RestController`/`@SpringBootApplication` in `src/main` and is not a business bounded context (see Gaps).

## Entity relationships

```
Party 1--* Vehicle                          (customer : owns)
Party 1--* SalesOrder                       (customer : places)
Party 1--* Workorder                        (customer : requests service for)
Account 1--1 Party                          (billing account : resolves to)
SalesOrder 1--* SalesOrderLine               (cart : contains)
SalesOrderLine *--1 Product                 (line : references)
SalesOrder 1--0..1 PriceOverride             (cart : may have)
SalesOrder 1--* Reservation                 (cart : reserves)
Estimate 1--* EstimateLine                  (estimate : contains)
Estimate 1--0..1 Workorder                   (estimate : promotes to)
Workorder 1--* LaborEntry                    (workorder : tracks)
Workorder 1--* PartUsage                     (workorder : consumes)
Workorder 1--0..1 Invoice                    (workorder : generates)
Workorder *--1 Vehicle                       (workorder : services)
Invoice 1--* Payment                         (invoice : collects)
Payment 1--0..1 Refund                       (payment : may reverse to)
Payment *--1 JournalEntry                    (payment : posts as)
VendorBill *--1 PurchaseOrder                (bill : matches)
PurchaseOrder 1--* ASN                       (PO : receives via)
ASN 1--* GoodsReceipt                        (ASN : produces)
GoodsReceipt 1--* InventoryLedgerEntry        (receipt : posts to)
Reservation *--1 InventoryLedgerEntry         (reservation : draws from)
Claim *--1 Workorder                         (warranty claim : originates from)
Claim 1--0..1 Settlement                     (claim : resolves to)
Claim 1--* PartReturn                        (claim : returns)
Product *--1 PriceBook                       (product : priced via)
Product *--0..1 FitmentHint                   (product : applicable to)
Employee 1--1 Person                         (employee record : links to)
Person 1--0..1 UserPersonLink                 (person : linked to auth user)
NltiRequest *--1 ChatSession                  (NLTI request : belongs to)
```

## Critical user journeys (CUJs)

Each step cites the operation `id` cataloged in [operations.yaml](operations.yaml).

### CUJ-1: Parts Counter Sale (Order → Price → Inventory → Invoice → Accounting)
1. Clerk creates a sales cart for the customer - `create-sales-order-cart` (pos-order)
2. Clerk adds a line item; price is quoted and stock checked - `add-sales-order-item` (pos-order), `calculate-price-quote` (pos-price), `check-inventory-availability` (pos-inventory)
3. A promotion is applied to the cart - `apply-promotion-offer` (pos-price)
4. Stock is reserved against the cart - `reserve-inventory` (pos-inventory)
5. Checkout finalizes the invoice - `finalize-invoice` (pos-invoice)
6. Customer pays - `capture-payment` (pos-invoice)
7. Revenue/COGS post to the GL (async, via domain event) - `reconcile-invoice-event` → `post-journal-entry` (pos-accounting)

### CUJ-2: Vehicle Service Estimate to Invoice (Workorder journey)
1. Advisor creates a repair estimate for the vehicle - `create-estimate` (pos-workorder), priced via `calculate-price-quote` (pos-price) and taxed via `calculate-tax` (pos-tax)
2. Customer approves the estimate - `approve-estimate` (pos-workorder)
3. Estimate is promoted into an active workorder - `promote-estimate-to-workorder` (pos-workorder)
4. Workorder is started and a technician assigned - `start-workorder`, `assign-technician` (pos-workorder)
5. Parts are consumed from inventory - `consume-workorder-parts` (pos-workorder) → `promote-inventory-allocation` (pos-inventory)
6. Workorder is completed - `complete-workorder` (pos-workorder)
7. Invoice is generated and paid - `generate-workorder-invoice` (pos-workorder) → `finalize-invoice`, `capture-payment` (pos-invoice)
8. GL posting - `post-journal-entry` (pos-accounting)

### CUJ-3: Purchase Order to Stock Replenishment
1. Buyer approves a purchase order - `approve-purchase-order` (pos-inventory)
2. Goods are received against the PO/ASN - `receive-goods` (pos-inventory)
3. Vendor bill is matched to the PO/receipt - `match-vendor-bill` (pos-accounting)
4. AP payment is created and applied - `create-ap-payment`, `apply-payment` (pos-accounting)

### CUJ-4: Warranty Claim Lifecycle
1. Advisor submits a warranty claim from workorder candidate lines - `submit-warranty-claim` (pos-warranty)
2. Claim eligibility is evaluated and a decision recorded - `decide-warranty-claim` (pos-warranty)
3. Defective part is returned to the vendor/provider - `submit-part-return` (pos-warranty), consumed by `resolve-inventory-shortage` (pos-inventory) if a replacement is short-picked
4. Settlement is submitted and reconciled - `submit-claim-settlement` (pos-warranty)
5. Recovery amount posts to the GL (async, via domain event) - `post-journal-entry` (pos-accounting)

### CUJ-5: Customer Onboarding & Vehicle Association
1. Front counter resolves or creates the party - `resolve-party`, `create-party` (pos-customer)
2. Pricing tier is resolved for the account - `resolve-account-tier` (pos-customer)
3. A vehicle is linked to the account - `link-party-vehicle` (pos-customer), backed by `register-vehicle-by-vin` (pos-vehicle-inventory)
4. Communication preferences are captured - `set-communication-preferences` (pos-customer)

### CUJ-6: Employee Time & Labor Tracking
1. Technician starts a work session - `start-work-session` (pos-people)
2. Labor is logged against an active workorder - `start-workorder-labor` (pos-workorder); corrections flow through `create-time-entry-adjustment` (pos-people)
3. Time entry is approved - `approve-time-entry` (pos-people)
4. Approved time flows to payroll export - `request-timekeeping-export` (pos-accounting)

### CUJ-7: Conversational Assistant Request (NLTI / GenAI)
1. User submits a natural-language tool-invocation request - `submit-nlti-request` (pos-mcp-server)
2. Assistant streams a tool-augmented chat response - `stream-mcp-chat` (pos-mcp-server)
3. Knowledge-base documents are ingested for retrieval-augmented answers - `ingest-mcp-document` (pos-mcp-server)

### CUJ-8: Authentication & Authorization at the Gateway
1. User logs in - `login-user` (pos-security-service)
2. Token pair is issued - `issue-token-pair` (pos-security-service)
3. Every downstream request is routed and authorized - `route-gateway-request` (pos-api-gateway), `check-authorization-decision` (pos-security-service)
4. Session is kept alive via refresh - `refresh-token` (pos-security-service)

## Frontend (durion-positivity-frontend)

Added: 2026-07-17 (Phase 1 extension). Scope expanded per `sre.config.yaml` (`context.frontend: true`,
`context.frontend_root: /Users/matthewlewis/Downloads/durion-positivity-frontend-master`). Source state:
`greenfield` (no existing OTel SDK, no manual spans - see the "Frontend" section of
[brownfield-assessment.md](brownfield-assessment.md)). Stack: Angular 21, standalone components,
Angular Signals, SSR via `@angular/ssr` + Express. All HTTP calls route through `ApiBaseService` to the
backend `pos-api-gateway` (`X-API-Version` header contract, same as any external client) - the frontend
introduces no new backend bounded contexts, only a UI surface over the 24 already cataloged above.

This section is additive: the 24 backend bounded contexts above are unchanged. It maps each frontend
feature module (`src/app/features/<name>/`) to the backend bounded context(s) it drives, and adds the
frontend's own critical user journeys.

### Feature-to-bounded-context mapping

| Frontend feature | Backend bounded context(s) | Notes |
|---|---|---|
| `accounting` | #5 Accounting & Financial Reporting | Posting rules, credit memos, vendor payments, payment-apply, invoice-payment-status, labor/overhead reports; also surfaces #20 Domain Event Backbone via the ingestion-monitor / event-envelope-contract pages |
| `admin` | *(cross-cutting)* | Thin `ROLE_ADMIN`-gated landing hub; no dedicated backend context |
| `auth` | #18 Security & Identity | Login page, mock-auth toggle (dev only), silent token refresh via `auth.interceptor.ts` |
| `billing` | #7 Invoicing & Payments | Invoice detail, payment capture, payment void/refund, receipts |
| `bulk-import` | #21 Bulk Data Ingestion | Shared wizard (file drop → column mapping → upload progress → results summary) reused by the `bulk-import` sub-routes under crm/inventory/location/people/product |
| `crm` | #6 Customer & CRM; #11 Vehicle Inventory (create-vehicle) | Party detail, create commercial/individual account, merge parties, billing rules, contacts, customer list, CRM snapshot |
| `inventory` | #4 Inventory & Warehouse Operations; #16 Location & Facilities (by-location views) | Ledger, putaway, purchase orders, replenishment, fulfillment, receiving, availability, cycle counts |
| `landing` | *(cross-cutting)* | Public pre-authentication marketing/landing page; no backend context |
| `location` | #16 Location & Facilities | Locations, bays, mobile units, storage locations, location sync/defaults |
| `order` | #1 Order Management | Order cart, price-override approval, order cancel |
| `people` | #14 People & Workforce Management; #15 People Identity & Contact | Timekeeping (work session, approval, export, discrepancy report), employee profile/offboard, directory, identity-compliance, role-assignment |
| `product` | #3 Catalog & Product Master; #2 Pricing & Promotions; #4 Inventory (availability/feeds tab); #16 Location (locations-roster tab) | Catalog browse, price books/MSRP/location price overrides, inventory availability/feeds, location roster |
| `security` | #18 Security & Identity | Roles, permissions, audit log, user provisioning |
| `shell` | #24 GenAI Assistant (NLTI chat panel, RAG ingest dialog); *(cross-cutting)* for nav/header/footer/dashboard chrome | App shell frame, dashboard home, conversational assistant panel |
| `shopmgmt` | #9 Shop Scheduling & Appointments | Appointment CRUD, dispatch board, mechanic roster/availability, schedule view |
| `system` | *(cross-cutting)* | Not-found/error routes; no backend context |
| `workexec` | #8 Work Order & Service Execution; #7 Invoicing & Payments (invoice-finalization step) | Estimates (create/revise/summary/approval variants), workorder assign/labor/parts/finalize/change-requests, WIP status board, travel time, timer widget |

*(cross-cutting)* rows and the `shell` nav/dashboard chrome have no single owning backend bounded context;
see the new `frontend-shell` bounded context added to `operations.yaml` for the one genuinely frontend-only
operation cataloged there (`view-operations-dashboard`).

### Frontend critical user journeys (CUJs)

These are the same business workflows as CUJ-1 through CUJ-8 above, but traced through the UI. Each step
cites the frontend route and the operation `id` it invokes (via `ApiBaseService` → gateway → backend
service) from [operations.yaml](operations.yaml). Steps that are UI-only aggregation/orchestration with no
single backend operation are cataloged as new `layer: frontend`-tagged (`telemetry.semanticConvention:
frontend`) operations in `operations.yaml`.

#### CUJ-F1: Cashier Creates and Finalizes a Sales Order (UI)
1. Cashier opens the order cart - `/app/order/cart` → `create-sales-order-cart`, `add-sales-order-item` (pos-order)
2. Price override is requested and approved from the cart - `/app/order/{orderId}/price-override/{lineId}` → `approve-price-override` / `reject-price-override` (pos-order)
3. Order is canceled if needed - `/app/order/{orderId}/cancel` → `cancel-sales-order` (pos-order)
4. Checkout hands off to Billing to finalize and pay - `/app/billing/invoices/{invoiceId}` → `finalize-invoice`, `/app/billing/invoices/{invoiceId}/payment-capture` → `capture-payment` (pos-invoice)

#### CUJ-F2: CRM Rep Updates a Customer Profile and Merges Duplicate Parties
1. Rep looks up or creates the party - `/app/crm/customers`, `/app/crm/create-commercial-account`, `/app/crm/create-individual-person` → `resolve-party`, `create-party` (pos-customer)
2. Rep reviews the unified account view - `/app/crm/crm-snapshot/{partyId}` → **`view-customer-snapshot`** (new frontend operation, aggregates `resolve-party` plus order/vehicle lookups)
3. Duplicate parties are merged - `/app/crm/merge-parties` → `merge-party` (pos-customer)
4. A vehicle is linked to the account - `/app/crm/party/{partyId}/add-vehicle` → `link-party-vehicle` (pos-customer), `register-vehicle-by-vin` (pos-vehicle-inventory)

#### CUJ-F3: Service Advisor Runs an Estimate Through Approval to Invoice
1. Advisor creates an estimate from an appointment or from scratch - `/app/workexec/estimates/new` → `create-estimate` (pos-workorder)
2. Customer approves via one of the approval-channel pages (digital/in-person/partial) - `/app/workexec/estimates/{estimateId}/approval/*` → `approve-estimate` (pos-workorder)
3. Advisor monitors the shop floor - `/app/workexec/wip-status` → **`view-work-in-progress-board`** (new frontend operation)
4. Workorder is finalized and invoiced - `/app/workexec/workorders/{workorderId}/finalize`, `/app/workexec/workorders/{workorderId}/invoice-finalization` → `complete-workorder`, `generate-workorder-invoice` (pos-workorder), `finalize-invoice` (pos-invoice)

#### CUJ-F4: Shop Manager Dispatches Appointments
1. Manager creates or edits an appointment - `/app/shopmgmt/appointments/new` → `create-appointment` (pos-shop-manager)
2. Manager works the live board - `/app/shopmgmt/dispatch-board` → **`view-dispatch-board`** (new frontend operation)
3. A conflicting appointment is resolved - `/app/shopmgmt/appointments/{id}/override-conflict` → shop-scheduling conflict-resolution operation (pos-shop-manager)

#### CUJ-F5: Warehouse Associate Performs a Bulk Stock Import
1. Associate drops a file and maps columns - `/app/bulk-import/jobs` (file-drop, column-mapping, upload-progress components) → `upload-bulk-load-file` (pos-bulk-loader)
2. Job is processed and reviewed to completion - `/app/bulk-import/jobs/{jobId}` → `process-bulk-load-job` (pos-bulk-loader), tracked end-to-end as **`complete-bulk-import-job`** (new frontend operation spanning the wizard)

#### CUJ-F6: HR/Payroll Reviews Timekeeping Discrepancies
1. Technician submits a work session - `/app/people/timekeeping/work-session` → `start-work-session` (pos-people)
2. Supervisor approves time entries - `/app/people/timekeeping/approval` → `approve-time-entry` (pos-people)
3. HR reviews cross-period discrepancies - `/app/people/timekeeping/discrepancy` → **`view-timekeeping-discrepancy-report`** (new frontend operation; no cataloged backend equivalent yet - see Gaps)

#### CUJ-F7: Security Admin Reviews Access and Audit History
1. Admin manages roles/permissions - `/app/security/roles`, `/app/security/permissions` → RBAC registry (pos-security-service)
2. Admin provisions a new user - `/app/security/users/provision` → user-provisioning (pos-security-service)
3. Admin reviews the audit trail - `/app/security/audit-logs` → **`view-security-audit-log`** (new frontend operation; no cataloged backend equivalent yet - see Gaps)

#### CUJ-F8: Conversational Assistant from the Dashboard
1. User opens the dashboard and the assistant strip - `/app` → **`view-operations-dashboard`** (new frontend operation, bounded context `frontend-shell`)
2. User submits a natural-language request via the chat panel - `shell/components/chat-panel` → `submit-nlti-request` (pos-mcp-server)
3. Response streams back - `stream-mcp-chat` (pos-mcp-server)
4. User ingests a document for RAG via the ingest dialog - `shell/components/rag-ingest-dialog` → `ingest-mcp-document` (pos-mcp-server)

### CUJ correlation matrix

Explicit backend-CUJ ↔ frontend-CUJ correlation, so a reader does not have to re-derive it from step text.
`Backend CUJ` and `Frontend CUJ` cross-reference the CUJ lists above by ID; `-` means no counterpart exists
on that side.

| Backend CUJ | Frontend CUJ | Journey name | Correlation |
|---|---|---|---|
| CUJ-1 | CUJ-F1 | Parts Counter Sale / Cashier Sales Order | partial - steps 1, 2, 5, 6 surfaced (cart creation, add-item incl. price quote + stock check, invoice finalize, payment capture); promotion apply (step 3) and inventory reservation (step 4) are not explicitly traced in CUJ-F1's steps; GL posting (step 7) is backend-only/async. CUJ-F1 additionally surfaces price-override and order-cancel flows not enumerated in CUJ-1 |
| CUJ-2 | CUJ-F3 | Vehicle Service Estimate to Invoice | partial - steps 1, 2, 6, 7 surfaced (create/approve estimate, complete workorder, generate + finalize invoice); promote-to-workorder / start-workorder+assign-technician / consume-parts (steps 3-5) are not individually traced in CUJ-F3; GL posting (step 8) is backend-only/async. CUJ-F3 adds a frontend-only WIP-board step (`view-work-in-progress-board`) with no backend-CUJ counterpart |
| CUJ-3 | - | Purchase Order to Stock Replenishment | no frontend surface traced - the `inventory` feature has PO/receiving/replenishment pages (see Frontend feature(s) column, context #4) but no frontend CUJ narrates this workflow end-to-end |
| CUJ-4 | - | Warranty Claim Lifecycle | no frontend surface - context #10 Warranty Claims has no owning frontend feature module (`-` in the Bounded contexts table) |
| CUJ-5 | CUJ-F2 | Customer Onboarding & Vehicle Association | partial - party resolve/create (step 1) and vehicle linking (step 3) are surfaced; account-tier resolution (step 2) and communication-preference capture (step 4) are not explicitly traced in CUJ-F2. CUJ-F2 additionally covers party-merge and a unified customer-snapshot view not part of CUJ-5 |
| CUJ-6 | CUJ-F6 | Employee Time & Labor Tracking | partial - work-session start (step 1) and time-entry approval (step 3) are surfaced; labor logging against a workorder (step 2, `start-workorder-labor`/`create-time-entry-adjustment`) and payroll export (step 4, `request-timekeeping-export`) are now cataloged in `operations.yaml` but not yet traced in the frontend CUJ-F6 steps. CUJ-F6 adds a frontend-only discrepancy-report step (`view-timekeeping-discrepancy-report`) with no cataloged backend operation (see Gaps) |
| CUJ-7 | CUJ-F8 | Conversational Assistant Request (NLTI) | 1:1 for the assistant workflow itself - steps 1-3 (submit request → stream response → ingest document) map directly to CUJ-F8 steps 2-4. CUJ-F8 adds a frontend-only dashboard-entry step (step 1, `view-operations-dashboard`) with no backend-CUJ counterpart |
| CUJ-8 | - | Authentication & Authorization at the Gateway | implicit/cross-cutting - no frontend CUJ narrates login / token-issuance / refresh explicitly; the `auth` feature (login page + `auth.interceptor.ts` silent refresh) implements it transparently beneath every other frontend CUJ rather than as its own user-facing journey |
| - | CUJ-F4 | Shop Manager Dispatches Appointments | frontend-only aggregation, no 1:1 backend CUJ - context #9 Shop Scheduling & Appointments has cataloged operations but no CUJ-1..8 entry narrates this journey |
| - | CUJ-F5 | Warehouse Associate Performs a Bulk Stock Import | frontend-only aggregation, no 1:1 backend CUJ - context #21 Bulk Data Ingestion has cataloged operations but no CUJ-1..8 entry narrates this journey |
| - | CUJ-F7 | Security Admin Reviews Access and Audit History | frontend-only aggregation, no 1:1 backend CUJ - context #18 Security & Identity RBAC/audit endpoints exist, but no CUJ-1..8 entry narrates an admin RBAC/audit-review workflow |

### New bounded context (frontend-only)

| # | Bounded context | Service(s) | Tier | Responsibility | Frontend feature(s) |
|---|---|---|---|---|---|
| 25 | **Frontend Application Shell** | `durion-positivity-frontend` (`shell` feature) | 2 | App chrome, navigation, dashboard home, assistant chat panel entry point - no backend equivalent | `shell` |

## External dependencies

| Service/system | Criticality | SLO owner | Timeout | Notes |
|---|---|---|---|---|
| PostgreSQL 16 / TimescaleDB | critical | platform-eng | per-pool (HikariCP defaults) | Per-service schema, no cross-service FKs |
| Kafka | critical | platform-eng | producer/consumer per topic config | ADR-0044 domain events (`{domain}.events.v1`, `.commands.v1`, `.manifest.v1`, `.dlq`) |
| Eureka (`pos-service-discovery`) | critical | platform-eng | client-side lease/heartbeat | Service registration/discovery for all 28 services + gateway |
| `pos-api-gateway` | critical | platform-eng | route-level (Spring Cloud Gateway defaults) | External security boundary; JWT validation, header rewrite |
| `pos-security-service` | critical | security-eng | per-call | JWT issuance, RBAC source of truth for all modules |
| Ollama (self-hosted) + `gpt-oss:120b` fallback | medium | genai-eng | model-call timeout (LangChain4j config) | `pos-mcp-server` only; cost model non-standard (self-hosted + cloud fallback) |
| Grafana OTel Java Agent v2.9.0 | n/a (observability) | platform-sre | n/a | Attached via `-javaagent` in all 28 service Dockerfiles |
| OTLP Collector (`otel-collector:4318` vs `localhost:4318`) | n/a (observability) | platform-sre | n/a | Endpoint discrepancy flagged in `docs/sre-todo.md` - not yet resolved |

## Gaps / data-quality notes

- **`service.team` is `TBD`** in `sre.config.yaml`. This domain map and `operations.yaml` use provisional, bounded-context-derived team slugs (`order-eng`, `price-eng`, `inventory-eng`, `accounting-eng`, `customer-eng`, `invoice-eng`, `workorder-eng`, `shop-eng`, `warranty-eng`, `vehicle-eng`, `people-eng`, `identity-eng`, `location-eng`, `tax-eng`, `security-eng`, `platform-eng`, `genai-eng`). These must be reconciled with real team ownership before Phase 4/6 (alert and runbook ownership metadata).
- **`pos-events`** appears in `docs/generated/audit/current/context.json`'s `detected_services` (28-count, has a Dockerfile) but has no `@RestController` or `@SpringBootApplication` in `src/main` - it is the shared `@EmitEvent` AOP library, not a business bounded context. No operations were cataloged for it. Flag for the SRE Planner to reconcile the 28-count / shared-library classification.
- **`pos-order`** was omitted from `context.json`'s `detected_services` array (already flagged in [brownfield-assessment.md](brownfield-assessment.md)); it is fully covered here as bounded context #1.
- **OpenAPI specs**: no per-service OpenAPI/`openapi.yaml` static files were found checked into the repo; entrypoints were derived directly from `@RestController`/`@RequestMapping` source annotations instead (`docs/adr-0042-openapi-rollout-baseline.md` suggests OpenAPI generation is in-progress/rolling out, not yet a committed artifact per service).
- **Coverage is representative, not exhaustive.** 28 services expose several hundred REST endpoints in total. Per the prioritization guidance, Tier 1 contexts (order, price, catalog, inventory, accounting, customer, invoice, workorder, tax, security, gateway) received deeper operation coverage (5-9 operations each); Tier 2/3 peripheral contexts received 1-4 representative operations each covering their primary business capability. Additional endpoints (e.g., `pos-accounting` financial-report drilldowns, `pos-people` timekeeping reports, most `GET`-by-id lookups across all services) were not cataloged individually and are candidates for a follow-up pass if SLOs are needed at finer granularity.
- **Payroll/timekeeping export** (`pos-accounting TimekeepingExportController`) and **labor time-entry on workorders** (`pos-workorder WorkorderLaborController`, `pos-people TimeEntryAdjustmentController`) are now cataloged as standalone operations - `request-timekeeping-export` (accounting), `start-workorder-labor` (work-order-execution), and `create-time-entry-adjustment` (workforce) - and CUJ-6 cites them directly. Each controller exposes additional endpoints (export status/history, labor stop/adjust/history, adjustment list/approve) not individually cataloged; candidates for a future finer-granularity pass.
