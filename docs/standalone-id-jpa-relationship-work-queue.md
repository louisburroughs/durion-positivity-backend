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

| `pos-location` | `BayEntity` | `locationId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne Location` on 2026-03-10. |
| `pos-location` | `StorageLocationEntity` | `siteId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne Location site` on 2026-03-10. |
| `pos-location` | `StorageLocationEntity` | `parentStorageLocationId` | `CONVERT_NOW` | `DONE` | Self-ref: converted to `@ManyToOne StorageLocationEntity parentStorageLocation` on 2026-03-10. |
| `pos-location` | `Location` | `defaultStagingLocationId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne StorageLocationEntity defaultStagingLocation` on 2026-03-10. |
| `pos-location` | `Location` | `defaultQuarantineLocationId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne StorageLocationEntity defaultQuarantineLocation` on 2026-03-10. |
| `pos-location` | `MobileUnitEntity` | `baseLocationId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne Location baseLocation` (nullable) on 2026-03-10. |
| `pos-shop-manager` | `OverrideRecord` | `appointmentId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne Appointment` on 2026-03-10. |
| `pos-shop-manager` | `RescheduleHistory` | `appointmentId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne Appointment` on 2026-03-10. |
| `pos-shop-manager` | `WorkOrderAppointmentMapping` | `appointmentId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne Appointment` on 2026-03-10. |
| `pos-shop-manager` | `AppointmentAudit` | `appointmentId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne Appointment` on 2026-03-10. |
| `pos-shop-manager` | `AssignmentMechanic` | `mechanicId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne Mechanic` on 2026-03-10. |
| `pos-shop-manager` | `MechanicSkill` | `mechanicId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne Mechanic` on 2026-03-10. |
| `pos-people` | `TimeEntryAdjustment` | `timeEntryId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne TimeEntry timeEntry` on 2026-03-10. |
| `pos-people` | `WorkSession` | `personId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne Person person` on 2026-03-10. |
| `pos-people` | `WorkSessionBreak` | `sessionId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne WorkSession session` on 2026-03-10. |
| `pos-people` | `UserPersonLink` | `personId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne Person person` on 2026-03-10. |
| `pos-people` | `TimekeepingEntry` | `sourceSessionId` | `KEEP_SCALAR` | `DONE` | Not a true FK: correction flow sets to correctionId, used as dedup key per unique constraint (tenant_id, source_system, source_session_id). |
| `pos-people` | `PersonLocationAssignment` | `personId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne Person person` on 2026-03-10. |
| `pos-people` | `EmployeeOffboardingRetry` | `employeeId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne Person employee` on 2026-03-10. |
| `pos-vehicle-reference-carapi` | `CarApiModel` | `makeId` | `CONVERT_NOW` | `DONE` | Already had `@ManyToOne CarApiMake make` relationship; scanner false positive from compatibility getter. |
| `pos-vehicle-fitment` | `VehicleVariableValue` | `variableId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne VehicleVariable variable` on 2026-03-10. |
| `pos-vehicle-reference-nhtsa` | `VehicleVariableValue` | `variableId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne VehicleVariable variable` on 2026-03-10. |
| `pos-vehicle-inventory` | `VehicleCarePreference` | `vehicleId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne VehicleRecord vehicle` on 2026-03-10. Non-standard PK: `vehicleId`. |
| `pos-inventory` | `CountEntry` | `recountOfCountEntryId` | `CONVERT_NOW` | `DONE` | Self-ref: converted to `@ManyToOne CountEntry recountOfCountEntry` (nullable) on 2026-03-10. Non-standard PK: `countEntryId`. |
| `pos-accounting` | `PaymentApplication` | `paymentId` | `CONVERT_NOW` | `DONE` | Converted to `@ManyToOne ReceivablePayment payment` on 2026-03-10. Immutable entity. |
| `pos-customer` | `CommunicationPreference` | `partyId` | `DEFER` | `DEFER` | AbstractParty uses TABLE_PER_CLASS inheritance; no shared AbstractPartyRepository; cross-table proxy resolution fragile. |
| `pos-customer` | `PartyNote` | `partyId` | `DEFER` | `DEFER` | Same as CommunicationPreference — AbstractParty TABLE_PER_CLASS inheritance complexity. |
| `pos-customer` | `ContactRoleAssignment` | `contactId` | `DEFER` | `DEFER` | Composite @IdClass (contactId+customerAccountId+roleName); FK-in-composite-key requires @MapsId strategy. |
| `pos-customer` | `PartyAlias` | `sourcePartyId` | `DEFER` | `DEFER` | @Id field in composite key; requires @MapsId strategy. |
| `pos-customer` | `PartyAlias` | `targetPartyId` | `DEFER` | `DEFER` | Same entity with composite @Id; requires @MapsId strategy. |
| `pos-accounting` | `PaymentApplication` | `invoiceId` | `KEEP_SCALAR` | `DONE` | Cross-service: invoice owned by pos-invoice. |
| `pos-accounting` | `PaymentApplication` | `customerId` | `KEEP_SCALAR` | `DONE` | Cross-service: customer owned by pos-customer. |
| `pos-accounting` | `ReceivablePayment` | `customerId` | `KEEP_SCALAR` | `DONE` | Cross-service: customer owned by pos-customer. |

## Next Candidates

All approved CONVERT_NOW candidates are complete. Remaining items are DEFER or KEEP_SCALAR.
