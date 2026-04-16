-- Issue CAP-214 #39: Storage location topology persistence
CREATE TABLE IF NOT EXISTS storage_location (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    barcode VARCHAR(255),
    type VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    site_id UUID NOT NULL,
    parent_storage_location_id UUID,
    inventory_count INT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_storage_location_name_site
    ON storage_location (LOWER(name), site_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_storage_location_barcode_site
    ON storage_location (barcode, site_id);
