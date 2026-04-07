-- Phase 5.3 precision parity tightening for customer.

ALTER TABLE IF EXISTS communication_preference
    ALTER COLUMN party_id SET NOT NULL,
    ALTER COLUMN sms_preference SET DEFAULT FALSE,
    ALTER COLUMN sms_preference SET NOT NULL,
    ALTER COLUMN email_preference SET DEFAULT FALSE,
    ALTER COLUMN email_preference SET NOT NULL,
    ALTER COLUMN phone_preference SET DEFAULT FALSE,
    ALTER COLUMN phone_preference SET NOT NULL,
    ALTER COLUMN marketing_preference SET DEFAULT FALSE,
    ALTER COLUMN marketing_preference SET NOT NULL,
    ALTER COLUMN flag_value SET DEFAULT FALSE,
    ALTER COLUMN flag_value SET NOT NULL,
    ALTER COLUMN version SET DEFAULT 0,
    ALTER COLUMN version SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT NOW(),
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE IF EXISTS contact_point
    ALTER COLUMN person_id SET NOT NULL,
    ALTER COLUMN contact_type SET NOT NULL,
    ALTER COLUMN value SET NOT NULL,
    ALTER COLUMN is_primary SET DEFAULT FALSE,
    ALTER COLUMN is_primary SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT NOW(),
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE IF EXISTS party_relationship
    ALTER COLUMN from_party_id SET NOT NULL,
    ALTER COLUMN to_person_id SET NOT NULL,
    ALTER COLUMN role_type SET NOT NULL,
    ALTER COLUMN is_primary_billing_contact SET DEFAULT FALSE,
    ALTER COLUMN is_primary_billing_contact SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT NOW(),
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE IF EXISTS promotion_redemption
    ALTER COLUMN customer_id SET NOT NULL,
    ALTER COLUMN promotion_id SET NOT NULL,
    ALTER COLUMN promotion_code SET NOT NULL,
    ALTER COLUMN status SET NOT NULL,
    ALTER COLUMN redemption_timestamp SET DEFAULT NOW(),
    ALTER COLUMN redemption_timestamp SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN created_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_contact_point_person_type
    ON contact_point (person_id, contact_type);
CREATE INDEX IF NOT EXISTS idx_party_relationship_from_to
    ON party_relationship (from_party_id, to_person_id);
CREATE INDEX IF NOT EXISTS idx_promotion_redemption_customer_promotion
    ON promotion_redemption (customer_id, promotion_id);
