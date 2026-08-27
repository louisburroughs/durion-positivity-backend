-- #1527: pay periods are now written by the rollover scheduler and the operator API.
-- Two writers (or two service instances running the same scheduled rollover) may race
-- to create the same grid period; a unique start date per tenant turns that race into
-- a constraint violation the rollover skips over instead of a duplicated period.
ALTER TABLE time_period
    ADD CONSTRAINT uq_time_period_tenant_start UNIQUE (tenant_id, start_date);
