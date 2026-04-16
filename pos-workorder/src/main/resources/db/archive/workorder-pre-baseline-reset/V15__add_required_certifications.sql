-- CAP-142: Add required certifications column to workorder for skill mismatch detection
ALTER TABLE IF EXISTS workorder ADD COLUMN IF NOT EXISTS required_certifications TEXT;
