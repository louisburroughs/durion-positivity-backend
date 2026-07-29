-- FI-3 (#1133): declined-recommendation service-history feed.
-- Declined estimate recommendations are now promoted onto the workorder as declined service
-- lines so the decline has a source workorder and its reason is retained. Null means "not
-- declined" or "no reason recorded"; the WorkorderServiceLineDeclinedV1 fact carries it to CRM.
ALTER TABLE workorder_service ADD COLUMN decline_reason VARCHAR(500);
