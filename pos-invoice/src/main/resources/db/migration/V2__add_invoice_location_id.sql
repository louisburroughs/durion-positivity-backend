-- Add shop location reference to invoices.
-- Used to resolve the tax jurisdiction address (queried from pos-location) at
-- tax calculation time. Nullable: pre-existing rows and non-workorder creation
-- paths may not carry a location.

SET TIME ZONE 'UTC';

ALTER TABLE invoices ADD COLUMN location_id uuid;
