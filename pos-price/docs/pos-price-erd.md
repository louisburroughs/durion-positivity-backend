erDiagram
	restriction_rule {
		UUID ruleId
		UUID productId
		VARCHAR locationTag
		VARCHAR serviceTag
		DATE effectiveFrom
		DATE effectiveTo
		BOOLEAN active
		BOOLEAN overrideable
		INTEGER policyVersion
		TIMESTAMP createdAt
		TIMESTAMP updatedAt
	}
	restriction_override_audit {
		UUID overrideId
		VARCHAR actor
		UUID ruleId
		UUID transactionId
		UUID productId
		VARCHAR overrideContext
		VARCHAR reasonCode
		VARCHAR notes
		VARCHAR approvedBy
		INTEGER policyVersion
		VARCHAR status
		TIMESTAMP issuedAt
		TIMESTAMP expiresAt
	}
	promotion_offer {
		UUID promotionOfferId
		VARCHAR promoCode
		VARCHAR name
		VARCHAR description
		VARCHAR discountType
		DECIMAL discountValue
		DATE startDate
		DATE endDate
		INTEGER usageLimit
		INT usageCount
		VARCHAR status
		VARCHAR storeCode
		TIMESTAMP createdAt
		TIMESTAMP updatedAt
		VARCHAR createdBy
	}
	promotion_eligibility_rule {
		UUID ruleId
		UUID promotion_id
		VARCHAR condition_type
		VARCHAR operator
		VARCHAR rule_value
		VARCHAR rule_combination
		VARCHAR createdBy
		VARCHAR updatedBy
		TIMESTAMP createdAt
		TIMESTAMP updatedAt
	}
	product_base_price {
		UUID productId
		DECIMAL msrp
		VARCHAR currency
		TIMESTAMP effectiveFrom
		TIMESTAMP effectiveTo
		TIMESTAMP createdAt
		TIMESTAMP updatedAt
	}
	pricing_snapshot {
		UUID snapshotId
		TIMESTAMP createdAt
		TEXT sourceContext
		VARCHAR itemIdentifier
		INTEGER quantity
		TEXT prices
		TEXT appliedRules
		VARCHAR policyVersion
		TIMESTAMP updatedAt
	}
	location_price_override {
		UUID id
		UUID productId
		UUID locationId
		DECIMAL overridePrice
		VARCHAR currency
		TIMESTAMP effectiveFrom
		TIMESTAMP effectiveTo
		TIMESTAMP createdAt
		TIMESTAMP updatedAt
	}
	customer_tier_pricing_rule {
		UUID id
		UUID productId
		UUID customerTierId
		DECIMAL discountRate
		TIMESTAMP effectiveFrom
		TIMESTAMP effectiveTo
		TIMESTAMP createdAt
		TIMESTAMP updatedAt
	}
	promotion_offer ||--o{ promotion_eligibility_rule : promotion_id
