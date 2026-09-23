-- #2167: a cycle count adjustment with no task had no way to say which shelf it counted, so its
-- variance posted against the stock item's location-less balance and the negative-stock policy
-- judged it against that balance, not the stock actually on the counted shelf. The location is now
-- resolved when the adjustment is created (the task's bin, else the request's locationId) and kept
-- here, so approval posts against the same shelf the count was taken at.
--
-- Nullable rather than backfilled: rows recorded before this column fall back to their task's bin
-- at posting time, exactly as before.
ALTER TABLE cycle_count_adjustment ADD COLUMN location_id uuid;

COMMENT ON COLUMN cycle_count_adjustment.location_id IS
    'Storage location the variance posts against: the task bin, else the create request locationId (#2167). Null when neither named one.';
