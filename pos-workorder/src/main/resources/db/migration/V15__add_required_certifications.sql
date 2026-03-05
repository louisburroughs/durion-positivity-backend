-- CAP-142: Add required certifications column to workorder for skill mismatch detection
ALTER TABLE workorder ADD COLUMN required_certifications TEXT;
