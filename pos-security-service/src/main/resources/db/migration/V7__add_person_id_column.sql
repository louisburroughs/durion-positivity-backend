-- AUTH-005: Add person_id column to users table for JWT personId claim
ALTER TABLE users ADD COLUMN person_id UUID NULL;
