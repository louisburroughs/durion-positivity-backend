The following markdown table is a version of your updated **Strategic Inventory and Accounting Clarification Requests**, incorporating the latest responses provided in the sources.

| Issue Number | Origin Story Number | Domain(s) | Question Number | Question | Responses |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **324** | #178 | inventory | 1 | What are the specific, nuanced requirements, business rules, or edge cases from original story #236? | |
| | | | 2 | What is the authoritative source for an item's cost (COGS) at the time of consumption? | |
| | | | 3 | Should the entire consumption transaction be atomic? | |
| **321** | #127 | accounting | 1 | What specific user roles will be granted MANAGING and REOPEN permissions? | |
| | | | 2 | What entity represents a Business Unit (Location, legal entity, etc.)? | |
| | | | 3 | Is the proposed error response sufficient for all upstream clients? | |
| | | | 4 | Can accounting periods be created for future dates or back-dated? | |
| | | | 5 | Confirm that domain:accounting is the correct primary domain. | **domain:accounting is correct** |
| **319** | #173 | workexec | 1 | What is the specific mechanism for granting PRICE_OVERRIDE permission? | |
| | | | 2 | What is the definitive list of valid overrideReasonCode values? | |
| | | | 3 | Is the ALLOW_NON_CATALOG_PARTS policy system-wide or per location? | |
| | | | 4 | What is the source of truth for the taxCode field? | |
| **318** | #171 | pricing | 1 | What is the business policy for handling a taxable item missing a tax code? | |
| | | | 2 | What is the authoritative rounding policy for final totals? | |
| | | | 3 | Does the system need to support tax-inclusive pricing models now? | |
| **317** | #168 | workexec | 1 | What specific fields and conditions define a "complete" estimate for submission? | |
| **316** | #161 | workexec | 1 | What is the source for technician availability and the precise business rule for blocking? | |
| | | | 2 | What are the required notification channels, who configures them, and what is the content? | |
| **315** | #140 | accounting | 1 | What are the precise conditions (balances, usage, templates) for account deactivation? | |
| **314** | #137 | accounting | 1 | What is the explicit policy for handling mapping failures (Suspense vs. Rejection)? | |
| **313** | #134 | accounting | 1 | Which specific dimensions must be supported for filtering (Location, Project, etc.)? | |
| | | | 2 | What is the precise model for access control granularity? | |
| | | | 3 | What is the exact column layout and naming for the CSV export? | |
| | | | 4 | How is a "period" officially defined (Calendar vs. Fiscal)? | |
| | | | 5 | What is the contract for the "Source Event" payload for drilldowns? | |
| **312** | #120 | accounting | 1 | Under what conditions is negative inventory permissible and who defines this? | |
| **311** | #114 | accounting | 1 | Confirm if Accounting is the primary domain for PaymentApplication state. | |
| | | | 2 | What is the formal event or API contract between Payment and Accounting? | |
| | | | 3 | How are overpayments handled (CustomerCredit entity vs. left on payment)? | |
| | | | 4 | Define the business process, authorization, and audit trail for reversals. | |
| **310** | #109 | crm | 1 | How are conflicting primitive attributes handled during a merge? | |
| | | | 2 | What is the precise final state of a source record after a merge? | |
| | | | 3 | Is there a requirement for administrative "un-merge" capabilities? | |
| **309** | #106 | crm | 1 | Confirm separation of "kind" and "label" for contacts and if labels are configurable. | |
| | | | 2 | Confirm the "single primary per kind" demotion rule. | |
| **308** | #97 | pricing | 1 | How is customer eligibility for promotions defined? | |
| | | | 2 | Can a single promotion contain multiple discount types? | |
| | | | 3 | Is storeCode mandatory, and does null imply all locations? | |
| | | | 4 | Is usageLimit tracked globally or per-customer? | |
| **307** | #172 | workexec | 1 | What is the hierarchy and fallback logic for determine default labor rates? | |
| | | | 2 | Are units and rates editable for flat-rate labor items? | |
| **306** | #170 | workexec | 1 | What is the policy for revisions on already approved/converted estimates? | |
| **305** | #169 | workexec | 1 | Should summary generation fail or use defaults if legal terms are missing? | |
| **304** | #160 | workexec | 1 | Is "auto-start on first labor entry" in scope for this story? | |
| | | | 2 | What is the exhaustive list of statuses from which a work order can be started? | |
| | | | 3 | What is the complete list of valid in-progress sub-statuses? | |
| | | | 4 | How does the system technically identify a "pending approval change request"? | |
| **303** | #158 | workexec | 1 | Should "Issued" and "Consumed" be distinct events or atomic? | |
| | | | 2 | What is the precise deterministic key format for part usage? | |
| | | | 3 | Where is the rule for WIP vs. COGS accounting impact configured? | |
| **302** | #155 | workexec | 1 | What is the authoritative source for VisibilityPolicy data? | |
| | | | 2 | Is the policy configurable per-field or per-role flag? | |
| | | | 3 | Should implementation use a single endpoint or role-specific endpoints? | |
| **301** | #152 | workexec | 1 | What is the policy for handling Completed but Unauthorized items? | |
| | | | 2 | What is the required workflow when a price or tax variance is detected? | |
| | | | 3 | What roles/permissions are required for corrections on finalized orders? | |
| **300** | #150 | workexec | 1 | What is the exact target state for a reopened work order? | |
| | | | 2 | Which specific fields become editable after reopening? | |
| **299** | #148 | accounting | 1 | What is the authoritative rounding policy for financial calculations? | |
| | | | 2 | Is the tax rule source internal or a third-party provider? | |
| | | | 3 | What is the complete, canonical list of varianceReasonCodes to detect? | |
| | | | 4 | What specific fields are mandatory for successful tax calculation? | |
| **298** | #143 | accounting | 1 | What is the specific contract/schema for event payloads? | |
| | | | 2 | Which specific message broker technology is targeted? | |
| | | | 3 | What is the idempotency strategy for duplicate event submissions? | |
| | | | 4 | What security mechanism will secure the synchronous endpoint? | |
| **297** | #142 | accounting | 1 | What is the required mechanism for "flagging" duplicate events? | |
| | | | 2 | Should the entire payload or a subset be hashed for conflict detection? | |
| | | | 3 | What is the data retention policy for the idempotency store? | |
| **296** | #141 | accounting | 1 | What is the policy for handling unknown event types? | |
| | | | 2 | What are the exhaustive financial consistency checks and tolerances? | |
| | | | 3 | What is the canonical list of actionable error codes for failures? | |
| **295** | #139 | accounting | 1 | What is the policy for handling date-range overlaps in mappings? | |
| | | | 2 | What is the system behavior for an unmappable transaction? | |
| | | | 3 | What is the definitive list of financial dimensions to capture? | |
| **294** | #136 | accounting | 1 | Confirm the policy for closed period postings (Strict Block vs. Override). | |
| **293** | #131 | accounting | 1 | Is it acceptable to scope this story only to accounting functions? | **Give instructions to initiate new stories in other domains** to complete the story |
| | | | 2 | What is the business trigger to initiate a cash refund? | |
| | | | 3 | Provide specific period-close policies for adjustments. | |
| | | | 4 | Does a Credit Memo require an approval workflow? | |
| **292** | #130 | accounting | 1 | Which is the primary trigger for bill creation (Goods vs. Invoice)? | |
| | | | 2 | What is the exact initial state of a newly created bill? | |
| | | | 3 | What is the process if event quantities/prices mismatch the PO? | |
| **291** | #125 | accounting | 1 | What is the COA structure for mapping lines to statements? | |
| | | | 2 | Which accounting standard (GAAP/IFRS) should be followed? | |
| | | | 3 | What are the specific roles and permissions for report viewing? | |
| | | | 4 | Which specific export formats (PDF, CSV, etc.) are required? | |
| | | | 5 | How should multi-entity/currency scenarios be handled? | |
| **290** | #124 | accounting | 1 | What are the data types and sources for traceability IDs? | |
| | | | 2 | Does the "explainability view" require a public API or just data structure? | |
| | | | 3 | What system is the authoritative source for business event details? | |
| **289** | #123 | accounting | 1 | Which file formats (CSV, OFX, etc.) must be supported for import? | **Implement a pattern for supporting multiple popular formats** |
| | | | 2 | Is there a requirement for a matching tolerance (e.g., $0.01)? | **Configurable** |
| | | | 3 | Can default GL accounts be pre-configured for adjustment types? | |
| | | | 4 | What is the contract for retrieving unreconciled transactions? | |
| | | | 5 | What specific fields must be included in the Reconciliation Report? | |
| **288** | #121 | accounting | 1 | Where is the inventory valuation method configured and maintained? | |
| | | | 2 | What determined whether to post to COGS vs. WIP? | |
| | | | 3 | How are specific GL account identifiers for assets determined? | |
| **287** | #119 | accounting | 1 | What specific fields are guaranteed in the finalCostSummary? | |
| | | | 2 | What is the logic to determine the destination GL account for WIP? | |
| **286** | #118 | accounting | 1 | What are the specific GL IDs for standard WIP and COGS accounts? | |
| | | | 2 | Is event trust sufficient, or is an external authorization check required? | |
| | | | 3 | What is the behavior if the workorderId is not found? | |
| **285** | #116 | accounting | 1 | What specific GL IDs should be used for AR, Revenue, and Tax? | |
| | | | 2 | What is the authoritative source for adjustment authorization? | |
| | | | 3 | Are pattern rules identical for Adjusted vs. CreditMemo events? | |
| | | | 4 | What is the behavior for adjustments that increase the total amount? | |
| **284** | #115 | accounting | 1 | What is the correct policy for currency mismatches? | |
| | | | 2 | Is a null customerId acceptable for payments? | |
| **283** | #113 | accounting | 1 | What are the reasonCodes and required accounting treatments? | |
| | | | 2 | What is the behavior for refunds on already credited invoices? | |
| **282** | #112 | crm | 1 | Which domain is the owner for Commercial Account (Party) entities? | **Let's try CRM for now** |
| | | | 2 | What specific fields constitute "default billing terms"? | |
| | | | 3 | Confirm the logic for duplicate name/contact detection. | |
| | | | 4 | How should ExternalIdentifiers be managed and constrained? | |
| **281** | #108 | crm | 1 | Which domain is authoritative for managing contact roles? | **CRM is the domain for managing contacts** |
| | | | 2 | What is the definitive list of contact roles required at launch? | |
| | | | 3 | Confirm the automatic demotion logic for primary contacts. | |
| | | | 4 | Is the billing contact rule global or per-account? | |
| **280** | #105 | crm | 1 | Is VIN uniqueness global or only within a single customer account? | |
| **279** | #103 | crm | 1 | What is the required ranking algorithm for search results? | |
| | | | 2 | Define "partial" search (contains vs. starts with) and min length. | |
| | | | 3 | What fields must be in the "full vehicle + owner snapshot"? | |
| | | | 4 | What is the maximum number of results and is pagination needed? | |
| **278** | #102 | crm | 1 | Is the preference list fixed or must it use a flexible JSONB model? | |
| **277** | #101 | crm | 1 | What is the policy for mileage decreases and VIN corrections? | |
| **276** | #96 | pricing | 1 | When multiple rules exist, is the combination logic AND or OR? | |
| | | | 2 | What are the contracts for fleet size and vehicle tag sources? | |
| | | | 3 | Confirm the initial set of reasonCode enums required. | |
| **275** | #94 | crm | 1 | Does this service enforce usage limits or only record redemptions? | |
| **274** | #93 | workexec | 1 | What is the specific contract for the alias resolution endpoint? | |
| | | | 2 | What are the exact data types and formats for CRM IDs? | |
| | | | 3 | Is it permissible for crmContactIds to be an empty list? | |
| **273** | #87 | location | 1 | Should this story be narrowed only to domain:location CRUD? | **yes** |
| | | | 2 | Where should the "Inactive location" staffing rule be enforced? | **domain:people** |
| | | | 3 | What are the business rules for parent location status cascades? | **A location cannot be made inactive with staff members assigned** |
| | | | 4 | Does a new location always default to ACTIVE status? | **No**, a location is created in an inactive status and has to be made active |
| **272** | #85 | workexec | 1 | What is the definitive location validation policy for clock-ins? | |
| **271** | #71 | workexec | 1 | What is the definitive API contract for the HR availability service? | |
| | | | 2 | What is the source of truth for "mobile travel blocks"? | |
| | | | 3 | How should the system behave if the HR system times out? | |
| | | | 4 | What is the required time granularity for checks (e.g., 15 mins)? | |
| **270** | #90 | people | 1 | What is the authoritative state for a disabled user? | |
| | | | 2 | Is ending location assignments optional or mandatory? | |
| | | | 3 | What is the retry policy for downstream command failures? | |
| **269** | #89 | people | 1 | How are overlapping/conflicting role assignments handled? | |
| | | | 2 | Are any roles inherently global or location-scoped? | |
| | | | 3 | Confirm revocation is handled via effectiveEndDate. | |
| **268** | #88 | people | 1 | What are the specific enumerated values for user status? | |
| | | | 2 | What specific fields are required in contactInfo? | |
| | | | 3 | Should the API hard-block duplicates with Conflict? | |
| **267** | #86 | people | 1 | What is the behavior for existing primary location assignments? | |
| | | | 2 | Are effective dates full dates or timestamps (and what TZ)? | |
| | | | 3 | What is the precise, non-negotiable event schema? | |
| | | | 4 | Can a person have multiple overlapping non-primary assignments? | |
| **266** | #84 | people | 1 | Are there different break types (Meal, Rest) to record? | |
| | | | 2 | What happens if a mechanic clocks out with an active break? | |
| **265** | #83 | people | 1 | Define the workflow and permissions for "controlled adjustments." | |
| | | | 2 | What defines a "period" for time approval? | |
| | | | 3 | What is the workflow and state transition after a rejection? | |
| **264** | #82 | workexec | 1 | What are the rules for allowing multiple concurrent timers? | |
| | | | 2 | What is the behavior for "abandoned" timers at shift end? | |
| | | | 3 | Is there a requirement to pause and resume a timer? | |
| **263** | #81 | workexec | 1 | Which WorkOrder states prevent LaborPerformed entries? | |
| | | | 2 | What is the retry policy for transient network/API failures? | |
| **262** | #80 | people | 1 | Where is the "configurable threshold" for discrepancies managed? | |
| **261** | #78 | location | 1 | What are the definitions and scopes for check-in/cleanup buffers? | |
| **260** | #77 | location | 1 | Are service/skill constraints free-text or foreign keys? | |
| | | | 2 | How should "capacity" be structured (Integer, Weight, etc.)? | |
| | | | 3 | What is the initial, definitive list of bay_type values? | |
| **259** | #76 | location | 1 | What travel buffer policies (fixed vs dynamic) must be supported? | |
| | | | 2 | What is the source and model for "capability" tags? | |
| | | | 3 | How are "service areas" defined (Zip, Polygon, etc.)? | |
| | | | 4 | What is the integration contract for travel time to HR? | |
| **258** | #74 | workexec | 1 | What is the specific performance SLA for the schedule endpoint? | |
| | | | 2 | What are the precise rules for defining a "conflict"? | |
| | | | 3 | Is the "HR availability overlay" a hard or soft dependency? | |
| | | | 4 | What is the default time range for a "daily" view? | |
| **257** | #72 | people | 1 | What is the integration pattern (Scheduled Pull vs Event Push)? | |
| **256** | #59 | workexec | 1 | What is the API for manager context overrides? | |
| | | | 2 | What is the exact REST/event schema for OperationalContext? | |
| | | | 3 | What specific event triggers the workorder context lock? | |
| | | | 4 | What is the required egress mechanism for status reporting? | |
| **255** | #54 | pricing | 1 | Do rules apply to SKUs, categories, or both (and precedence)? | |
| | | | 2 | What are the endpoints for Customer Tier and Cost/MSRP data? | |
| | | | 3 | How should missing base cost/MSRP values be handled? | |
| | | | 4 | Is there a single company-wide "Base" Price Book? | |
| **254** | #52 | pricing | 1 | Who is the designated approver for threshold-exceeding overrides? | |
| | | | 2 | What is the notification and escalation path for approvals? | |
| | | | 3 | What is the lifecycle and notification process for rejections? | |
| **253** | #70 | workexec | 1 | What are the roles, checks, and justifications for overrides? | |
| | | | 2 | How is collective skill possession defined for a job? | |
| | | | 3 | What is the required payload schema for assignment events? | |
| **252** | #67 | workexec | 1 | What is the definitive list of supported travel segment types? | |
| | | | 2 | What are the specific auto-apply buffer policies? | |
| | | | 3 | What is the schema and transport for HR/Payroll integration? | |
| | | | 4 | What are the conditions for editing segments on behalf of others? | |
| **251** | #66 | workexec | 1 | What is the functional and data model for time adjustments? | |
| | | | 2 | What are the specific criteria that define an "Exception"? | |
| | | | 3 | What are the specific fields and transport for HR integration? | |
| **250** | #63 | workexec | 1 | What is the mapping table for Workexec to Appointment statuses? | |
| | | | 2 | Is the reopenFlag permanent once set to true? | |
| | | | 3 | How is the Appointment identified from the incoming event? | |
| **249** | #61 | audit | 1 | Which domain is the primary owner for audit extensions? | **domain:audit** |
| | | | 2 | Is auditing a synchronous, critical-path dependency? | **asynchronous**, non-critical path but important |
| | | | 3 | What is the data retention policy for audit records? | **Default 90 days**, make categories configurable |
| | | | 4 | Is the "reason code" free-text or from a managed list? | **Managed list** |
| | | | 5 | Is there a need to filter by date range or actor? | **Yes**, there needs to be a filter framework for searching |
| | | | 6 | What is the structure for the ChangeSummary (Text vs JSON)? | **json** |
| **248** | #51 | pricing | 1 | What is the authoritative rounding policy for financial calculations? | |
| | | | 2 | What is the specific P95 latency target for the pricing endpoint? | |
| | | | 3 | Is a coupled Inventory availability check a hard requirement? | |
| | | | 4 | What is the fallback behavior if no pricing rules are found? | |
| **247** | #60 | workexec | 1 | What is the performance SLA for dashboard initial load? | |
| | | | 2 | What is the required refresh rate (Real-time vs Polling)? | |
| | | | 3 | What is the contract for availability signals from the HR domain? | |
| | | | 4 | What specific rules define a "Conflict" exception? | |
| **245** | #55 | inventory | 1 | What is the authoritative list of lifecycle states? | |
| | | | 2 | Which roles have permission to override discontinued blocks? | |
| | | | 3 | Can a product be replaced by more than one alternative? | |
| | | | 4 | What is the granularity for effectiveStartDate (e.g., 00:00 UTC)? | |
| **243** | #50 | pricing | 1 | Which domain (Pricing or Workexec) is primary for implementation? | **workexec calls the service**, but pricing is responsible for implementation |
| | | | 2 | Should the story be split into capability vs. integration? | **yes**, this will help clarify duties |
| | | | 3 | Is a hard-fail the desired behavior if a snapshot cannot be created? | **Yes**, pricing should hard fail with clear errors for items it cannot price |
| | | | 4 | What are the specific requirements for snapshot drilldown? | **The PricingSnapshot should contain enough information** for tracing how it was derived |
| **242** | #49 | workexec | 1 | What is the system behavior if the Pricing service is unavailable? | |
| | | | 2 | What is the preferred data model for substitution traceability? | |
| | | | 3 | Is the substitute price locked once applied to the line? | |
| **241** | #48 | inventory | 1 | What is the authoritative formula for calculating ATP? | |
| **240** | #47 | inventory | 1 | What are the rules for normalizing lead time and region codes? | |
| | | | 2 | What is the operational plan and SLA for the exception queue? | |
| | | | 3 | Define the exact contract for the v1 stub connector. | |
| **239** | #46 | inventory | 1 | What system is the authoritative source for ManufacturerPartMap? | |
| | | | 2 | What is the expected data format (JSON/CSV) for feeds? | |
| | | | 3 | Define the scope of "min-order rules" (optional field vs story). | |
| **238** | #43 | product | 1 | Which domain is the System of Record for RestrictionRule entities? | **Set up in the product system**; called when ordering stock or placing on workorder |
| | | | 2 | What is the technical contract for synchronous enforcement? | |
| | | | 3 | Should the system "fail open" or "fail closed" if rules are down? | **Allow** product addition/receipt if the system is unavailable |
| | | | 4 | What specific location and service tags must be supported? | **Start with samples**, add as we go |
| | | | 5 | What is the expected backend-influenced user flow for overrides? | |
| **237** | #42 | security | 1 | Which domain is primary for foundational security? | **security** |
| | | | 2 | Is the domain:resource:action permission format approved? | **Need to note location dependence** |
| | | | 3 | What is the mechanism for a domain to declare permissions? | **Registered in the security module** |
| | | | 4 | Is the creation of business-oriented roles in scope? | **no**, just the structure |
| | | | 5 | How does location factor into the permission model? | **Some role based permissions are for specific locations** |
| **235** | #38 | inventory | 1 | Should the story be split into configuration vs execution? | **Yes**, split the story |
| | | | 2 | Can the same location be both Staging and Quarantine? | **No**, must be distinct to avoid confusion |
| | | | 3 | Is the move-from-quarantine permission enforcement in scope? | **No**, that's a different story |
| **234** | #37 | inventory | 1 | What specific permissions or roles are required for ADJUST movements? | |
| **233** | #36 | inventory | 1 | Confirm the official ATP formula (include expected receipts?). | |
| | | | 2 | Define the scope of UOM handling (Base UOM vs multi-UOM). | |
| | | | 3 | Define a specific performance target for the API P95 latency. | |
| | | | 4 | Which specific ledger event types sum to 'On-Hand'? | |
| **232** | #35 | inventory | 1 | What is the primary method for providing PO/ASN identifiers? | |
| | | | 2 | Is "blind" receiving (no source document) allowed or blocked? | |
| | | | 3 | Is counting items and variances out of scope for this story? | |
| **231** | #33 | inventory | 1 | Should receipt confirmation be fully automatic or manual? | |
| | | | 2 | What is the schema and transport for Workexec notifications? | |
| | | | 3 | How are mismatched part numbers handled during receipt? | |
| **230** | #32 | inventory | 1 | What is the precedence order for overlapping put-away rules? | |
| | | | 2 | Should single lines or consolidated lines drive task creation? | |
| | | | 3 | What is the mechanism for task assignment (Manual vs Pool)? | |
| | | | 4 | What happens if a rule's suggested destination is full? | |
| **229** | #31 | inventory | 1 | How are full/invalid destination storage scans handled? | |
| | | | 2 | What happens if a "from" scan shows no quantity in data? | |
| **228** | #30 | inventory | 1 | What is the trigger for replenishment (Real-time vs Batch)? | |
| | | | 2 | What is the sourcing logic for multiple backstock locations? | |
| **227** | #29 | inventory | 1 | What specific business logic defines "Soft" vs "Hard" allocation? | |
| **226** | #28 | inventory | 1 | What logic determines "priority" and "due time" for pick tasks? | |
| | | | 2 | What defines the "route/location" sorting algorithm? | |
| | | | 3 | How is a "suggested" location picked if stock is in multiple spots? | |
| **225** | #27 | inventory | 1 | Clarify recount logic, triggers, and auditability processes. | |
| **224** | #26 | inventory | 1 | What are the criteria for approval thresholds (Cost, %, Units)? | |
| | | | 2 | Are below-threshold adjustments automatically approved? | |
| | | | 3 | Is a single level of approval sufficient? | |
| | | | 4 | How are users with approval permissions notified? | |
| **223** | #25 | inventory | 1 | What is the decision hierarchy for presenting shortage options? | |
| | | | 2 | What is the schema for fetching substitutes from Product? | |
| | | | 3 | What is the schema for fetching availability from Positivity? | |
| | | | 4 | What is the specific timeout threshold for dependent calls? | |
| | | | 5 | Where is the estimatedLeadTimeDays sourced from? | |
| **222** | #24 | inventory | 1 | What rules prevent "starvation" of lower priority orders? | |
| | | | 2 | Is the Priority DESC, Due Time ASC sorting logic complete? | |
| | | | 3 | What is the enumerated list of audit reason codes? | |
| **221** | #21 | workexec | 1 | What is the policy if Inventory reports insufficient stock? | |
| | | | 2 | What is the precise behavior for linking estimates/workorders? | |
| | | | 3 | Is an anonymous cart (no customerId) a valid use case? | |
| | | | 4 | Is Pricing a hard dependency and what is the fallback? | |
| **220** | #18 | workexec | 1 | Which domain (Order, Payment, Workexec) is primary for policy? | **Workorder execution** is the trigger and primary engine |
| | | | 2 | Which work statuses specifically block cancellation? | **Completed workorders** cannot be cancelled |
| | | | 3 | What is the policy if a payment is already Captured and Settled? | Completed workorders cannot cancel; **In progress requires reconciliation** |
| | | | 4 | Is moving to CancellationFailed correct for downstream errors? | **Downstream errors should not impact** workorder status |
| **219** | #17 | inventory | 1 | What is the specific, measurable target latency for search? | |
| | | | 2 | What specific product fields should "keyword" match against? | |
| | | | 3 | What is the default and maximum allowable page size? | |
| **218** | #16 | positivity | 1 | What is the partial failure policy for downstream services? | |
| | | | 2 | What is the source and format for external lead-time hints? | |
| | | | 3 | What is the response caching strategy and TTL? | |
| **217** | #3 | crm | 1 | Who is the authoritative source for customer billing rules? | |
| | | | 2 | Is there a specific format or pattern required for PO numbers? | |
| | | | 3 | What is the override permission scope and approval workflow? | |
| | | | 4 | How do payment terms interact with PO requirements? | |
| | | | 5 | What is the default behavior for missing/misconfigured rules? | |
| **216** | #2 | security | 1 | What is the complete list of protected operations and permissions? | |
| | | | 2 | Are roles flat or hierarchical with inheritance rules? | |
| | | | 3 | Is the HR system the authoritative source for user identity? | |
| | | | 4 | Are permissions scoped globally, per-location, or per-shop? | |
| | | | 5 | Can roles be assigned with an expiration date? | |
| **215** | #1 | accounting | 1 | Who decides the timing and responsibility of GL postings? | |
| | | | 2 | What are the override authority and threshold rules? | |
| | | | 3 | What refund scenarios (reversal, memo, adj) are in scope? | |
| | | | 4 | Define the cancellation scope and associated GL impacts. | |
