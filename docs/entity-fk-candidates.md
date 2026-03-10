# Entity Foreign Key Candidate Inventory

Generated: 2026-03-09T19:56:51Z

Scope: all Java classes annotated with @Entity under */src/main/java.

Method:

- Explicit FK keys: @JoinColumn and @JoinTable definitions.
- Candidate FK keys: scalar fields typed UUID/String with names ending in Id.
- Note: service-layer lookup usage is not derivable statically from entity definitions; scalar IDs are listed as candidates for manual verification.

Total entities scanned: 231

## Core Entries

## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/APPaymentAllocation.java

Scalar UUID/String Id candidates:

- allocationId
- paymentId
- vendorBillId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/APPayment.java

Scalar UUID/String Id candidates:

- paymentId
- vendorBillId
- vendorId
- bankAccountId
- gatewayTransactionId
- glJournalEntryId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/CreditMemo.java

Scalar UUID/String Id candidates:

- creditMemoId
- originalInvoiceId
- customerId
- createdByUserId
- originalPeriodId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/CustomerCredit.java

Scalar UUID/String Id candidates:

- creditId
- customerId
- sourcePaymentId
- traceId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/DefaultGLMapping.java

Scalar UUID/String Id candidates:

- mappingId
- organizationId
- debitAccountId
- creditAccountId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/GLAccount.java

Scalar UUID/String Id candidates:

- glAccountId
- parentAccountId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/GLMapping.java

Scalar UUID/String Id candidates:

- glMappingId
- postingCategoryId
- mappingKeyId
- glAccountId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/IdempotencyKey.java

Scalar UUID/String Id candidates:

- invoiceId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/InvoiceStatusView.java

Scalar UUID/String Id candidates:

- invoiceId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/JournalEntry.java

Scalar UUID/String Id candidates:

- journalEntryId
- sourceEventId
- postingRuleSetId
- postingRuleVersionId
- reversalJournalEntryId
- reversedByJournalEntryId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/JournalEntryLine.java

Scalar UUID/String Id candidates:

- lineId
- journalEntryId
- glAccountId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/MappingKey.java

Scalar UUID/String Id candidates:

- mappingKeyId
- postingCategoryId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/PaymentApplication.java

Scalar UUID/String Id candidates:

- paymentApplicationId
- paymentId
- invoiceId
- customerId
- applicationRequestId
- traceId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/PaymentApplicationReversal.java

Scalar UUID/String Id candidates:

- reversalId
- originalPaymentApplicationId
- traceId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/PostingCategory.java

Scalar UUID/String Id candidates:

- postingCategoryId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/PostingRuleSet.java

Scalar UUID/String Id candidates:

- postingRuleSetId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/PostingRuleVersion.java

Explicit relationship keys:

- posting_rule_set_id

Scalar UUID/String Id candidates:

- versionId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/ReceivablePayment.java

Scalar UUID/String Id candidates:

- paymentId
- customerId
- sourceEventId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/Reconciliation.java

Scalar UUID/String Id candidates:

- reconciliationId
- glAccountId
- lineId
- glTransactionId
- journalEntryId
- adjustmentId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/ReconciliationRecord.java

Scalar UUID/String Id candidates:

- reconciliationRecordId
- invoiceId
- transactionId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/ReprocessingAttemptHistory.java

Explicit relationship keys:

- event_id

Scalar UUID/String Id candidates:

- attemptId
- triggeredByUserId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/StatementLineMapping.java

Scalar UUID/String Id candidates:

- mappingId
- glAccountId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/VendorBill.java

Scalar UUID/String Id candidates:

- vendorBillId
- vendorId
- purchaseOrderId
- originEventId
- journalEntryId
- paymentTransactionId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/VendorBillLine.java

Scalar UUID/String Id candidates:

- lineId
- vendorBillId
- productId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/VendorBillMatchCandidate.java

Scalar UUID/String Id candidates:

- candidateId
- invoiceEventId
- vendorBillId
- vendorId


