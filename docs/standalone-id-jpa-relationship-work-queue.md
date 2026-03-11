# Standalone Id to JPA Relationship Work Queue

Last Updated: 2026-03-10

## Status Legend

- `CONVERT_NOW`: same-module relationship candidate ready for migration
- `DONE`: converted and validated
- `KEEP_SCALAR`: cross-service/external reference, must remain scalar
- `DEFER`: postponed due lifecycle/cycle/ownership risk

## Queue

| Module | Entity | Field | Classification | Status | Notes |
|---|---|---|---|---|---|
| `pos-price` | `PromotionEligibilityRule` | `promotionId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne PromotionOffer` on 2026-03-10. |
| `pos-accounting` | `GLMapping` | `postingCategoryId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne PostingCategory` on 2026-03-10 with scalar compatibility accessor methods. |
| `pos-accounting` | `GLMapping` | `glAccountId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne GLAccount` on 2026-03-10. |
| `pos-accounting` | `GLMapping` | `mappingKeyId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne MappingKey` on 2026-03-10. |
| `pos-accounting` | `Reconciliation` | `glAccountId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne GLAccount` on 2026-03-10. |
| `pos-accounting` | `StatementLineMapping` | `glAccountId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne GLAccount` on 2026-03-10. |
| `pos-accounting` | `DefaultGLMapping` | `debitAccountId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne GLAccount debitAccount` on 2026-03-10. |
| `pos-accounting` | `DefaultGLMapping` | `creditAccountId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne GLAccount creditAccount` on 2026-03-10. |
| `pos-accounting` | `JournalEntryLine` | `glAccountId` | `CONVERT_NOW` | `DONE` | Dual-mapped fix: removed scalar, made `@ManyToOne GLAccount` owning on 2026-03-10. |
| `pos-accounting` | `JournalEntry` | `postingRuleSetId` | `CONVERT_NOW` | `DONE` | Dual-mapped fix: removed scalar, made `@ManyToOne PostingRuleSet` owning on 2026-03-10. |
| `pos-accounting` | `JournalEntry` | `postingRuleVersionId` | `CONVERT_NOW` | `DONE` | Dual-mapped fix: removed scalar, made `@ManyToOne PostingRuleVersion` owning on 2026-03-10. |
| `pos-accounting` | `GLAccount` | `parentAccountId` | `CONVERT_NOW` | `DONE` | Self-ref: converted to `@ManyToOne GLAccount parentAccount` on 2026-03-10. |
| `pos-accounting` | `JournalEntry` | `reversalJournalEntryId` | `CONVERT_NOW` | `DONE` | Self-ref: converted to `@ManyToOne JournalEntry reversalJournalEntry` on 2026-03-10. |
| `pos-accounting` | `JournalEntry` | `reversedByJournalEntryId` | `CONVERT_NOW` | `DONE` | Self-ref: converted to `@ManyToOne JournalEntry reversedByJournalEntry` on 2026-03-10. |
| `pos-accounting` | `VendorBill` | `journalEntryId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne JournalEntry` on 2026-03-10. |
| `pos-accounting` | `APPayment` | `glJournalEntryId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne JournalEntry glJournalEntry` on 2026-03-10. |
| `pos-workorder` | `EstimateSnapshot` | `estimateId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne Estimate estimate` on 2026-03-10. |
| `pos-workorder` | `WorkorderSnapshot` | `workorderId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne Workorder workorder` on 2026-03-10. |
| `pos-workorder` | `WorkorderStateTransition` | `workorderId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne Workorder workorder` on 2026-03-10. |
| `pos-workorder` | `ApprovalRecord` | `changeRequestId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne ChangeRequest changeRequest` on 2026-03-10. |
| `pos-workorder` | `ApprovalRecord` | `workorderId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne Workorder workorder` on 2026-03-10. |
| `pos-workorder` | `Estimate` | `approvalConfigurationId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne ApprovalConfiguration approvalConfiguration` on 2026-03-10. |
| `pos-workorder` | `WorkorderPart` | `originEstimateItemId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne EstimateItem originEstimateItem` on 2026-03-10. |
| `pos-workorder` | `WorkorderPart` | `changeRequestId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne ChangeRequest changeRequest` on 2026-03-10. |
| `pos-workorder` | `WorkorderService` | `originEstimateItemId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne EstimateItem originEstimateItem` on 2026-03-10. |
| `pos-workorder` | `WorkorderService` | `changeRequestId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne ChangeRequest changeRequest` on 2026-03-10. |
| `pos-workorder` | `TimeEntryAdjustment` | `timeEntryId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne TimeEntry timeEntry` on 2026-03-10. Non-standard PK: `timeEntryId`. |
| `pos-workorder` | `WorkOrderPartSubstitution` | `workorderId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne Workorder workorder` on 2026-03-10. |
| `pos-workorder` | `WorkOrderPartSubstitution` | `workorderLineItemId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne WorkorderPart workorderLineItem` on 2026-03-10. |
| `pos-workorder` | `TravelSegment` | `workOrderId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne Workorder workOrder` (nullable) on 2026-03-10. |
| `pos-workorder` | `TravelSegmentAdjustment` | `travelSegmentId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne TravelSegment travelSegment` on 2026-03-10. Non-standard PK: `travelSegmentId`. |
| `pos-workorder` | `SubstituteAudit` | `linkId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne SubstituteLink link` on 2026-03-10. |
| `pos-workorder` | `WorkorderLaborEntry` | `workorderServiceId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne WorkorderService workorderService` on 2026-03-10. |
| `pos-workorder` | `TimeEntry` | `workOrderId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne Workorder workOrder` on 2026-03-10. |
| `pos-workorder` | `WorkSession` | `workOrderId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne Workorder workOrder` on 2026-03-10. |
| `pos-workorder` | `TechnicianAssignment` | `workorderId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne Workorder workorder` on 2026-03-10. |
| `pos-workorder` | `WorkorderPartAdjustmentEvent` | `workorderId` | `DEFER` | `DEFER` | Event entity; should not have JPA relationships. |
| `pos-workorder` | `WorkorderPartAdjustmentEvent` | `substitutedWithPartId` | `DEFER` | `DEFER` | Event entity; should not have JPA relationships. |
| `pos-workorder` | `WorkorderPartUsageEvent` | `workorderId` | `DEFER` | `DEFER` | Event entity; should not have JPA relationships. |
| `pos-workorder` | `ApprovalConfiguration` | `customerId` | `KEEP_SCALAR` | `DONE` | Cross-service: customer owned by pos-customer. |
| `pos-workorder` | `Estimate` | `customerId` | `KEEP_SCALAR` | `DONE` | Cross-service: customer owned by pos-customer. |
| `pos-workorder` | `Estimate` | `vehicleId` | `KEEP_SCALAR` | `DONE` | Cross-service: vehicle owned by pos-vehicle-*. |
| `pos-workorder` | `Workorder` | `customerId` | `KEEP_SCALAR` | `DONE` | Cross-service: customer owned by pos-customer. |
| `pos-workorder` | `Workorder` | `vehicleId` | `KEEP_SCALAR` | `DONE` | Cross-service: vehicle owned by pos-vehicle-*. |
| `pos-workorder` | `IdempotencyKey` | `invoiceId` | `KEEP_SCALAR` | `DONE` | Cross-service: invoice owned by pos-invoice. |
| `pos-workorder` | `IdempotencyKey` | `partUsageEventId` | `DEFER` | `DEFER` | Event entity target; requires lifecycle strategy. |
| `pos-workorder` | `IdempotencyKey` | `partAdjustmentEventId` | `DEFER` | `DEFER` | Event entity target; requires lifecycle strategy. |
| `pos-price` | `RestrictionRule` | `productId` | `KEEP_SCALAR` | `DONE` | Product owned by another service/module. |
| `pos-price` | `ProductBasePrice` | `productId` | `KEEP_SCALAR` | `DONE` | Product owned by another service/module. |
| `pos-price` | `LocationPriceOverride` | `productId` | `KEEP_SCALAR` | `DONE` | Product owned by another service/module. |
| `pos-price` | `LocationPriceOverride` | `locationId` | `KEEP_SCALAR` | `DONE` | Location owned by another service/module. |
| `pos-price` | `CustomerTierPricingRule` | `productId` | `KEEP_SCALAR` | `DONE` | Product owned by another service/module. |
| `pos-price` | `CustomerTierPricingRule` | `customerTierId` | `KEEP_SCALAR` | `DONE` | Customer tier owned by another service/module. |
| `pos-inventory` | `CycleCountTask` | `latestCountEntryId` | `DEFER` | `DEFER` | Cyclic persistence/teardown risk; requires explicit lifecycle strategy. |
| `pos-workorder` | `IdempotencyKey` | `workorderId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne Workorder workorder` on 2026-03-10. |
| `pos-workorder` | `IdempotencyKey` | `changeRequestId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne ChangeRequest changeRequest` on 2026-03-10. |
| `pos-workorder` | `IdempotencyKey` | `laborEntryId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne WorkorderLaborEntry laborEntry` on 2026-03-10. |
| `pos-workorder` | `Workorder` | `estimateId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne Estimate estimate` on 2026-03-10. |
| `pos-workorder` | `EstimateItem` | `estimateId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne Estimate estimate` on 2026-03-10. High impact: 1 entity, 4 repo methods, 11 service calls, 15+ test file updates. |

## Next Candidates

- Classify and convert remaining modules (pos-shop-manager, pos-people, pos-location, pos-inventory, pos-invoice, pos-order, pos-catalog, pos-customer).
