-- Backfill workorder.shop_id (and location_id) from the linked estimate's location_id.
-- shop_id and location_id are the same concept (a shop is a location); the estimate carries
-- it as location_id. The workorder creation path historically left both null, so the assign
-- page — which resolves its technician roster from shop_id — could not load technicians.
-- Going forward doCreateWorkorder seeds both from the estimate; this repairs existing rows.

-- shop_id from the estimate's location.
UPDATE workorder w
SET shop_id = e.location_id
FROM estimate e
WHERE w.estimate_id = e.id
  AND w.shop_id IS NULL
  AND e.location_id IS NOT NULL;

-- location_id likewise, where the assignment-context path never set it.
UPDATE workorder w
SET location_id = e.location_id
FROM estimate e
WHERE w.estimate_id = e.id
  AND w.location_id IS NULL
  AND e.location_id IS NOT NULL;

-- Where one of the two columns was already populated (e.g. via assignment context) but the
-- other is still null, mirror the known value so shop_id and location_id stay consistent.
UPDATE workorder
SET shop_id = location_id
WHERE shop_id IS NULL
  AND location_id IS NOT NULL;

UPDATE workorder
SET location_id = shop_id
WHERE location_id IS NULL
  AND shop_id IS NOT NULL;

-- Note: estimate-less workorders (estimate_id IS NULL) with no assignment context remain null;
-- they have no location source to backfill from and need a separate resolution path.
