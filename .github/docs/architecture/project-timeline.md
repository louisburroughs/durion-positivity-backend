Below is a **Moqui-optimized 6-month delivery timeline** engineered for the domains you defined and the agentic-AI strategy we outlined.

This version assumes:

* **Moqui provides your base platform**—entity engine, service engine, security, multi-tenancy, transactions.
* You still build a **2-layer service architecture**:

  * Moqui **base services** (domain logic)
  * Custom **experience services** (UI + mobile + MCP orchestration)
* You are building:

  * Traditional web UI
  * Mobile mechanic UI
  * MCP chat server
* AI agents automate domain modeling, scaffolding, integration testing, and conversational mapping.
* Teams are organized into **vertical slices**:

  * Work Execution & Billing
  * Inventory
  * Product/Pricing
  * CRM
  * Accounting (light)

This plan is aggressive but feasible **only** if governance and AI-assisted modeling are followed.

---

# 🌙 **Phase 0: Pre-Launch (Weeks 0–2)**

*Goal: Establish Moqui skeleton, domain governance, and AI agents before coding begins.*

### Human Work

* Install & configure Moqui + runtime structure.
* Define **naming conventions, entity/service patterns, experience-layer boundary rules**.
* Set up Git repo structure:

  * `moqui-framework`
  * `durion-components` (custom)
  * `experience-services` (Java/Kotlin or Groovy)
  * `ui-web`
  * `ui-mobile`
  * `mcp-server`
* Establish DevOps pipelines for:

  * Entity validation
  * Service auto-generation tests
  * Experience API testing
  * UI test harness
* Finalize the **4–5 “golden flows”**.

### Agentic AI Work

* Convert golden flows into **domain requirement specifications**.
* Generate initial **Moqui entity definitions** for:

  * Customer
  * Vehicle
  * Product
  * ServiceList
  * Workorder
  * Estimate
  * Invoice
* Generate a **glossary** + **domain dictionary** used by all squads.
* Produce scaffolding for:

  * Moqui services
  * Experience endpoints
  * DTOs
  * Test skeletons

### Milestone

✔ **Moqui project structure ready for teams**
✔ **First domain entities defined and validated**
✔ **AI agents trained on your patterns**

---

# 🌱 **Phase 1: Domain Modeling + Moqui Base Layer (Weeks 3–6)**

*Goal: Establish stable entities and base services for 80% of the prototype logic.*

### Human Work (Domain Teams)

#### **Work Execution**

* Implement Moqui entity sets:

  * `Estimate`, `Workorder`, `WorkorderItem`, `Invoice`, `WarrantyCase`
* Implement domain-driven service logic (state transitions).
* Begin simple scheduling model (time slots, mechanics, bays).

#### **Inventory**

* Entities: `InventoryItem`, `InventoryReceipt`, `InventoryLocation`.
* Services: check availability, consume inventory for workorder.

#### **CRM**

* Entities: `Customer`, `Vehicle`, linking tables.
* Basic CRUD services.

#### **Product/Pricing**

* Entities: `Product`, `ServiceOperation`, `PriceListItem`.
* Simple pricing rule services.

#### **Accounting (Prototype)**

* Entities: `ARTransaction`, `Payment`.
* Prototype invoice posting.

---

### Agentic AI Work

* Validate entity models for completeness + conflicts.
* Auto-generate CRUD services for all domains.
* Generate basic integration tests for each service.
* Produce early experience-layer scaffolding for estimate → workorder → invoice.
* Detect missing fields or domain inconsistencies (e.g., invoice descending from estimate).

---

### Milestone

✔ All major entities and base services exist in Moqui
✔ First vertical flow: *Customer + Vehicle → Estimate → Workorder (draft)*
✔ Integration tests running in CI/CD

---

# 🚧 **Phase 2: Experience Layer + UI Skeletons (Weeks 7–10)**

*Goal: Create task-oriented APIs + UI shells that consume them.*

### Human Work

#### Experience Layer

* Build orchestrated APIs:

  * `createEstimateForCustomerVehicle`
  * `addPartToWorkorder`
  * `completeWorkorder`
  * `generateInvoice`
  * `checkInventoryAvailability`
  * `lookupCustomerVehicle`
