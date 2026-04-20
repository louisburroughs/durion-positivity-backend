CREATE TABLE IF NOT EXISTS bulk_load_job (
  id UUID PRIMARY KEY,
  operator_id VARCHAR(255) NOT NULL,
  location_id UUID,
  file_name VARCHAR(500) NOT NULL,
  original_file_path VARCHAR(1000),
  domain_type VARCHAR(50) NOT NULL,
  status VARCHAR(50) NOT NULL,
  total_rows BIGINT,
  processed_rows BIGINT DEFAULT 0,
  success_count BIGINT DEFAULT 0,
  failure_count BIGINT DEFAULT 0,
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS bulk_load_record_audit (
  id UUID PRIMARY KEY,
  job_id UUID NOT NULL REFERENCES bulk_load_job(id),
  entity_type VARCHAR(100),
  entity_id UUID,
  row_number BIGINT,
  review_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
  reason_codes JSONB,
  original_values JSONB,
  corrected_values JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS bulk_load_column_mapping (
  id UUID PRIMARY KEY,
  job_id UUID NOT NULL REFERENCES bulk_load_job(id),
  source_column VARCHAR(500) NOT NULL,
  target_field VARCHAR(500) NOT NULL,
  confidence DOUBLE PRECISION,
  overridden_by_user BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_bulk_load_job_operator_id ON bulk_load_job(operator_id);
CREATE INDEX IF NOT EXISTS idx_bulk_load_job_status ON bulk_load_job(status);
CREATE INDEX IF NOT EXISTS idx_bulk_load_record_audit_job_id ON bulk_load_record_audit(job_id);
CREATE INDEX IF NOT EXISTS idx_bulk_load_record_audit_review_status ON bulk_load_record_audit(review_status);
CREATE INDEX IF NOT EXISTS idx_bulk_load_column_mapping_job_id ON bulk_load_column_mapping(job_id);