## pos-catalog/src/main/java/com/positivity/catalog/internal/entity/ApprovalRequestEntity.java

Scalar UUID/String Id candidates:

- overrideId
- assignedApproverId


## pos-catalog/src/main/java/com/positivity/catalog/internal/entity/CostTierEntity.java

Explicit relationship keys:

- supplier_item_cost_id

Scalar UUID/String Id candidates:

- supplierItemCostId


## pos-catalog/src/main/java/com/positivity/catalog/internal/entity/GuardrailPolicyEntity.java

Scalar UUID/String Id candidates:

- scopeId


## pos-catalog/src/main/java/com/positivity/catalog/internal/entity/ItemCostEntity.java

Scalar UUID/String Id candidates:

- itemCostId
- itemId


## pos-catalog/src/main/java/com/positivity/catalog/internal/entity/LocationPriceOverrideEntity.java

Scalar UUID/String Id candidates:

- locationId
- productId
- createdByUserId
- approvedByUserId


## pos-catalog/src/main/java/com/positivity/catalog/internal/entity/PriceBookEntity.java

Scalar UUID/String Id candidates:

- priceBookId
- scopeId


## pos-catalog/src/main/java/com/positivity/catalog/internal/entity/PriceBookRuleEntity.java

Explicit relationship keys:

- price_book_id

Scalar UUID/String Id candidates:

- ruleId
- priceBookId
- targetId
- createdByUserId


## pos-catalog/src/main/java/com/positivity/catalog/internal/entity/ProductEntity.java

Explicit relationship keys:

- product_id
- category_id
- subcategory_id

Scalar UUID/String Id candidates:

- manufacturerId


## pos-catalog/src/main/java/com/positivity/catalog/internal/entity/ProductMsrpEntity.java

Scalar UUID/String Id candidates:

- msrpId
- productId


## pos-catalog/src/main/java/com/positivity/catalog/internal/entity/ProductReplacementEntity.java

Scalar UUID/String Id candidates:

- replacementId
- originalProductId
- replacementProductId


## pos-catalog/src/main/java/com/positivity/catalog/internal/entity/SupplierItemCostEntity.java

Scalar UUID/String Id candidates:

- supplierId
- itemId


## pos-customer/src/main/java/com/positivity/customer/internal/entity/AbstractParty.java

Explicit relationship keys:

- customer_id

Scalar UUID/String Id candidates:

- partyId


## pos-customer/src/main/java/com/positivity/customer/internal/entity/CommercialParty.java

Explicit relationship keys:

- parent_party_id
- party_id

Scalar UUID/String Id candidates:

- taxId
- billingTermsId


## pos-customer/src/main/java/com/positivity/customer/internal/entity/CommunicationPreference.java

Explicit relationship keys:

- preference_id

Scalar UUID/String Id candidates:

- preferenceId
- partyId


## pos-customer/src/main/java/com/positivity/customer/internal/entity/Contact.java

Explicit relationship keys:

- party_id

Scalar UUID/String Id candidates:

- contactId
- personId


## pos-customer/src/main/java/com/positivity/customer/internal/entity/ContactPoint.java

Explicit relationship keys:

- person_id

Scalar UUID/String Id candidates:

- contactPointId


## pos-customer/src/main/java/com/positivity/customer/internal/entity/ContactRoleAssignment.java

Scalar UUID/String Id candidates:

- contactId
- customerAccountId


## pos-customer/src/main/java/com/positivity/customer/internal/entity/PartyAlias.java

Scalar UUID/String Id candidates:

- sourcePartyId
- targetPartyId


## pos-customer/src/main/java/com/positivity/customer/internal/entity/PartyNote.java

Scalar UUID/String Id candidates:

- noteId
- partyId
- sourceWorkorderId
- sourceEventId


## pos-customer/src/main/java/com/positivity/customer/internal/entity/PartyRelationship.java

Explicit relationship keys:

- from_party_id
- to_person_id
- party_relationship_id

Scalar UUID/String Id candidates:

- partyRelationshipId


