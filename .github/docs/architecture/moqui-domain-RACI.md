Below is a **Moqui-specific responsibility matrix per domain**, designed so each domain team knows EXACTLY what they own across:

* **Moqui Entity Engine responsibilities**
* **Moqui Service Engine responsibilities**
* **Experience Layer responsibilities**
* **UI responsibilities**
* **MCP/Chat responsibilities**
* **Cross-domain & platform constraints**
* **Agentic AI support roles**

This is *not* a generic matrix—it's built specifically for:

* Your **domain list** (Workorder, Inventory, CRM, Product, Accounting, Shop Management)
* Your **2-layer architecture** (Moqui base + Experience layer)
* Your **vertical-slice delivery model**
* Your **agentic AI automation strategy**
* Your **6-month prototype goals**

---

# ⭐ **Moqui-Specific Responsibility Matrix by Domain**

Each domain section includes:

1. **Moqui Entity Responsibilities**
2. **Moqui Base Service Responsibilities**
3. **Experience Layer Responsibilities**
4. **UI Responsibilities**
5. **MCP / Chat Responsibilities**
6. **Cross-Domain Responsibilities**
7. **Agentic AI Responsibilities**

---

# 1️⃣ **Work Execution & Billing Domain**

*(Estimate → Workorder → Execution → Warranty → Invoice → Payment)*

### **Moqui Entity Responsibilities**

**Responsible:** Work Execution Team
**Accountable:** Architect

* Define entities: `Estimate`, `WorkOrder`, `WorkOrderItem`, `WorkStep`, `Invoice`, `InvoiceItem`, `WarrantyCase`, etc.
* Define state fields (Draft, Approved, In Progress, Completed, Invoiced).
* Define relationships to Customer, Vehicle, Product, InventoryItem.
* Ensure entity definitions align with golden flows.

### **Moqui Base Service Responsibilities**

**Responsible:** Work Execution Team
**Accountable:** Work Execution Lead

* Implement state transitions (estimate→workorder, workorder→invoice).
* Implement validations: pricing, labor-time, parts availability.
* Implement business rules (tax, discount, roadside logic).
* Implement transitions for warranty-specific processes.

### **Experience Layer Responsibilities**

**Responsible:** Work Execution Team
**Accountable:** Architect

* Create orchestration APIs:

  * `createEstimate()`
  * `approveEstimate()`
  * `startWorkorder()`
  * `addPartOrService()`
  * `completeWorkorder()`
  * `generateInvoice()`
  * `postPayment()`

These APIs hide Moqui internals and deliver UX-friendly DTOs.

### **UI Responsibilities**

**Responsible:** UI Team
**Consulted:** Work Execution

* Screens: Estimate editor, Workorder page, Close-out/invoice page.
* Workflow guidance UI (progress indicators).
* Error-handling aligned with experience APIs.

### **MCP / Chat Responsibilities**

**Responsible:** Conversational Team
**Consulted:** Work Execution

* Conversational tools:

  * “Create estimate for VIN ___.”
  * “Add tire rotation.”
  * “Summarize workorder.”
  * “What is blocking completion?”

### **Cross-Domain Responsibilities**

* Depends on CRM for customer/vehicle.
* Depends on Inventory for stock availability.
* Notifies Accounting for invoice posting.

### **Agentic AI Responsibilities**

**Domain Clarification Agent:** Ensures state machines are valid
**Scaffolding Agent:** Generates entities/services/DTOs
**Integration Agent:** Tests estimate→workorder→invoice flow
**Risk Agent:** Detects domain drift (most common here)

---

# 2️⃣ **Inventory Control Domain**

*(Receiving → Location → Availability → Picking → Putaway)*

### **Moqui Entity Responsibilities**

**Responsible:** Inventory Team
Define:

* `InventoryItem`, `InventoryReceipt`, `InventoryLocation`, `InventoryReservation`.

### **Moqui Base Service Responsibilities**

