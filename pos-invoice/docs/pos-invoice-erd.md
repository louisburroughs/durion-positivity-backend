erDiagram
	invoices {
		UUID id
		String invoice_number
		UUID workorder_id
		UUID estimate_id
		UUID approval_id
		String customer_id
		String status
		Decimal subtotal
		Decimal tax_amount
		Decimal total_amount
		Decimal adjustments_amount
		Timestamp created_at
		Timestamp updated_at
		Timestamp finalized_at
		String finalized_by
		Integer version
	}
	invoice_items {
		UUID id
		UUID invoice_id
		String description
		Decimal quantity
		Decimal unit_price
		Decimal line_total
		UUID workorder_item_id
	}
	invoice_adjustments {
		UUID id
		UUID invoice_id
		String type
		Decimal amount
		String reason
		String authorized_by
		Timestamp created_at
	}
	payment_intents {
		UUID id
		UUID invoice_id
		String idempotency_key
		String status
		String payment_flow
		String payment_token
		Decimal authorized_amount
		Decimal captured_amount
		Decimal voided_remainder_amount
		String gateway_provider
		String gateway_response
		String gateway_reference
		Timestamp created_at
		Timestamp updated_at
	}
	refund_records {
		UUID id
		UUID payment_intent_id
		UUID invoice_id
		Decimal amount
		String status
		String reason
		String notes
		String gateway_reference
		String requested_by
		Timestamp requested_at
		Timestamp completed_at
		Timestamp created_at
		Timestamp updated_at
	}
	receipts {
		UUID id
		UUID invoice_id
		UUID payment_intent_id
		String reference
		String status
		String template_id
		String template_version
		String cashier_id
		String terminal_id
		Integer reprint_count
		String last_reprint_reason
		String last_reprinted_by
		String delivery_method
		String delivery_status
		String delivery_email_address
		Timestamp created_at
		Timestamp updated_at
	}
	billing_rules {
		UUID id
		String party_id
		Boolean purchase_order_required
		String payment_terms_code
		String invoice_delivery_method
		String invoice_grouping_strategy
		Integer version
		Timestamp created_at
		Timestamp updated_at
		String updated_by
	}
	invoices ||--o{ invoice_items : invoice_id
	invoices ||--o{ invoice_adjustments : invoice_id
	invoices ||--o{ payment_intents : invoice_id
	invoices ||--o{ refund_records : invoice_id
	invoices ||--o{ receipts : invoice_id
	payment_intents ||--o{ refund_records : payment_intent_id
	payment_intents ||--o{ receipts : payment_intent_id
