-- #2121: the offboarding retry queue now has a worker, and a GRACE_PERIOD retry has to know the end
-- date the original request asked for. Until now the queue row carried the policy but not the date,
-- so a retry could not have reproduced the request it replays. Nullable: IMMEDIATE rows have no
-- end date, and rows queued before this column existed (none were ever processed) get none.
ALTER TABLE public.employee_offboarding_retry_queue
    ADD COLUMN assignment_end_date date;
