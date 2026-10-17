-- #2620 (Accounting ruling, 2026-10-17): a settled payment held for its currency (SUSPENDED / CURRENCY_NOT_SUPPORTED)
-- that was reprocessed before the fence existed fell to the posting engine, which cannot handle a Kafka row, and was
-- marked FAILED with "Posting engine error: ..." (a null organizationId). Nothing was posted. The hold is the record:
-- restore it, so the row again reads as what it is and ADR-0067 Stage B1 can release it from the stored payload.
-- Only Kafka rows (ingestion_id set) of that type, failed by the engine, are touched; the attempt history keeps the
-- failed attempts as they happened.
UPDATE public.accounting_event
   SET status = 'SUSPENDED',
       failure_reason_code = 'CURRENCY_NOT_SUPPORTED',
       failure_details = 'Held for its currency (ADR-0067 PC-9); released by Stage B1. Hold restored by V30 (#2620) after'
                         || ' a reprocess fell to the posting engine: ' || COALESCE(failure_details, ''),
       error_message = 'Held for its currency (ADR-0067 PC-9); released by Stage B1'
 WHERE event_type = 'payment.payment.settled'
   AND status = 'FAILED'
   AND ingestion_id IS NOT NULL
   AND failure_details LIKE 'Posting engine error:%';
