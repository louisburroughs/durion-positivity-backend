-- Commercial customer numbers were the first 8 hex characters of a UUIDv7,
-- which are the top 32 bits of its millisecond timestamp: the same value for
-- every id minted inside a ~65 second window. With customer_number UNIQUE, only
-- the first account created in each window could be saved and every other one
-- failed on the constraint.
--
-- A sequence gives the same 8-character shape with none of that. It starts
-- above the values already issued so a fresh number can never collide with a
-- historical one; existing rows are left exactly as they are.
CREATE SEQUENCE IF NOT EXISTS commercial_party_customer_number_seq
    AS bigint
    START WITH 1
    INCREMENT BY 1
    NO CYCLE;