**Responsible:** Inventory Team

* Receiving logic
* Adjustments
* Location assignments
* Inventory reservation for workorders

### **Experience Layer Responsibilities**

**Responsible:** Inventory Team
**Accountable:** Architect

* APIs:

  * `checkAvailability(partId, locationId)`
  * `reserveInventoryForWorkorder()`
  * `releaseReservation()`
  * `receiveStock()`
* Provide simplified “availability snapshots” for UI/MCP.

### **UI Responsibilities**

**Responsible:** UI Team

* Inventory lookup pages
* Receiving screens (for prototype)
* Stock adjustments

### **MCP Responsibilities**

**Responsible:** Conversational Team

* Tools:

  * “Do we have size 295/75R22.5 in stock?”
  * “Reserve two units for WO12345.”

### **Cross-Domain Responsibilities**

* Supports Work Execution for part picking.
* Depends on Product domain for part definitions.

### **Agentic AI Responsibilities**

* Validate entity and reservation logic
* Generate tests for part-picking flows
* Detect schema drift between Product and Inventory

---

# 3️⃣ **Product & Pricing Domain**

*(Product Catalog → Service List → Price List)*

### **Moqui Entity Responsibilities**

**Responsible:** Product Team

* Entities: `Product`, `ServiceOperation`, `PriceList`, `PriceListItem`.
* Maintain consistent keys for service operations.

### **Moqui Base Service Responsibilities**

**Responsible:** Product Team

* Pricing lookup services
* Product creation & grouping
* Service-op dependencies (labor categories, skill requirements)

### **Experience Layer Responsibilities**

**Responsible:** Product Team
**Accountable:** Architect

* APIs:

  * `getEffectivePrice(productId, customerId)`
  * `getServiceCatalog()`
  * “Operation bundles”

### **UI Responsibilities**

**Responsible:** UI Team

* CRUD for product, services, prices. (Prototype-level forms)

### **MCP Responsibilities**

**Responsible:** Conversational Team

* Tools:

  * “Add the brake inspection service.”
  * “Show tire price options.”

### **Cross-Domain Responsibilities**

* Provides pricing & product definitions to Work Execution & Inventory.
* Must avoid cyclic dependencies.

### **Agentic AI Responsibilities**

* Detect price list inconsistencies
* Auto-generate DTOs
* Test price propagation into estimates

---

# 4️⃣ **CRM Domain (Customers, Vehicles, Campaigns)**

### **Moqui Entity Responsibilities**

**Responsible:** CRM Team
Define:

* `Customer`, `Vehicle`, `ContactMechanism`, `FleetAccount`, `VehicleAttributes`.

### **Moqui Base Service Responsibilities**

**Responsible:** CRM Team

* Customer creation & validation
* Vehicle creation, VIN decoding hooks (stubbed prototype)
* Fleet-unit linking logic

### **Experience Layer Responsibilities**

**Responsible:** CRM Team

* APIs:

  * `lookupCustomer(query)`
  * `getVehiclesForCustomer()`
  * `createFleetUnit()`

### **UI Responsibilities**

**Responsible:** UI Team

* Customer maintenance screens
* Vehicle profile screens

### **MCP Responsibilities**

**Responsible:** Conversational Team

* Tools:

  * “Find customer by phone number.”
  * “Lookup fleet unit ABC-123.”

### **Cross-Domain Responsibilities**

* Provides data to Work Execution (mandatory dependency).
* Ensures identity of customer/vehicle is stable.

### **Agentic AI Responsibilities**

* Ensure customer/vehicle schemas remain stable
* Test flows involving customer lookup → estimate creation

---

# 5️⃣ **Shop Management Domain**

*(Mechanics, Bays, Mobile Stations, Schedule)*

### **Moqui Entity Responsibilities**

**Responsible:** Work Execution Team (shared)

* Entities: `Mechanic`, `Bay`, `Shift`, `Assignment`, `MobileUnit`.