## pos-customer/src/main/java/com/positivity/customer/internal/entity/PersonParty.java

Scalar UUID/String Id candidates:

- personId


## pos-customer/src/main/java/com/positivity/customer/internal/entity/ProcessingLog.java

Scalar UUID/String Id candidates:

- eventId
- correlationId


## pos-customer/src/main/java/com/positivity/customer/internal/entity/PromotionCounter.java

Scalar UUID/String Id candidates:

- counterId
- promotionId


## pos-customer/src/main/java/com/positivity/customer/internal/entity/PromotionRedemption.java

Scalar UUID/String Id candidates:

- promotionRedemptionId
- promotionId
- customerId
- workorderId
- invoiceId


## pos-customer/src/main/java/com/positivity/customer/internal/entity/VehicleProjection.java

Scalar UUID/String Id candidates:

- vehicleId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/AdvanceShippingNoticeEntity.java

Scalar UUID/String Id candidates:

- asnId
- vendorId
- poId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/AllocationEntity.java

Explicit relationship keys:

- reservation_id

Scalar UUID/String Id candidates:

- allocationId
- locationId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/ApprovalThresholdConfig.java

Scalar UUID/String Id candidates:

- configId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/AsnLineEntity.java

Explicit relationship keys:

- asn_id

Scalar UUID/String Id candidates:

- asnLineId
- poId
- poLineId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/CountEntry.java

Scalar UUID/String Id candidates:

- countEntryId
- cycleCountTaskId
- auditorId
- recountOfCountEntryId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/CycleCountAdjustment.java

Scalar UUID/String Id candidates:

- adjustmentId
- stockItemId
- createdByUserId
- approvedByUserId
- rejectedByUserId
- ledgerEntryId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/CycleCountPlan.java

Explicit relationship keys:

- plan_id

Scalar UUID/String Id candidates:

- planId
- locationId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/CycleCountTask.java

Scalar UUID/String Id candidates:

- taskId
- auditorId
- latestCountEntryId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/DistributorFeedException.java

Scalar UUID/String Id candidates:

- distributorId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/DistributorNormalizedInventory.java

Scalar UUID/String Id candidates:

- productId
- distributorId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/GoodsReceiptEntity.java

Scalar UUID/String Id candidates:

- receiptId
- poId
- asnId
- locationId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/GoodsReceiptLineEntity.java

Explicit relationship keys:

- receipt_id

Scalar UUID/String Id candidates:

- receiptLineId
- poLineId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/InventoryAdjustmentRequest.java

Scalar UUID/String Id candidates:

- adjustmentRequestId
- locationId
- requestedByUserId
- approvedByUserId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/InventoryLedgerEntry.java

Scalar UUID/String Id candidates:

- ledgerEntryId
- stockItemId
- adjustmentId
- transactionUserId
- locationId
- fromLocationId
- toLocationId
- sourceTransactionId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/InventoryReturnEntity.java

Scalar UUID/String Id candidates:

- returnId
- workorderId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/InventoryReturnLineEntity.java

Explicit relationship keys:

- return_id

Scalar UUID/String Id candidates:

- lineId
- skuId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/InventoryVariance.java

Scalar UUID/String Id candidates:

- varianceId
- sessionId
- lineId
- productId
- recordedByUserId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/NormalizedAvailability.java

Scalar UUID/String Id candidates:

- productId
- manufacturerId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/PickListEntity.java

Scalar UUID/String Id candidates:

- pickListId
- workorderId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/PickTaskEntity.java

Explicit relationship keys:

- pick_list_id

Scalar UUID/String Id candidates:

- pickTaskId
- productId
- suggestedLocationId
- workorderLineId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/PurchaseOrderEntity.java

Scalar UUID/String Id candidates:

- purchaseOrderId
- vendorId
- shipToLocationId
- paymentTermsId
- approverId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/PurchaseOrderLineEntity.java

Explicit relationship keys:

- purchase_order_id

Scalar UUID/String Id candidates:

- lineId
- skuId
- taxCodeId
- glAccountId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/PutawayRule.java

