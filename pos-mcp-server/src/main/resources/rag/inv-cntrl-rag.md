# Inventory Control Best Practices & Terminology

_For Use in Durion ETSMS / Positivity RAG Knowledge Base_

---

## 1. Purpose

This document provides a structured reference for inventory control concepts, terminology, and best practices. It is intended to support a natural-language interface (NLI) in:

- Interpreting inventory-related queries
- Managing stock movements and states
- Ensuring accurate valuation and availability
- Enforcing operational and financial integrity

This document complements the inventory module README and related domain contracts.

---

## 2. Core Objectives of Inventory Control

An effective inventory control system must:

- Maintain **accurate stock levels**
- Ensure **product availability** without overstocking
- Enable **traceability** of inventory movements
- Support **financial accuracy** (COGS, valuation)
- Optimize **working capital utilization**

---

## 3. Fundamental Inventory Concepts

### 3.1 Inventory

Goods held for:

- Sale (finished goods)
- Use in operations (consumables)
- Transformation (raw materials)

---

### 3.2 Stock Levels

| Term                   | Definition                            |
| ---------------------- | ------------------------------------- |
| **On-Hand Quantity**   | Physical stock available              |
| **Available Quantity** | On-hand minus reservations            |
| **Reserved Quantity**  | Allocated for orders/workorders       |
| **In-Transit**         | Ordered but not yet received          |
| **Backordered**        | Demand exceeding current availability |

---

### 3.3 Inventory States

Inventory may exist in different states:

- **Available**
- **Reserved**
- **Damaged**
- **In Inspection**
- **Quarantined**
- **In Transit**

State transitions must be explicit and auditable.

---

## 4. Inventory Movements

All inventory changes should be modeled as **events**.

### Common Movement Types

| Movement                | Description                        |
| ----------------------- | ---------------------------------- |
| **Receipt**             | Stock received from supplier       |
| **Issue / Consumption** | Stock used (e.g., workorder)       |
| **Transfer**            | Movement between locations         |
| **Adjustment**          | Manual correction                  |
| **Return**              | Customer or vendor return          |
| **Scrap**               | Removal due to damage/obsolescence |

---

## 5. Units of Measure (UoM)

- Each product must define a **base unit of measure**
- Conversions must be deterministic (e.g., cases → units)
- Avoid floating ambiguity in conversions

---

## 6. Location Management

### 6.1 Location Types

| Type                    | Description                   |
| ----------------------- | ----------------------------- |
| **Warehouse**           | Central storage               |
| **Bin**                 | Sub-location within warehouse |
| **Truck / Mobile Unit** | Field inventory               |
| **Shop Floor**          | Operational staging           |
| **Quarantine Area**     | Restricted inventory          |

---

### 6.2 Best Practices

- Use **hierarchical location structures**
- Track inventory at the **lowest practical level (bin-level if needed)**
- Maintain clear ownership and responsibility

---

## 7. Inventory Valuation Methods

### Common Methods

| Method                         | Description                                |
| ------------------------------ | ------------------------------------------ |
| **FIFO (First-In, First-Out)** | Oldest inventory used first                |
| **LIFO (Last-In, First-Out)**  | Newest inventory used first                |
| **Weighted Average Cost**      | Average cost across all units              |
| **Standard Cost**              | Predefined cost for accounting consistency |

---

### Guidance

- FIFO is generally preferred for operational clarity
- Weighted average simplifies high-volume environments
- Standard cost useful for planning and variance analysis

---

## 8. Replenishment Strategies

### 8.1 Reorder Point (ROP)

Trigger replenishment when:

```

ROP = Demand during lead time + Safety stock

```

---

### 8.2 Safety Stock

Buffer to absorb variability:

- Demand fluctuations
- Supplier delays

---

### 8.3 Economic Order Quantity (EOQ)

Optimizes order size to minimize:

- Holding costs
- Ordering costs

---

### 8.4 Min/Max Levels

- **Min:** Reorder threshold
- **Max:** Target stock level after replenishment

---

## 9. Cycle Counting & Physical Inventory

### 9.1 Cycle Counting

