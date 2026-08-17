-- CAP-324 (#1257): pos-image gains somewhere to put image bytes.
--
-- Until now this module was a registry of filenames and URLs: the read path resolved `url` as a
-- filesystem path and served whatever was there. Nothing could put an image in, which is why MKCAT
-- ingestion had nowhere to store the manufacturer artwork it fetches.
--
-- Content-addressed, not id-addressed. The same manufacturer image is republished unchanged on
-- every catalogue refresh, and a store keyed on anything but the bytes would accumulate a new copy
-- per fetch cycle forever.

CREATE TABLE IF NOT EXISTS image_content (
    -- SHA-256 of the bytes, lower-case hex. The identity of an image IS its content.
    content_hash  VARCHAR(64)  PRIMARY KEY,
    content_type  VARCHAR(255) NOT NULL,
    byte_size     BIGINT       NOT NULL,
    content       bytea        NOT NULL,
    created_at    timestamp(6) with time zone NOT NULL,

    CONSTRAINT ck_image_content_size CHECK (byte_size > 0)
);

-- Existing rows keep working exactly as before: a null content_hash means "served from the url
-- path", which is what every row created before this migration is. Backfilling them would mean
-- reading files this service may no longer have.
ALTER TABLE image ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);
ALTER TABLE image ADD COLUMN IF NOT EXISTS content_type VARCHAR(255);
ALTER TABLE image ADD COLUMN IF NOT EXISTS byte_size    BIGINT;

-- Where the bytes came from, kept as evidence rather than as a render source. A vendor URL stops
-- being how the image is served; it does not stop being how the image is explained.
ALTER TABLE image ADD COLUMN IF NOT EXISTS source_uri VARCHAR(2000);

ALTER TABLE image ADD COLUMN IF NOT EXISTS created_at timestamp(6) with time zone;
ALTER TABLE image ADD COLUMN IF NOT EXISTS updated_at timestamp(6) with time zone;

-- Finding the record for a set of bytes without scanning: this is how a re-ingest of unchanged
-- vendor content resolves to the image already held instead of storing a second one.
CREATE INDEX IF NOT EXISTS ix_image_content_hash ON image (content_hash);

-- Answering "have we already fetched this vendor URL", which is the cheap check before the
-- expensive one.
CREATE INDEX IF NOT EXISTS ix_image_source_uri ON image (source_uri);
