erDiagram
	advance_shipping_notice {
		UUID asnId
		String asnReferenceNumber
		UUID vendorId
		String status
		UUID po_id
		LocalDate shipDate
		LocalDate expectedArrivalDate
		String createdBy
		String updatedBy
		Instant createdAt
		Instant updatedAt
	}
	inventory_allocation_audit {
		UUID allocationAuditId
		UUID stockItemId
		String reasonCode
		String triggeredBy
		String triggerReferenceId
		String previousState
		String newState
		Instant occurredAt
		Instant createdAt
	}
	inventory_allocation {
		UUID allocationId
		UUID reservation_id
		UUID locationId
		int allocatedQuantity
		String allocationState
		String status
		Instant hardenedAt
		String hardenedBy
		String hardenedReason
		Instant createdAt
		Instant updatedAt
	}
	approval_threshold_config {
		UUID configId
		String approvalTier
		Integer unitVarianceThreshold
		BigDecimal valueVarianceThreshold
		BigDecimal percentageVarianceThreshold
		Boolean active
		Instant createdAt
		Instant updatedAt
	}
	asn_line {
		UUID asnLineId
		UUID asn_id
		UUID po_id
		UUID po_line_id
		String sku
		BigDecimal quantityShipped
		BigDecimal quantityReceived
		String unitOfMeasure
		Long unitCostMinor
		String lotNumber
		Instant createdAt
	}
	count_entry {
		UUID countEntryId
		UUID cycle_count_task_id
		String auditorId
		Integer actualQuantity
		Integer expectedQuantity
		Integer variance
		Integer recountSequenceNumber
		UUID recount_of_count_entry_id
		Instant countedAt
		Instant createdAt
		Instant updatedAt
	}
	cycle_count_adjustment {
		UUID adjustmentId
		UUID stockItemId
		String reasonCode
		Integer quantityChange
		BigDecimal costAtTimeOfAdjustment
		Integer quantityOnHandBefore
		Integer countedQuantity
		String status
		String requiredApprovalTier
		String createdByUserId
		String approvedByUserId
		String rejectedByUserId
		String rejectionReason
		Instant createdAt
		Instant updatedAt
		Instant approvedAt
		Instant rejectedAt
		Instant postedAt
	}
	cycle_count_plan {
		UUID planId
		UUID locationId
		String planName
		LocalDate scheduledDate
		String status
		String createdBy
		Instant createdAt
		Instant updatedAt
	}
	cycle_count_task {
		UUID taskId
		String binLocation
		String itemSku
		String itemDescription
		Integer expectedQuantity
		String auditorId
		String status
		UUID latestCountEntryId
		Integer countEntriesCount
		Instant createdAt
		Instant updatedAt
	}
	distributor_feed_exception {
		UUID id
		String distributorId
		String distributorSku
		String reason
		String rawPayload
		Instant createdAt
		Instant updatedAt
	}
	distributor_normalized_inventory {
		UUID id
		UUID productId
		String distributorId
		String distributorSku
		Integer quantityAvailable
		Integer leadTimeDaysMin
		Integer leadTimeDaysMax
		String shipFromRegionCode
		String normalizationPolicyVersion
		String rawLeadTime
		String rawShipFromRegion
		Instant createdAt
		Instant updatedAt
	}
	goods_receipt {
		UUID receiptId
		String receiptNumber
		UUID po_id
		UUID asn_id
		UUID locationId
		Long totalAccruedAmountMinor
		String createdBy
		String updatedBy
		Instant createdAt
		Instant updatedAt
	}
	goods_receipt_line {
		UUID receiptLineId
		UUID receipt_id
		UUID po_line_id
		String sku
		BigDecimal quantityReceived
		Long unitCostMinor
		Long lineAccruedAmountMinor
		String lotNumber
		Instant createdAt
	}
	inventory_adjustment_request {
		UUID adjustmentRequestId
		String productSku
		UUID locationId
		Integer quantity
		String reasonCode
		String unitOfMeasure
		String status
		String requestedByUserId
		String approvedByUserId
		Instant requestedAt
		Instant approvedAt
		Instant createdAt
		Instant updatedAt
	}
	inventory_ledger_entry {
		UUID ledgerEntryId
		String stockItemId
		UUID adjustmentId
		String eventType
		Integer changeInQuantity
		Integer quantityAfter
		BigDecimal unitCost
		String transactionUserId
		Instant timestamp
		UUID locationId
		UUID fromLocationId
		UUID toLocationId
		String reasonCode
		String sourceTransactionId
		String unitOfMeasure
		String notes
		Instant createdAt
		Instant updatedAt
	}
	inventory_return {
		UUID returnId
		UUID workorderId
		String returnReason
		int totalItemsReturned
		Instant createdAt
		Instant updatedAt
	}
	inventory_return_line {
		UUID lineId
		UUID return_id
		UUID skuId
		int quantityReturned
		Instant createdAt
		Instant updatedAt
	}
	inventory_variance {
		UUID varianceId
		UUID session_id
		UUID line_id
		String productId
		String varianceType
		BigDecimal varianceQuantity
		BigDecimal expectedQuantity
		BigDecimal receivedQuantity
		String recordedByUserId
		Instant createdAt
	}
	normalized_availability {
		UUID id
		UUID productId
		UUID manufacturerId
		String manufacturerPartNumber
		Integer availableQty
		String uom
		BigDecimal unitPriceAmount
		String unitPriceCurrency
		Integer leadTimeDaysMin
		Integer leadTimeDaysMax
		Integer minOrderQty
		Integer packSize
		String sourceLocationCode
		Instant asOf
		Instant receivedAt
		Integer schemaVersion
		Instant createdAt
		Instant updatedAt
	}
	inventory_pick_list {
		UUID pickListId
		UUID workorderId
		String status
		int priority
		Instant dueAt
		Instant createdAt
		Instant updatedAt
	}
	inventory_pick_task {
		UUID pickTaskId
		UUID pick_list_id
		UUID productId
		String sku
		int quantityRequired
		int quantityPicked
		UUID suggestedLocationId
		int sortOrder
		int priority
		Instant dueAt
		String status
		UUID workorderLineId
		Instant createdAt
		Instant updatedAt
	}
	purchase_order {
		UUID purchaseOrderId
		UUID vendorId
		String poNumber
		String status
		Integer versionNumber
		String currency
		Long subtotalMinor
		Long taxMinor
		Long grandTotalMinor
		Long openBalanceMinor
		UUID shipToLocationId
		String paymentTermsId
		LocalDate poDate
		LocalDate expectedDeliveryDate
		String requestedBy
		String comment
		String approverId
		Instant approvalTimestamp
		String approvalNotes
		String encumbranceRef
		String createdBy
		String updatedBy
		Instant createdAt
		Instant updatedAt
	}
	purchase_order_line {
		UUID lineId
		UUID purchase_order_id
		Integer lineNumber
		UUID skuId
		String description
		BigDecimal quantityDecimal
		Long unitCostMinor
		Long lineTotalMinor
		Long taxMinor
		String taxCodeId
		String glAccountId
		BigDecimal openQuantityDecimal
		Instant createdAt
	}
	putaway_rule {
		UUID ruleId
		Integer priority
		String criteria
		UUID destinationLocationId
		boolean isEnabled
		Instant createdAt
		Instant updatedAt
	}
	putaway_task {
		UUID taskId
		UUID source_receipt_id
		UUID productId
		Integer quantity
		UUID sourceLocationId
		UUID suggestedDestinationLocationId
		UUID originalSuggestedLocationId
		UUID finalSuggestedLocationId
		UUID actualDestinationLocationId
		String fallbackReason
		String status
		String assigneeId
		Instant createdAt
		Instant updatedAt
	}
	receiving_line {
		UUID lineId
		UUID session_id
		String productId
		BigDecimal expectedQuantity
		BigDecimal receivedQuantity
		String status
		String workorderId
		String workorderLineId
		Instant createdAt
		Instant updatedAt
	}
	receiving_session {
		UUID sessionId
		String sourceDocumentId
		String sourceDocumentType
		String supplierId
		String shipmentReference
		String status
		String entryMethod
		String createdByUserId
		Instant createdAt
		Instant updatedAt
	}
	replenishment_policy {
		UUID policyId
		UUID locationId
		String itemSKU
		Integer minimumQuantity
		Integer maximumQuantity
		Instant createdAt
		Instant updatedAt
	}
	replenishment_task {
		UUID taskId
		String itemSKU
		Integer quantity
		UUID sourceLocationId
		UUID destinationLocationId
		String status
		String triggerType
		String decisionReason
		String sourcingReason
		String assignedTo
		Instant createdAt
		Instant updatedAt
	}
	inventory_reservation {
		UUID reservationId
		UUID workorderLineId
		UUID stockItemId
		int requiredQuantity
		int allocatedQuantity
		int priority
		Instant waitingSince
		Instant dueDateTime
		Instant scheduleStartTime
		String status
		Instant createdAt
		Instant updatedAt
	}
	unmapped_manufacturer_part {
		UUID id
		UUID manufacturerId
		String manufacturerPartNumber
		Instant firstSeen
		Instant lastSeen
		Integer occurrenceCount
		String status
		Instant createdAt
		Instant updatedAt
	}
	purchase_order ||--o{ advance_shipping_notice : po_id
	advance_shipping_notice ||--o{ asn_line : asn_id
	purchase_order ||--o{ asn_line : po_id
	purchase_order_line |o--o{ asn_line : po_line_id
	cycle_count_task ||--o{ count_entry : cycle_count_task_id
	count_entry |o--o{ count_entry : recount_of_count_entry_id
	inventory_reservation ||--o{ inventory_allocation : reservation_id
	purchase_order ||--o{ goods_receipt : po_id
	advance_shipping_notice |o--o{ goods_receipt : asn_id
	goods_receipt ||--o{ goods_receipt_line : receipt_id
	purchase_order_line |o--o{ goods_receipt_line : po_line_id
	inventory_return ||--o{ inventory_return_line : return_id
	receiving_session ||--o{ inventory_variance : session_id
	receiving_line ||--o{ inventory_variance : line_id
	inventory_pick_list ||--o{ inventory_pick_task : pick_list_id
	purchase_order ||--o{ purchase_order_line : purchase_order_id
	goods_receipt ||--o{ putaway_task : source_receipt_id
	receiving_session ||--o{ receiving_line : session_id
