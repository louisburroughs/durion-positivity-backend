-- The starter password an account provisioned from a fixture pack is claimed with, hashed, in a
-- column of its own rather than in `password`.
--
-- Storing it as the account's password would have made it matchable at login. Spring Security's
-- DaoAuthenticationProvider compares the password in additionalAuthenticationChecks and only then
-- runs postAuthenticationChecks, where credentials expiry lives, so a correct starter password
-- would have produced CREDENTIALS_EXPIRED while a wrong one produced INVALID_CREDENTIALS. The
-- login would still have failed either way -- no token is issued for an account whose credentials
-- are expired -- but the two answers differ, which tells an unauthenticated caller both that the
-- password it guessed is the real starter and that the account has not been claimed yet.
--
-- Held apart, `password` keeps the discarded random value createUser writes, exactly as the first
-- administrator's account does (ADR-0062 section 7). Login therefore cannot match anything and
-- answers the same INVALID_CREDENTIALS for every password, starter included, which is the
-- behaviour that ADR entry already describes for the token flow.
--
-- Cleared when the account is claimed: the value is single-use per account, and an account that
-- has been claimed must not be re-openable with the shared password.
ALTER TABLE users ADD COLUMN starter_password_hash character varying(255);

COMMENT ON COLUMN users.starter_password_hash IS
    'Bcrypt hash of the shared starter password a bulk-provisioned account is claimed with, or NULL. '
    'Never compared at login: POST /v1/auth/activate-starter is the only reader, and it clears this '
    'on success.';
