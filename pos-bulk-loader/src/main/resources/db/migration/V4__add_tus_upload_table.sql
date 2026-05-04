CREATE TABLE IF NOT EXISTS tus_upload (
    id          UUID PRIMARY KEY,
    job_id      UUID         NOT NULL REFERENCES bulk_load_job(id) ON DELETE CASCADE,
    operator_id VARCHAR(255) NOT NULL,
    file_name   VARCHAR(500) NOT NULL,
    total_size  BIGINT       NOT NULL,
    upload_offset BIGINT     NOT NULL DEFAULT 0,
    completed   BOOLEAN      NOT NULL DEFAULT FALSE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tus_upload_job_id ON tus_upload(job_id);
CREATE INDEX IF NOT EXISTS idx_tus_upload_expires_at ON tus_upload(expires_at) WHERE completed = FALSE;