- Continuous partial counts
- Focus on high-value or high-movement items

### 9.2 Physical Inventory

- Full count at a point in time
- Used for financial reconciliation

---

### Best Practices

- Use **ABC classification** to prioritize counting:
  - A: High value
  - B: Medium value
  - C: Low value
- Investigate and resolve variances immediately

---

## 10. Key Inventory Metrics

| Metric                               | Description                        |
| ------------------------------------ | ---------------------------------- |
| **Inventory Turnover**               | Frequency of inventory replacement |
| **Days Inventory Outstanding (DIO)** | Days inventory is held             |
| **Fill Rate**                        | % of demand fulfilled from stock   |
| **Stockout Rate**                    | Frequency of unavailable items     |
| **Shrinkage**                        | Loss due to theft, damage, errors  |

---

## 11. Traceability & Lot Control

### 11.1 Lot / Batch Tracking

- Track groups of items received together
- Useful for recalls and quality control

### 11.2 Serial Tracking

- Track individual items uniquely

---

### Best Practices

- Use lot tracking for consumables (e.g., tires, parts batches)
- Use serial tracking for high-value or regulated items

---

## 12. Integration with Operations (ETSMS Context)

### 12.1 Workorders

- Parts consumption reduces inventory
- Must trigger:
  - Inventory decrement
  - Cost recognition (COGS)

---

### 12.2 Procurement

- Purchase orders increase in-transit inventory
- Receipt converts to on-hand inventory

---

### 12.3 Returns

- Customer returns increase inventory (if resellable)
- Vendor returns decrease inventory

---

## 13. System Design Principles

### 13.1 Event-Driven Model

All inventory changes should be represented as immutable events:

- InventoryReceived
- InventoryConsumed
- InventoryTransferred
- InventoryAdjusted

---

### 13.2 Immutability

- Do not overwrite stock history
- Corrections via compensating transactions

---

### 13.3 Real-Time Accuracy

- Update availability immediately upon reservation or consumption

---

### 13.4 Idempotency

- Prevent duplicate posting of inventory events

---

### 13.5 Consistency with Accounting

- Inventory movements must align with financial entries:
  - Consumption → COGS
  - Receipt → Asset increase

---

## 14. Common Terminology

| Term                         | Definition                        |
| ---------------------------- | --------------------------------- |
| **SKU (Stock Keeping Unit)** | Unique product identifier         |
| **Lead Time**                | Time from order to receipt        |
| **Safety Stock**             | Buffer inventory                  |
| **Backorder**                | Unfulfilled demand                |
| **Allocation**               | Reservation of stock              |
| **Putaway**                  | Process of storing received goods |
| **Pick**                     | Selecting items for use/shipment  |
| **Cycle Count**              | Partial inventory audit           |
| **Shrinkage**                | Inventory loss                    |
| **Stock Ledger**             | Record of inventory movements     |

---

## 15. Natural Language Interpretation Guidelines

The NLI should:

### Identify:

- Product/SKU
- Quantity
- Location
- Movement type (receive, consume, transfer, adjust)

### Map to:

- Correct inventory event
- Valid state transition
- Associated financial impact

### Validate:

- Sufficient available stock (for consumption)
- Valid location
- UoM consistency

---

## 16. Error Handling Patterns

### Common Errors

- Negative inventory
- Invalid location
- Duplicate transaction
- UoM mismatch
- Untracked lot/serial when required

---

### Handling Strategy

- Reject invalid operations before commit
- Provide corrective suggestions
- Log all attempted invalid actions

---

## 17. Summary

Effective inventory control requires:

- Accurate tracking of quantities and states
- Clear event-driven movement modeling
- Alignment with financial systems
- Strong validation and auditability

The system must enforce:

- Traceability of all inventory movements
- Real-time availability accuracy
- Integration with procurement, work execution, and accounting

---

## 18. Suggested Extensions (Future RAG Enhancements)

- Tire-specific inventory handling (sizes, wear states)
- Mobile truck inventory optimization
- Predictive replenishment using usage patterns
- Vendor performance tracking
- Multi-location optimization strategies

---
