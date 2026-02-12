-- Test schema initialization for H2 database
-- Creates sequences and initial data needed for integration tests

-- Create bill_number_seq for unique, distributed bill number generation
-- Used by VendorBill entity for human-readable bill number generation
CREATE SEQUENCE IF NOT EXISTS bill_number_seq
    START WITH 1000
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 9999999
    CACHE 20
    NO CYCLE;

COMMENT ON SEQUENCE bill_number_seq IS 
    'Auto-increment sequence for vendor bill numbers. Starts at 1000 for human-readable 
     bill numbers. Used in ap_number generation with vendor prefix.';
