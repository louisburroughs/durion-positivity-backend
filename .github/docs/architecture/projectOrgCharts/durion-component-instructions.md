Here’s a Moqui-style component layout mapped to *your* POS domains. I’ll show the folder tree and then briefly explain what lives where and how it maps to your base vs experience layers and UI/MCP.

I’ll assume:

* Project code name: **durion**
* Components live under: `runtime/component/`

You can change the prefixes, but the structure (one component per domain slice + shared components) is what matters.

---

## 1. Top-level component layout

```text
runtime/
  component/
    durion-common/
    durion-theme/
    durion-crm/
    durion-product/
    durion-inventory/
    durion-workexec/          # Workorder + Shop Mgmt + ERS
    durion-accounting/
    durion-experience/        # Experience APIs (BFF)
    durion-mcp/               # MCP tools + integration
    durion-demo-data/         # Seed data for prototype
```

Think of it this way:

* **durion-*** = domain components + cross-cutting
* Each domain component carries its **Moqui entities + services + (optional) internal screens**
* **Experience + MCP + UI** are separated so your Moqui internals don’t leak.

---

## 2. Shared base components

### `durion-common` – shared primitives, enums, and utility services

```text
runtime/component/durion-common/
  entity/
    DurCommonType.xml          # enums, status types
    DurUom.xml                 # units of measure
    DurGeo.xml                 # basic region/country if needed
  service/
    DurCommonServices.xml      # helper services
  script/
    groovy/
      DurCommonServices.groovy
  data/
    DurCommonSeed.xml
```

**Use this for:**

* Common types (status, tax categories, generic references).
* Simple utility services (ID generation, shared validations).

---

### `durion-theme` – brand & visual theme

```text
runtime/component/durion-theme/
  screen/
    durion/
      layout/
        MainLayout.xml
      widget/
        CommonHeader.xml
        CommonFooter.xml
  template/
    durion/
      css/
        durion-theme.css.ftl
  data/
    DurThemePreference.xml
```

**Use this for:**

* Header/footer, common layout, branding.
* CSS / theme overrides for Moqui screens and admin tools.
* You can reuse this across all components with `<sub-screen>` includes.

---

## 3. CRM Component

### `durion-crm` – Customers, Vehicles, Fleets

```text
runtime/component/durion-crm/
  entity/
    DurCustomer.xml
    DurVehicle.xml
    DurFleet.xml
    DurContactMech.xml
  service/
    DurCrmServices.xml
  script/
    groovy/
      DurCrmServices.groovy
  screen/
    durion/
      crm/
        CustomerFind.xml
        CustomerEdit.xml
        VehicleFind.xml
        VehicleEdit.xml
  data/
    DurCrmDemoData.xml
```

**Maps to your domains:**

* Customers
* Vehicles

**Responsibilities:**

* Define entities and base services for customer + vehicle.
* Provide admin/maintenance screens (even if your main UI is external SPA).

---

## 4. Product & Pricing Component

### `durion-product`

```text
runtime/component/durion-product/
  entity/
    DurProduct.xml
    DurServiceOperation.xml
    DurPriceList.xml
    DurPriceListItem.xml
  service/
    DurProductServices.xml
  script/
    groovy/
      DurProductServices.groovy
  screen/
    durion/
      product/
        ProductFind.xml
        ProductEdit.xml
        PriceListFind.xml
        PriceListEdit.xml
  data/
    DurProductDemoData.xml
```

**Maps to your domains:**

* Product List
* Service List
* Price List

**Responsibilities:**

* Product & service catalog entities and services.
* Pricing logic + list lookups.

---

## 5. Inventory Control Component

### `durion-inventory`

```text
runtime/component/durion-inventory/
  entity/
    DurInventoryItem.xml
    DurInventoryLocation.xml
    DurInventoryReceipt.xml
    DurInventoryReservation.xml
  service/
    DurInventoryServices.xml
  script/
    groovy/
      DurInventoryServices.groovy
  screen/
    durion/
      inventory/
        InventoryItemFind.xml
        InventoryItemSummary.xml
        Receiving.xml
        Adjustment.xml
  data/
    DurInventoryDemoData.xml
```

**Maps to your domains:**

* Putaway
* Pick
* Receiving
* Location
* Cycle Count (starter entities; full cycle count can come later)

**Responsibilities:**

* Define stock, locations, receipts, reservations.
* Provide services to check availability and reserve stock for workorders.

---

