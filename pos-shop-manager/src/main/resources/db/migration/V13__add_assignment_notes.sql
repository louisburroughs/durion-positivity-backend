-- CAP-249 Story #10: Add optional notes column to assignment table (max 500 chars per business rule)
ALTER TABLE assignment ADD COLUMN notes VARCHAR(500);
