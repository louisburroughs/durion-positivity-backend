-- Story #1142: campaign attribution on redemptions.
-- Distinct from promotion_code: the same promotion can run in several campaigns, and
-- attribution has to tell them apart when reporting commercial vs individual arms.
ALTER TABLE promotion_redemption
    ADD COLUMN campaign_code character varying(100);

CREATE INDEX idx_promotion_redemption_campaign_code
    ON promotion_redemption (campaign_code)
    WHERE campaign_code IS NOT NULL;
