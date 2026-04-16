-- Retrofit FK constraints for tables that already existed before V22 (CREATE TABLE IF NOT EXISTS
-- silently skips constraints on pre-existing tables). Uses DO-block guards for idempotency.
DO $$ BEGIN IF NOT EXISTS (
  SELECT 1
  FROM pg_constraint
  WHERE conname = 'fk_change_request_workorder'
) THEN
ALTER TABLE change_request
ADD CONSTRAINT fk_change_request_workorder FOREIGN KEY (workorder_id) REFERENCES workorder(id);
END IF;
IF NOT EXISTS (
  SELECT 1
  FROM pg_constraint
  WHERE conname = 'fk_workorder_service_workorder'
) THEN
ALTER TABLE workorder_service
ADD CONSTRAINT fk_workorder_service_workorder FOREIGN KEY (work_order_id) REFERENCES workorder(id);
END IF;
IF NOT EXISTS (
  SELECT 1
  FROM pg_constraint
  WHERE conname = 'fk_workorder_service_change_request'
) THEN
ALTER TABLE workorder_service
ADD CONSTRAINT fk_workorder_service_change_request FOREIGN KEY (change_request_id) REFERENCES change_request(id);
END IF;
IF NOT EXISTS (
  SELECT 1
  FROM pg_constraint
  WHERE conname = 'fk_workorder_service_estimate_item'
) THEN
ALTER TABLE workorder_service
ADD CONSTRAINT fk_workorder_service_estimate_item FOREIGN KEY (origin_estimate_item_id) REFERENCES estimate_item(id);
END IF;
END $$;