Scalar UUID/String Id candidates:

- ruleId
- destinationLocationId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/PutawayTask.java

Scalar UUID/String Id candidates:

- taskId
- sourceReceiptId
- productId
- sourceLocationId
- suggestedDestinationLocationId
- originalSuggestedLocationId
- finalSuggestedLocationId
- actualDestinationLocationId
- assigneeId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/ReceivingLine.java

Explicit relationship keys:

- session_id

Scalar UUID/String Id candidates:

- lineId
- productId
- workorderId
- workorderLineId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/ReceivingSession.java

Scalar UUID/String Id candidates:

- sessionId
- sourceDocumentId
- supplierId
- createdByUserId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/ReplenishmentPolicy.java

Scalar UUID/String Id candidates:

- policyId
- locationId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/ReplenishmentTask.java

Scalar UUID/String Id candidates:

- taskId
- sourceLocationId
- destinationLocationId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/ReservationEntity.java

Scalar UUID/String Id candidates:

- reservationId
- workorderLineId
- stockItemId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/UnmappedManufacturerPart.java

Scalar UUID/String Id candidates:

- manufacturerId


## pos-invoice/src/main/java/com/positivity/invoice/internal/entity/BillingRules.java

Scalar UUID/String Id candidates:

- partyId


## pos-invoice/src/main/java/com/positivity/invoice/internal/entity/InvoiceAdjustment.java

Explicit relationship keys:

- invoice_id


## pos-invoice/src/main/java/com/positivity/invoice/internal/entity/InvoiceItem.java

Explicit relationship keys:

- invoice_id

Scalar UUID/String Id candidates:

- invoiceId
- workorderItemId


## pos-invoice/src/main/java/com/positivity/invoice/internal/entity/Invoice.java

Scalar UUID/String Id candidates:

- workorderId
- estimateId
- approvalId
- partyId
- glEntryId


## pos-invoice/src/main/java/com/positivity/invoice/internal/entity/PaymentIntent.java

Scalar UUID/String Id candidates:

- invoiceId


## pos-invoice/src/main/java/com/positivity/invoice/internal/entity/Receipt.java

Scalar UUID/String Id candidates:

- invoiceId
- paymentIntentId
- templateId
- cashierId
- terminalId


## pos-invoice/src/main/java/com/positivity/invoice/internal/entity/RefundRecord.java

Scalar UUID/String Id candidates:

- paymentIntentId
- invoiceId


## pos-location/src/main/java/com/positivity/location/internal/entity/BayEntity.java

Scalar UUID/String Id candidates:

- locationId


## pos-location/src/main/java/com/positivity/location/internal/entity/Location.java

Explicit relationship keys:

- location_type_id

Scalar UUID/String Id candidates:

- hrLocationId
- defaultStagingLocationId
- defaultQuarantineLocationId
- geographicalLocationId


## pos-location/src/main/java/com/positivity/location/internal/entity/LocationParent.java

Explicit relationship keys:

- parent_id
- child_id


## pos-location/src/main/java/com/positivity/location/internal/entity/MobileUnitCoverageRuleEntity.java

Explicit relationship keys:

- mobile_unit_id
- service_area_id


## pos-location/src/main/java/com/positivity/location/internal/entity/MobileUnitEntity.java

Explicit relationship keys:

- mobile_unit_id

Scalar UUID/String Id candidates:

- baseLocationId
- travelBufferPolicyId


## pos-location/src/main/java/com/positivity/location/internal/entity/ServiceAreaEntity.java

Explicit relationship keys:

- service_area_id


## pos-location/src/main/java/com/positivity/location/internal/entity/StorageLocationEntity.java

Scalar UUID/String Id candidates:

- siteId
- parentStorageLocationId


## pos-mcp-server/src/main/java/com/positivity/mcp/internal/entity/LlmApiConfig.java

Explicit relationship keys:

- llm_api_config_id

Scalar UUID/String Id candidates:

- apiId


## pos-order/src/main/java/com/positivity/order/internal/entity/ApprovalRecord.java

