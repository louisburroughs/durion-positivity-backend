erDiagram
	sales_order {
		UUID orderId
		String customerId
		String vehicleId
		String clerkId
		String terminalId
		String status
		BigDecimal subtotal
		Instant createdAt
		Instant updatedAt
		String createdBy
		String updatedBy
		String cancellationReason
		UUID workOrderId
		UUID paymentId
		String cancellationIdempotencyKey
	}
	sales_order_line {
		UUID orderLineId
		UUID order_id
		String itemSku
		String itemDescription
		int quantity
		BigDecimal unitPrice
		String fulfillmentStatus
		String priceSource
		String reasonCode
		String sourceType
		String sourceId
		String sourceLineId
		Instant createdAt
		Instant updatedAt
	}
	price_override {
		UUID overrideId
		UUID order_id
		UUID order_line_id
		UUID productId
		BigDecimal originalPrice
		BigDecimal overridePrice
		String reasonCode
		String justification
		String status
		UUID requestedByUserId
		UUID approvedByUserId
		UUID rejectedByUserId
		String rejectionReason
		Instant createdAt
		Instant updatedAt
		String createdBy
		String updatedBy
		Instant approvedAt
		Instant rejectedAt
		Instant appliedAt
		Boolean requiresApproval
		Boolean affectsCommission
		String idempotencyKey
	}
	approval_record {
		UUID recordId
		UUID price_override_id
		UUID reviewerUserId
		String reviewerRole
		String action
		String comments
		Instant actionTimestamp
		Instant createdAt
		Instant updatedAt
		String reviewerIpAddress
	}
	sales_order ||--o{ sales_order_line : order_id
	sales_order ||--o{ price_override : order_id
	sales_order_line ||--o{ price_override : order_line_id
	price_override ||--o{ approval_record : price_override_id
