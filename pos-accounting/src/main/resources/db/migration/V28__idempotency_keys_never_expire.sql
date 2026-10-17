-- #2595: posting idempotency keys never expire. A fact redelivered under a fresh eventId days later must still
-- post nothing twice, and journal_entry.source_event_id is only indexed, not unique, so the key is the durable
-- guard. New keys carry no expiry; rows written with one before this change are honoured whatever it says
-- (IdempotencyService no longer reads it) and nothing deletes them (cleanupExpiredKeys is gone).
ALTER TABLE public.idempotency_keys ALTER COLUMN expires_at DROP NOT NULL;