Scalar UUID/String Id candidates:

- recordId
- priceOverrideId
- reviewerUserId


## pos-order/src/main/java/com/positivity/order/internal/entity/PriceOverride.java

Scalar UUID/String Id candidates:

- overrideId
- orderId
- orderLineId
- productId
- requestedByUserId
- approvedByUserId
- rejectedByUserId


## pos-order/src/main/java/com/positivity/order/internal/entity/SalesOrder.java

Scalar UUID/String Id candidates:

- orderId
- customerId
- vehicleId
- clerkId
- terminalId
- workOrderId
- paymentId


## pos-order/src/main/java/com/positivity/order/internal/entity/SalesOrderLine.java

Explicit relationship keys:

- order_id

Scalar UUID/String Id candidates:

- orderLineId
- sourceId
- sourceLineId


## pos-people/src/main/java/com/positivity/people/internal/entity/EmployeeOffboardingRetry.java

Scalar UUID/String Id candidates:

- employeeId
- actorId


## pos-people/src/main/java/com/positivity/people/internal/entity/PersonLocationAssignment.java

Scalar UUID/String Id candidates:

- personId
- locationId


## pos-people/src/main/java/com/positivity/people/internal/entity/TimeEntryAdjustment.java

Scalar UUID/String Id candidates:

- adjustmentId
- timeEntryId


## pos-people/src/main/java/com/positivity/people/internal/entity/TimeEntryException.java

Scalar UUID/String Id candidates:

- exceptionId
- employeeId
- timeEntryId


## pos-people/src/main/java/com/positivity/people/internal/entity/TimeEntry.java

Explicit relationship keys:

- person_id

Scalar UUID/String Id candidates:

- timeEntryId
- timesheetId
- locationId


## pos-people/src/main/java/com/positivity/people/internal/entity/TimekeepingEntry.java

Scalar UUID/String Id candidates:

- timekeepingEntryId
- tenantId
- sourceSessionId
- originalSourceSessionId
- correctionId
- employeeId
- associatedWorkOrderId


## pos-people/src/main/java/com/positivity/people/internal/entity/TimekeepingPolicy.java

Scalar UUID/String Id candidates:

- timekeepingPolicyId
- scopeId


## pos-people/src/main/java/com/positivity/people/internal/entity/UserPersonLink.java

Scalar UUID/String Id candidates:

- userId
- personId


## pos-people/src/main/java/com/positivity/people/internal/entity/WorkSessionBreak.java

Scalar UUID/String Id candidates:

- breakId
- sessionId


## pos-people/src/main/java/com/positivity/people/internal/entity/WorkSession.java

Scalar UUID/String Id candidates:

- sessionId
- personId


## pos-price/src/main/java/com/positivity/price/internal/entity/CustomerTierPricingRule.java

Scalar UUID/String Id candidates:

- productId
- customerTierId


## pos-price/src/main/java/com/positivity/price/internal/entity/LocationPriceOverride.java

Scalar UUID/String Id candidates:

- productId
- locationId


## pos-price/src/main/java/com/positivity/price/internal/entity/PricingSnapshot.java

Scalar UUID/String Id candidates:

- snapshotId


## pos-price/src/main/java/com/positivity/price/internal/entity/ProductBasePrice.java

Scalar UUID/String Id candidates:

- productId


## pos-price/src/main/java/com/positivity/price/internal/entity/PromotionEligibilityRule.java

Scalar UUID/String Id candidates:

- ruleId
- promotionId


## pos-price/src/main/java/com/positivity/price/internal/entity/PromotionOffer.java

Scalar UUID/String Id candidates:

- promotionOfferId


## pos-price/src/main/java/com/positivity/price/internal/entity/RestrictionRule.java

Scalar UUID/String Id candidates:

- ruleId
- productId


## pos-security-service/src/main/java/com/positivity/securityservice/internal/entity/PricingRuleTraceEntry.java

Explicit relationship keys:

- snapshot_id

Scalar UUID/String Id candidates:

- ruleId


