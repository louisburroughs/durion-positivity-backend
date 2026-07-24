-- odoo-parity K2 (issue #1055): destination-selection strategy on putaway rules.
-- Additive, non-null with a FIXED default so every existing rule keeps its
-- pre-K2 fixed-destination behavior.
ALTER TABLE putaway_rule
    ADD COLUMN destination_strategy varchar(32) NOT NULL DEFAULT 'FIXED';