## 6. Workorder + Shop Mgmt Component

### `durion-workexec` – Workorder, Execution, ERS, Shop Management

```text
runtime/component/durion-workexec/
  entity/
    DurWorkOrder.xml
    DurWorkOrderItem.xml
    DurEstimate.xml
    DurInvoice.xml
    DurWarrantyCase.xml
    DurDeliveryReceipt.xml
    DurScheduleSlot.xml
    DurMechanic.xml
    DurBay.xml
    DurMobileUnit.xml
  service/
    DurWorkExecServices.xml
  script/
    groovy/
      DurWorkExecServices.groovy
  screen/
    durion/
      workexec/
        EstimateCreate.xml
        EstimateEdit.xml
        WorkOrderBoard.xml
        WorkOrderEdit.xml
        InvoiceView.xml
        WarrantyCaseFind.xml
        RoadsideDispatch.xml
  data/
    DurWorkExecDemoData.xml
```

**Maps to your domains:**

* Workorder

  * Warranty
  * Estimate
  * Workorder Execution
  * Delivery Receipt
  * Invoicing (Moqui side)
  * Scheduling
  * Emergency Roadside Service

* Shop Management

  * Mechanics
  * Bays
  * Mobile Stations

**Responsibilities:**

* Workorder, estimate, invoice state machines.
* Scheduling & assignment model.
* ERS flows and roadside reference data.

---

## 7. Accounting (AR prototype) Component

### `durion-accounting`

```text
runtime/component/durion-accounting/
  entity/
    DurArTransaction.xml
    DurPayment.xml
  service/
    DurAccountingServices.xml
  script/
    groovy/
      DurAccountingServices.groovy
  screen/
    durion/
      accounting/
        ArTransactionFind.xml
        PaymentEntry.xml
  data/
    DurAccountingDemoData.xml
```

**Maps to your domains:**

* Accounts Receivable
* (AP/GL can be deliberately out-of-scope for prototype)

**Responsibilities:**

* AR posting and simple customer balance.
* Payment capture and link to DurInvoice.

---

## 8. Experience Layer Component

### `durion-experience` – BFF / Orchestration APIs

This is where you **separate Moqui from your UI/MCP**.

```text
runtime/component/durion-experience/
  service/
    DurExperienceWorkExec.xml
    DurExperienceInventory.xml
    DurExperienceProduct.xml
    DurExperienceCrm.xml
    DurExperienceAccounting.xml
  script/
    groovy/
      DurExperienceWorkExec.groovy
      DurExperienceInventory.groovy
      DurExperienceProduct.groovy
      DurExperienceCrm.groovy
      DurExperienceAccounting.groovy
  rest/
    durion-experience-rest.xml     # REST mappings for experience services
```

**Responsibilities:**

* Orchestrate across domain components.
* Define **UI/MCP-facing DTOs**.
* Provide stable REST endpoints that frontends and MCP use:

  * `createEstimateForCustomerVehicle`
  * `getWorkOrderDetail`
  * `getAvailablePartsForJob`
  * `postInvoiceAndPayment`
  * etc.

---

## 9. MCP Integration Component

### `durion-mcp`

```text
runtime/component/durion-mcp/
  service/
    DurMcpTools.xml
  script/
    groovy/
      DurMcpTools.groovy          # calls experience services
  data/
    DurMcpToolConfig.xml          # configuration for MCP tools
```

**Responsibilities:**

* Define MCP tools in terms of *experience* services, not Moqui base services.
* Keep MCP-specific logic and configuration decoupled from the domains.

---

## 10. Demo Data Component

### `durion-demo-data`

```text
runtime/component/durion-demo-data/
  data/
    DurDemoCrm.xml
    DurDemoProduct.xml
    DurDemoInventory.xml
    DurDemoWorkExec.xml
    DurDemoAccounting.xml
```

**Responsibilities:**

* Provide coherent test data across components for demos & environments.
* Keeps demo load out of the domain logic components.

---

## 11. How this maps to your architecture

* **Domains → Components**:
  Each row of your original domain list is now a **component** (or part of `durion-workexec`).

* **Moqui Entities + Base Services**:
  Live in each `durion-<domain>/entity` and `durion-<domain>/service`.

* **Experience Layer**:
  Centralized in `durion-experience`, calling into the domain components.

* **UI / MCP**:
  Talk only to `durion-experience` (REST) and `durion-mcp` (tools), never directly to the Moqui base services.

---