erDiagram
	approval_request {
		UUID id
		UUID override_id
		VARCHAR status
		UUID assigned_approver_id
		VARCHAR assignment_strategy
		TIMESTAMP created_at
		TIMESTAMP resolved_at
	}
	cost_tier {
		UUID id
		UUID supplier_item_cost_id
		INTEGER min_quantity
		INTEGER max_quantity
		DECIMAL unit_cost
	}
	dimensions {
		UUID id
		UUID product_id
		VARCHAR dimension_type
		VARCHAR description
		VARCHAR unit_of_measure
		DOUBLE dimension_value
	}
	guardrail_policy {
		UUID id
		VARCHAR scope
		UUID scope_id
		DECIMAL min_margin_percent
		DECIMAL max_discount_percent
		DECIMAL auto_approval_threshold_percent
		TIMESTAMP created_at
	}
	item_cost {
		UUID item_cost_id
		UUID item_id
		DECIMAL standard_cost
		DECIMAL last_cost
		DECIMAL average_cost
		DECIMAL qty_on_hand
		TIMESTAMP created_at
		TIMESTAMP updated_at
	}
	item_cost_audit {
		UUID audit_id
		UUID item_id
		TIMESTAMP audit_timestamp
		VARCHAR cost_type_changed
		DECIMAL old_value
		DECIMAL new_value
		VARCHAR change_source_type
		VARCHAR change_source_id
		VARCHAR actor
		VARCHAR reason_code
	}
	location_price_override {
		UUID id
		BIGINT version
		UUID location_id
		UUID product_id
		DECIMAL base_price
		DECIMAL cost
		DECIMAL override_price
		DECIMAL discount_percent
		DECIMAL margin_percent
		VARCHAR status
		UUID created_by_user_id
		TIMESTAMP created_at
		UUID approved_by_user_id
		TIMESTAMP approved_at
		UUID rejected_by
		TIMESTAMP rejected_at
		VARCHAR rejection_reason_code
		VARCHAR rejection_notes
		TIMESTAMP resolved_at
	}
	non_inventory_product {
		UUID id
		VARCHAR name
		VARCHAR long_description
		VARCHAR short_description
	}
	price_book {
		UUID price_book_id
		VARCHAR name
		VARCHAR scope
		UUID scope_id
		BOOLEAN is_default
		VARCHAR status
		TIMESTAMP created_at
		TIMESTAMP updated_at
		BIGINT version
	}
	price_book_rule {
		UUID rule_id
		UUID price_book_id
		VARCHAR target_type
		UUID target_id
		TEXT pricing_logic
		VARCHAR condition_type
		VARCHAR condition_value
		INTEGER priority
		TIMESTAMP effective_start_at
		TIMESTAMP effective_end_at
		VARCHAR status
		UUID created_by_user_id
		TIMESTAMP created_at
		TIMESTAMP updated_at
		BIGINT version
	}
	product {
		UUID id
		VARCHAR name
		VARCHAR description
		VARCHAR status
		VARCHAR unit_of_measure
		VARCHAR short_description
		VARCHAR long_description
		VARCHAR manufacturer_part_number
		UUID manufacturer_id
		VARCHAR manufacturer_name
		VARCHAR manufacturer_warranty
		VARCHAR manufacturer_brand
		VARCHAR country_of_origin
		VARCHAR sku
		VARCHAR product_code
		VARCHAR upc
		TEXT attributes
		VARCHAR product_code_type
		VARCHAR type
		VARCHAR material
		VARCHAR color
		VARCHAR warranty
		TEXT specifications
		VARCHAR lifecycle_state
		TIMESTAMP lifecycle_state_effective_at
		UUID last_state_changed_by
		TIMESTAMP last_state_changed_at
		VARCHAR lifecycle_override_reason
		TIMESTAMP created_at
		TIMESTAMP updated_at
	}
	product_msrp {
		UUID msrp_id
		UUID product_id
		DECIMAL amount
		VARCHAR currency
		DATE effective_start_date
		DATE effective_end_date
		TIMESTAMP created_at
		TIMESTAMP updated_at
		UUID updated_by
		BIGINT version
	}
	product_replacement {
		UUID replacement_id
		UUID original_product_id
		UUID replacement_product_id
		INTEGER priority_order
		VARCHAR notes
		TIMESTAMP effective_at
		TIMESTAMP created_at
		TIMESTAMP updated_at
		TIMESTAMP deleted_at
	}
	service {
		UUID id
		VARCHAR name
		VARCHAR long_description
		VARCHAR short_description
	}
	supplier_item_cost {
		UUID id
		UUID supplier_id
		UUID item_id
		VARCHAR currency_code
		DECIMAL base_cost
		TIMESTAMP created_at
		TIMESTAMP updated_at
	}
	uom_conversion {
		UUID id
		VARCHAR from_uom_code
		VARCHAR to_uom_code
		DECIMAL conversion_factor
		BOOLEAN is_active
		TIMESTAMP created_at
		TIMESTAMP updated_at
		VARCHAR created_by
	}
	supplier_item_cost ||--o{ cost_tier : "supplierItemCost"
	price_book ||--o{ price_book_rule : "priceBook"
	product ||--o{ dimensions : "dimensions"
	product ||--o{ product_msrp : "product"
	product ||--o{ product_replacement : "originalProduct"
	product ||--o{ product_replacement : "replacementProduct"
