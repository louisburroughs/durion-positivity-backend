-- #2554 follow-up (CAP:550): reconciliation_records was written only by the legacy
-- PaymentOutcomeProcessingServiceImpl, removed as unreachable in #2568. No entity maps it and nothing reads it.
-- Dropping the table removes its constraints, index and tenant_isolation policy with it. Pre-production: no data
-- is kept.
DROP TABLE IF EXISTS public.reconciliation_records;
