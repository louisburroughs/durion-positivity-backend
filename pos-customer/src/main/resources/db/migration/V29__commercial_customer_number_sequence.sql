-- Commercial customer numbers were the first 8 hex characters of a UUIDv7,
-- which are the top 32 bits of its millisecond timestamp: the same value for
-- every id minted inside a ~65 second window. With customer_number UNIQUE, only
-- the first account created in each window could be saved and every other one
-- failed on the constraint.
--
-- A sequence gives the same 8-character shape with none of that. Numbers are
-- rendered base-36 and zero-padded to 8 characters, so the sequence is bounded
-- by 36^8 - 1 = 2821109907455; MAXVALUE states that in the database rather than
-- leaving it to the application alone.
--
-- START WITH is chosen so a generated number can never equal a historical one.
-- Every existing value renders 8 hex characters, and the largest string of hex
-- characters read as base-36 is 'FFFFFFFF' = 1209047103195. Starting one above
-- that puts the whole sequence range beyond anything the old scheme could have
-- produced, without needing to read the table. The first number issued is
-- CUST-FFFFFFFG and the last is CUST-ZZZZZZZZ, leaving 1.6e12 of them.
--
-- Existing rows are left exactly as they are.
CREATE SEQUENCE IF NOT EXISTS commercial_party_customer_number_seq
    AS bigint
    START WITH 1209047103196
    INCREMENT BY 1
    MINVALUE 1209047103196
    MAXVALUE 2821109907455
    NO CYCLE;