* Map Moqui service calls into flattened DTOs for UI + MCP.

#### UI – Web (Service Writer UI)

* Create screens:

  * Dashboard
  * Estimate editor
  * Workorder editor
  * Invoice preview
* Begin styling with TIOTF/Durion design kit.

#### UI – Mobile (Mechanic)

* Create:

  * Assigned jobs list
  * Job detail
  * Add findings (text, images)

---

### Agentic AI Work

* Generate UI scaffolding components based on DTOs.
* Generate TypeScript models from OpenAPI.
* Create integration tests ensuring experience APIs remain stable.
* Flag DTO drift between experience layer and Moqui entities.

---

### Milestone

✔ UI shells running end-to-end through experience services
✔ Mobile app reads and updates basic workorder data
✔ First meaningful demo possible

---

# 🤖 **Phase 3: Conversational Layer (MCP) + Flow Refinement (Weeks 11–14)**

*Goal: Enable AI-assisted shop workflows and stabilize backend orchestration.*

### Human Work

* Define MCP tools for:

  * Create estimate
  * Add/remove workorder items
  * Summarize workorder status
  * Lookup customer/vehicle
  * Check inventory
  * Produce invoice summary
* Build mcp-server with secure access to experience layer.
* Improve scheduling logic; add mechanic assignment.

### Agentic AI Work

* Generate MCP tool schemas.
* Automatically map flows to experience endpoints.
* Produce conversational scripts for testing.
* Suggest missing endpoints based on conversational needs.
* Run regression tests for chat flows.

---

### Milestone

✔ Chatbot can create an estimate and modify a workorder
✔ Conversational flows validated with MCP tests
✔ Scheduling and mechanic assignment usable

---

# 🧩 **Phase 4: Vertical Slice Completion (Weeks 15–18)**

*Goal: Complete all prototype-level functionality across domains.*

### Human Work

#### Work Execution

* Finalize warranties
* Add roadside service flow
* Add delivery receipt workflow

#### Inventory

* Receiving + simple putaway
* Part substitution logic (simple rules)

#### CRM

* Customer campaigns (stub with 1–2 examples)
* Fleet customer flags

#### Product/Pricing

* Price overrides + customer-specific pricing

#### Accounting

* Invoice posting + payment capture (prototype)

---

### Agentic AI Work

* Generate end-to-end tests for:

  * Roadside service
  * Warranty repairs
  * Full estimate → workorder → invoice → payment
* Validate data integrity across entities.
* Detect cross-domain drift (common in this phase).

---

### Milestone

✔ All core prototype features implemented
✔ Reliability increasing through automated tests
✔ UI + mobile + MCP all operating with real flows

---

# 🚀 **Phase 5: Hardening, Performance, and Demo Readiness (Weeks 19–24)**

*Goal: Produce a stable, demonstrable prototype suitable for pilots.*

### Human Work

* Refine UX (reduce friction in common tasks).
* Add filtering, search, sorting, dashboards.
* Improve mobile ergonomics.
* Add audit logging and security refinement.
* Prepare demo datasets (fleet, part catalog, services).

### Agentic AI Work

* Load test generation (Moqui’s service engine responds well to AI load patterns).
* Detect slow entity queries; suggest indexes.
* Generate regression tests for every experience API.
* Run conversational stress tests.
* Produce prototype documentation & training material.

---

### Final Milestone

🎯 **Fully functioning prototype with:**

* Stable Moqui base layer
* Experience APIs servicing UI + mobile + MCP
* Complete golden workflows
* Automated integration tests
* Production-quality conversational flows
* Well-structured entities and domain boundaries
* Demo-ready UX

---

# 💎 SUMMARY TIMELINE (condensed)

| Month | Objective                       | Output                         |
| ----- | ------------------------------- | ------------------------------ |
| 0     | Prep & AI training              | Moqui structure + domain specs |
| 1     | Domain modeling + base services | Entities + CRUD + transitions  |
| 2     | Experience layer + UI shells    | End-to-end flow (draft)        |
| 3     | Conversational integration      | MCP chat working               |
| 4     | Complete domain features        | Full vertical slices           |
| 5     | Hardening + UX refinement       | Stable prototype               |
| 6     | Demo-ready system               | Pilot-capable                  |

---