### **Moqui Base Service Responsibilities**

**Responsible:** Work Execution Team

* Assign mechanic to workorder
* Assign bay or mobile station
* Basic scheduling services
* Enforce skill requirements from Product domain

### **Experience Layer Responsibilities**

**Responsible:** Work Execution Team

* APIs:

  * `assignMechanic()`
  * `assignBay()`
  * `getScheduledJobs(mechanicId)`
  * `scheduleWork()`

### **UI Responsibilities**

**Responsible:** UI Team

* Scheduling dashboard
* Job assignment interface
* Mechanic mobile view

### **MCP Responsibilities**

**Responsible:** Conversational Team

* Tools:

  * “What jobs are assigned to John today?”
  * “Assign this job to the next available tech.”

### **Cross-Domain Responsibilities**

* Links to Work Execution, CRM, Product (skill requirements).

### **Agentic AI Responsibilities**

* Validate scheduling logic
* Test assignment workflows

---

# 6️⃣ **Accounting (Prototype: AR Only)**

*(Invoices → Payments → Simple Posting)*

### **Moqui Entity Responsibilities**

**Responsible:** Accounting Team
Define:

* `ARTransaction`, `Payment`, `InvoicePostingRecord`.

### **Moqui Base Service Responsibilities**

**Responsible:** Accounting Team

* Post invoice
* Record payment
* Simple customer balance logic

### **Experience Layer Responsibilities**

**Responsible:** Accounting Team

* APIs:

  * `postInvoice()`
  * `recordPayment()`
  * `getCustomerBalance()`

### **UI Responsibilities**

**Responsible:** UI Team

* Payment capture forms
* Invoice summary screens

### **MCP Responsibilities**

**Responsible:** Conversational Team

* Tools:

  * “What is the outstanding balance on Workorder 3456?”
  * “Record a $150 card payment.”

### **Cross-Domain Responsibilities**

* Accepts invoices from Work Execution
* Sends balances to CRM for fleet visibility

### **Agentic AI Responsibilities**

* Flag scope creep (full GL/AP not allowed)
* Validate invoice completeness
* Test invoice→payment flow

---

# 🔥 Cross-Domain Ownership Summary Matrix

| Domain                | Entity Engine | Moqui Services | Experience Layer | UI | MCP | Cross-Domain Responsibilities | AI Agents                    |
| --------------------- | ------------- | -------------- | ---------------- | -- | --- | ----------------------------- | ---------------------------- |
| **Work Execution**    | R             | R              | R                | C  | C   | Invoice, Inventory, CRM       | All agents (major domain)    |
| **Inventory Control** | R             | R              | R                | C  | C   | Product, Work Execution       | Clarif., Scaff., Integration |
| **Product/Pricing**   | R             | R              | R                | C  | C   | Pricing for Work Execution    | Clarif., Scaff., Risk        |
| **CRM**               | R             | R              | R                | C  | C   | Customer/Vehicle identity     | Clarif., Integration         |
| **Shop Mgmt**         | R             | R              | R                | C  | C   | Assignments, Scheduling       | Clarif., Scaff., Integration |
| **Accounting**        | R             | R              | R                | C  | C   | Receives invoices             | Clarif., Risk                |

---

# 🧠 MOQUI-SPECIFIC INSIGHTS

### **1. Entity definitions are the highest-impact artifacts in Moqui**

→ Domain Clarification Agent + Architect must approve all entity changes.

### **2. Experience Layer is the universal boundary**

→ Work Execution, Inventory, CRM, Product, Accounting all expose clean APIs.

### **3. MCP ONLY talks to the experience layer**

→ Never directly to Moqui services.

### **4. Vertical slices control their own experience services**

→ Prevents the “platform bottleneck” that delays delivery.

### **5. AI agents become governance tools**

→ Ensuring Moqui’s schema-first nature doesn’t lock in mistakes.

---