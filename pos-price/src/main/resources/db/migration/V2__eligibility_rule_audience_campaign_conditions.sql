-- Issue #1134: allow audience-type / campaign-code eligibility predicates
ALTER TABLE promotion_eligibility_rule DROP CONSTRAINT promotion_eligibility_rule_condition_type_check;

ALTER TABLE promotion_eligibility_rule ADD CONSTRAINT promotion_eligibility_rule_condition_type_check
    CHECK (condition_type IN ('ACCOUNT_ID_LIST', 'VEHICLE_TAG', 'ACCOUNT_FLEET_SIZE', 'AUDIENCE_TYPE', 'CAMPAIGN_CODE'));