## pos-security-service/src/main/java/com/positivity/securityservice/internal/entity/PricingSnapshot.java

Scalar UUID/String Id candidates:

- snapshotId


## pos-security-service/src/main/java/com/positivity/securityservice/internal/entity/PrincipalRole.java

Explicit relationship keys:

- role_id

Scalar UUID/String Id candidates:

- principalId


## pos-security-service/src/main/java/com/positivity/securityservice/internal/entity/RoleAssignment.java

Explicit relationship keys:

- user_id
- role_id
- role_assignment_id


## pos-security-service/src/main/java/com/positivity/securityservice/internal/entity/Role.java

Explicit relationship keys:

- permission_id


## pos-security-service/src/main/java/com/positivity/securityservice/internal/entity/User.java

Explicit relationship keys:

- role_id


## pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/Appointment.java

Explicit relationship keys:

- appointment_id

Scalar UUID/String Id candidates:

- appointmentId
- locationId
- resourceId
- crmCustomerId
- crmVehicleId
- sourceId


## pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/AppointmentServiceRequest.java

Scalar UUID/String Id candidates:

- serviceRequestId
- appointmentId
- serviceEntityId


## pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/Assignment.java

Scalar UUID/String Id candidates:

- assignmentId
- appointmentId
- resourceId


## pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/AssignmentMechanic.java

Scalar UUID/String Id candidates:

- assignmentId
- mechanicId


## pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/Certification.java

Explicit relationship keys:

- technician_id


## pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/HrIntegrationLog.java

Scalar UUID/String Id candidates:

- eventId
- personId


## pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/Mechanic.java

Scalar UUID/String Id candidates:

- mechanicId
- personId


## pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/MechanicSkill.java

Scalar UUID/String Id candidates:

- mechanicId


## pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/OverrideRecord.java

Scalar UUID/String Id candidates:

- overrideId
- appointmentId
- overriddenByUserId


## pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/RescheduleHistory.java

Scalar UUID/String Id candidates:

- rescheduleId
- appointmentId


## pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/ShopQualification.java

Explicit relationship keys:

- shop_id


## pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/ShopService.java

Explicit relationship keys:

- shop_id


## pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/Technician.java

Explicit relationship keys:

- shop_id


## pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/TravelBlock.java

Scalar UUID/String Id candidates:

- personId


## pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/WorkOrderAppointmentMapping.java

Scalar UUID/String Id candidates:

- workOrderId
- appointmentId


## pos-vehicle-fitment/src/main/java/com/positivity/vehiclefitment/internal/entity/FitmentTag.java

Explicit relationship keys:

- hint_id


## pos-vehicle-fitment/src/main/java/com/positivity/vehiclefitment/internal/entity/PartFitmentEntity.java

Explicit relationship keys:

- vehicle_manufacturer_id
- vehicle_make_id
- vehicle_model_id
- vehicle_type_id


## pos-vehicle-fitment/src/main/java/com/positivity/vehiclefitment/internal/entity/VehicleApplicabilityHint.java

Scalar UUID/String Id candidates:

- hintId
- productId


## pos-vehicle-fitment/src/main/java/com/positivity/vehiclefitment/internal/entity/VehicleType.java

Explicit relationship keys:

- make_id

Scalar UUID/String Id candidates:

- vehicleTypeId


## pos-vehicle-fitment/src/main/java/com/positivity/vehiclefitment/internal/entity/VehicleVariableValue.java

Scalar UUID/String Id candidates:

- variableId
- valueId


## pos-vehicle-inventory/src/main/java/com/positivity/vehicle/internal/entity/VehicleCarePreference.java

Scalar UUID/String Id candidates:

- vehicleId
- createdByUserId
- updatedByUserId


## pos-vehicle-inventory/src/main/java/com/positivity/vehicle/internal/entity/VehicleRecord.java

Scalar UUID/String Id candidates:

- vehicleId
- accountId


## pos-vehicle-reference-carapi/src/main/java/com/positivity/vehiclereferencecarapi/internal/entity/CarApiMake.java

