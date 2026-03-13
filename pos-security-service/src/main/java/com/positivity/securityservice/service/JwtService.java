package com.positivity.securityservice.service;

import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.NonNull;

/**
 * Service for handling JWT token operations such as generation, validation,
 * extraction, and deletion.
 * 
 * **Security Model (ADR-0011):**
 * - JWT secret: Injected from environment variable `SECURITY_JWT_SECRET`
 * - Token revocation: Cached in Redis with TTL matching expiration
 * - Access token lifetime: 1 hour (3600 seconds)
 * - Refresh token lifetime: 7 days (604800 seconds)
 * - JWT ID (JTI): Unique identifier for token revocation tracking
 * 
 * **Implementation Notes:**
 * - Permission encoding: Effective permissions are encoded into perm_bits
 *   (Base64URL BitSet) at token issuance via PermissionBitsetCodec.
 * - Concurrency: JwtToken entity uses @Version for optimistic locking
 * - Graceful Degradation: If Redis unavailable, token validation still succeeds
 * 
 * @since 1.0
 */
public interface JwtService {
   /** Legacy claim key; retained for backward-compatible decoding of old tokens only. New tokens do not include this claim. */
    public static final String ROLES = "roles";
   /** Legacy claim key; retained for backward-compatible decoding of old tokens only. New tokens do not include this claim. */
    public static final String AUTHORITIES = "authorities";
    /** Claim key for stable user identifier used by audit lineage. */
    public static final String USER_ID = "userId";
    /** Claim key for JWT ID (unique identifier for revocation). */
    public static final String JTI = "jti";
    /** Claim key for compact Base64URL-encoded permission bitset. */
    public static final String PERM_BITS = "perm_bits";
    /** Claim key for the catalog version used to encode perm_bits. */
    public static final String PERM_VER = "perm_ver";
    /** Claim key for stable user UUID identifier (replaces userId in new tokens). */
    public static final String UID = "uid";
    /** Claim key for human-readable display name (mirrors sub for gateway header). */
    public static final String USERNAME = "username";

    /**
     * Generates a JWT token for the given username and roles, stores it in the
     * repository, and returns the token string.
     *
     * **Implementation:**
        * - Token ID (JTI): Unique UUID v7 identifier for revocation tracking
     * - Expiration: 1 hour (3600 seconds)
        * - Permissions: Encoded as perm_bits Base64URL BitSet via
        *   PermissionBitsetCodec at issuance.
     * - Revocation: Token stored in Redis with 1-hour TTL
     * 
     * @param username the subject for the token
     * @param userId   stable user identifier for audit lineage
     * @param roles    the set of roles to include in the token
     * @return the generated JWT token string
     * 
     * @throws IllegalArgumentException if username, userId, or roles are invalid
     */
    String generateToken(@NonNull String username, @NonNull UUID userId, @NonNull Set<String> roles);

    /**
     * Validates the given JWT token by checking:
     * 1. JWT signature (HMAC-SHA256)
     * 2. Token expiration
     * 3. Revocation status in Redis cache
     * 4. Presence in database
     *
     * **Concurrency:**
     * - JwtToken entity uses @Version for optimistic locking
     * - Token revocation checks Redis with 1-5ms latency (typical)
     * 
     * **Graceful Degradation:**
     * - If Redis is unavailable: Still validates signature and database presence
     * - If database is unavailable: Fails (token not found)
     *
     * @param token the JWT token string to validate
     * @return true if the token is valid, not revoked, and not expired
     */
    boolean validateToken(@NonNull String token);

    /**
     * Extracts the username (subject) from the given JWT token.
     *
     * @param token the JWT token string
     * @return the subject (username) from the token
     */
    String getUsernameFromToken(@NonNull String token);

    /**
     * Extracts the stable user identifier from the given JWT token.
     *
     * @param token the JWT token string
     * @return stable user identifier claim
     */
    UUID getUserIdFromToken(@NonNull String token);

    /**
     * Extracts the set of roles from the given JWT token.
     *
     * @param token the JWT token string
     * @return a set of roles, or an empty set if none are found
     */
    Set<String> getRolesFromToken(@NonNull String token);

    /**
     * Extracts the set of authorities from the given JWT token.
     */
    Set<String> getAuthoritiesFromToken(@NonNull String token);

    /**
     * Deletes the given JWT token from the repository and marks it as revoked in
     * Redis.
     * 
     * **Process:**
     * 1. Delete token from database first (fail-fast if token doesn't exist)
     * 2. Extract JTI and revoke in Redis cache
     * 
     * **Consistency:**
     * - Database deletion is performed first within a transaction
     * - Redis revocation is best-effort (token is already invalidated in DB)
     *
     * @param token the JWT token string to delete
     * @return true if the token was found and deleted, false if it didn't exist
     */
    boolean deleteToken(@NonNull String token);

    /**
     * Revokes a token by its JTI (JWT ID) and removes it from the database.
     * 
     * @param jti               the JWT ID (unique token identifier)
     * @param expirationSeconds token expiration time in seconds
     * 
     * @throws IllegalArgumentException if jti is blank or expirationSeconds <= 0
     */
    void revokeTokenByJti(@NonNull String jti, long expirationSeconds);

    /**
     * Record representing a pair of access and refresh tokens.
     *
     * @param accessToken  the access token
     * @param refreshToken the refresh token
     */
    record TokenPair(String accessToken, String refreshToken) {
    }

    /**
     * Generates a pair of access and refresh tokens for the given username and
     * roles, stores them, and returns the pair.
     * 
     * **Token Lifetimes:**
     * - Access token: 1 hour (3600 seconds)
     * - Refresh token: 7 days (604800 seconds)
     * 
     * **JTI (JWT ID):**
     * - Both tokens include a unique JTI for revocation tracking
     * - Each token has separate JTI (not shared)
    * - Permissions: Encoded as perm_bits Base64URL BitSet via
    *   PermissionBitsetCodec at issuance.
     * 
     * @param username the subject for the tokens
     * @param userId   stable user identifier for audit lineage
   * @param roles    the set of roles used to derive the permission bitset claim (perm_bits)
     * @return a TokenPair containing the access and refresh tokens
     * 
     * @throws IllegalArgumentException if username, userId, or roles are invalid
     */
    TokenPair generateTokenPair(@NonNull String username, @NonNull UUID userId, @NonNull Set<String> roles);

    /**
     * Validates the given refresh token by checking:
     * 1. JWT signature (HMAC-SHA256)
     * 2. Token expiration
     * 3. Revocation status in Redis
     * 4. Presence in database
     *
     * @param refreshToken the refresh token string to validate
     * @return true if the refresh token is valid and not expired, false otherwise
     */
    boolean validateRefreshToken(@NonNull String refreshToken);

    /**
     * Refreshes the access token using the given refresh token.
     *
     * **Process:**
     * 1. Validates refresh token (signature, expiration, revocation)
        * 2. Extracts uid from refresh token and loads current roles from persistence
     * 3. Invalidates old tokens (marks as revoked in Redis)
     * 4. Generates new token pair with fresh expiration times
     * 
     * **Concurrency:**
     * - Handles OptimisticLockingFailureException with exponential backoff retry
     * - JwtToken entity uses @Version for optimistic locking
     *
     * @param refreshToken the refresh token string
     * @return a new TokenPair with fresh access and refresh tokens
     * 
     * @throws IllegalArgumentException if the refresh token is invalid or not found
     */
    TokenPair refreshAccessToken(@NonNull String refreshToken);
}
