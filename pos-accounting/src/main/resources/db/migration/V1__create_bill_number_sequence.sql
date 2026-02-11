-- Create bill_number_seq for unique, distributed bill number generation
-- Issue #130: Receipt Accrual Workflow - Vendor Bill lifecycle management
-- 
-- This sequence ensures globally unique bill numbers that survive service restarts
-- and work across multi-instance deployments (cluster-safe).

CREATE SEQUENCE IF NOT EXISTS bill_number_seq
    START WITH 1
    INCREMENT BY 1
    NO CYCLE
    CACHE 100;  -- Cache 100 values to reduce database round-trips

COMMENT ON SEQUENCE bill_number_seq IS 
    'Generates unique sequence numbers for vendor bill numbers. Format: BILL_<VendorPrefix>_<YYYYMMDD>_<sequence>';