Scalar UUID/String Id candidates:

- makeId


## pos-vehicle-reference-carapi/src/main/java/com/positivity/vehiclereferencecarapi/internal/entity/CarApiModel.java

Scalar UUID/String Id candidates:

- modelId
- makeId


## pos-vehicle-reference-nhtsa/src/main/java/com/positivity/nhtsa/internal/entity/VehicleType.java

Explicit relationship keys:

- make_id

Scalar UUID/String Id candidates:

- vehicleTypeId


## pos-vehicle-reference-nhtsa/src/main/java/com/positivity/nhtsa/internal/entity/VehicleVariableValue.java

Scalar UUID/String Id candidates:

- variableId
- valueId


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/ApprovalConfiguration.java

Scalar UUID/String Id candidates:

- locationId
- customerId


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/ApprovalRecord.java

Scalar UUID/String Id candidates:

- changeRequestId
- workorderId


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/BreakSegment.java

Explicit relationship keys:

- work_session_id

Scalar UUID/String Id candidates:

- breakSegmentId
- workSessionId


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/ChangeRequest.java

Scalar UUID/String Id candidates:

- workorderId
- requestedByUserId


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/EstimateItem.java

Scalar UUID/String Id candidates:

- estimateId
- productId
- serviceId
- createdById
- approvalProofId


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/Estimate.java

Scalar UUID/String Id candidates:

- locationId
- vehicleId
- customerId
- currencyUomId
- taxRegionId
- createdByUserId
- createdById
- approvalConfigurationId
- appointmentId
- crmPartyId
- crmVehicleId


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/EstimateSnapshot.java

Scalar UUID/String Id candidates:

- estimateId
- capturedById


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/IdempotencyKey.java

Scalar UUID/String Id candidates:

- workorderId
- changeRequestId
- laborEntryId
- partUsageEventId
- partAdjustmentEventId
- invoiceId


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/SubstituteLink.java

Scalar UUID/String Id candidates:

- productId
- substitutePartId


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/TechnicianAssignment.java

Scalar UUID/String Id candidates:

- workorderId
- technicianId


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/TimeEntryAdjustment.java

Scalar UUID/String Id candidates:

- adjustmentId
- timeEntryId


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/TimeEntry.java

Scalar UUID/String Id candidates:

- timeEntryId
- personId
- workOrderId
- decisionByUserId


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/TravelSegmentAdjustment.java

Scalar UUID/String Id candidates:

- adjustmentId
- travelSegmentId
- adjustedByUserId
- approvedByUserId


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/TravelSegment.java

Scalar UUID/String Id candidates:

- travelSegmentId
- mobileWorkAssignmentId
- technicianId
- fromLocationId
- toLocationId
- workOrderId
- actedByUserId
- actedForPersonId


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/Workorder.java

Scalar UUID/String Id candidates:

- shopId
- vehicleId
- customerId
- approvalId
- estimateId
- invoiceId
- locationId
- resourceId
- crmPartyId
- crmVehicleId


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/WorkorderLaborEntry.java

Explicit relationship keys:

- workorder_id

Scalar UUID/String Id candidates:

- workorderId
- workorderServiceId
- technicianId


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/WorkorderPart.java

Explicit relationship keys:

- work_order_service_id
- work_order_id

Scalar UUID/String Id candidates:

- productEntityId
- nonInventoryProductEntityId
- originEstimateItemId
- originalProductId
- changeRequestId


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/WorkOrderPartSubstitution.java

Scalar UUID/String Id candidates:

- substitutionId
- workorderId
- workorderLineItemId
- originalProductId
- substituteProductId


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/WorkorderService.java

Explicit relationship keys:

- work_order_id

Scalar UUID/String Id candidates:

- serviceEntityId
- technicianId
- originEstimateItemId
- changeRequestId


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/WorkorderSnapshot.java

Scalar UUID/String Id candidates:

- workorderId


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/WorkorderStateTransition.java

Scalar UUID/String Id candidates:

- workorderId


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/WorkSession.java

Scalar UUID/String Id candidates:

- workSessionId
- mechanicId
- workOrderId
- workOrderTaskId
- locationId
- resourceId
- approvedByUserId
- overriddenByUserId

## Audit and Event Entries

## pos-accounting/src/main/java/com/positivity/accounting/internal/audit/entity/AuditTrailEntry.java --> Leave off audit for now

Scalar UUID/String Id candidates:

- auditId
- actorId
- orderId
- lineItemId
- invoiceId
- paymentId
- sourceEventId
- sourceDocumentId


## pos-accounting/src/main/java/com/positivity/accounting/internal/audit/entity/OverridePolicyThreshold.java

Scalar UUID/String Id candidates:

- policyId


## pos-accounting/src/main/java/com/positivity/accounting/internal/audit/entity/RefundPolicyConfig.java

Scalar UUID/String Id candidates:

- configId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/AccountingAuditLog.java

Scalar UUID/String Id candidates:

- auditLogId
- entityId
- userId
- traceId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/AccountingEvent.java

Scalar UUID/String Id candidates:

- eventId
- organizationId
- journalEntryId
- finalPostingReferenceId
- resolvedByUserId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/AccountingStatusSyncAudit.java

Scalar UUID/String Id candidates:

- invoiceId
- eventId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/EventOutbox.java

Scalar UUID/String Id candidates:

- outboxId
- eventId
- aggregateId


## pos-accounting/src/main/java/com/positivity/accounting/internal/entity/PaymentAppliedEvent.java

Scalar UUID/String Id candidates:

- invoiceId


## pos-catalog/src/main/java/com/positivity/catalog/internal/entity/ItemCostAuditEntity.java

Scalar UUID/String Id candidates:

- auditId
- itemId
- changeSourceId


## pos-customer/src/main/java/com/positivity/customer/internal/entity/MergeAudit.java

Scalar UUID/String Id candidates:

- mergeAuditId
- survivorPartyId
- sourcePartyId
- mergedByUserId


## pos-event-receiver/src/main/java/com/positivity/poseventreceiver/internal/entity/EmittedEvent.java

Scalar UUID/String Id candidates:

- eventId


## pos-inventory/src/main/java/com/positivity/inventory/internal/entity/AllocationAuditEntity.java

Scalar UUID/String Id candidates:

- allocationAuditId
- stockItemId
- triggerReferenceId


## pos-people/src/main/java/com/positivity/people/internal/entity/TimeEntryAudit.java

Scalar UUID/String Id candidates:

- auditId
- timeEntryId
- actorId
- correlationId


## pos-price/src/main/java/com/positivity/price/internal/entity/RestrictionOverrideAudit.java

Scalar UUID/String Id candidates:

- overrideId
- ruleId
- transactionId
- productId


## pos-security-service/src/main/java/com/positivity/securityservice/internal/entity/AuditLogEvent.java

Scalar UUID/String Id candidates:

- eventId
- actorId
- entityId


## pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/AppointmentAudit.java

Scalar UUID/String Id candidates:

- auditId
- appointmentId
- actorId


## pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/MechanicAuditLog.java

Scalar UUID/String Id candidates:

- eventId
- personId


## pos-vehicle-inventory/src/main/java/com/positivity/vehicle/internal/entity/EventProcessingLog.java

Scalar UUID/String Id candidates:

- logId
- eventId
- workorderId
- vehicleId


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/AuditEvent.java

Scalar UUID/String Id candidates:

- entityId
- userId


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/SubstituteAudit.java

Scalar UUID/String Id candidates:

- auditId
- linkId
- actorId
- correlationId


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/WorkorderPartAdjustmentEvent.java

Explicit relationship keys:

- original_part_id

Scalar UUID/String Id candidates:

- workorderId
- substitutedWithPartId


## pos-workorder/src/main/java/com/positivity/workorder/internal/entity/WorkorderPartUsageEvent.java

Explicit relationship keys:

- workorder_part_id

Scalar UUID/String Id candidates:

- workorderId


