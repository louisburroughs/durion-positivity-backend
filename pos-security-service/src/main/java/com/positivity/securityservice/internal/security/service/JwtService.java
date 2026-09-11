package com.positivity.securityservice.internal.security.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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
 * (Base64URL BitSet) at token issuance via PermissionBitsetCodec.
 * - Concurrency: JwtToken entity uses @Version for optimistic locking
 * - Graceful Degradation: If Redis unavailable, token validation still succeeds
 *
 * @since 1.0
 */
public interface JwtService {
    /**
     * Role claim key.
     * Access tokens include this claim as informational compatibility data.
     * Authorization decisions remain driven by {@code perm_bits}/{@code perm_ver}.
     */
    public static final String ROLES = "roles";
    /**
     * Legacy claim key; retained for backward-compatible decoding of old tokens
     * only. New tokens do not include this claim.
     */
    public static final String AUTHORITIES = "authorities";
    /** Claim key for stable user identifier used by audit lineage. */
    public static final String USER_ID = "userId";
    /** Claim key for JWT ID (unique identifier for revocation). */
    public static final String JTI = "jti";
    /** Claim key for compact Base64URL-encoded permission bitset. */
    public static final String PERM_BITS = "perm_bits";
    /** Claim key for the catalog version used to encode perm_bits. */
    public static final String PERM_VER = "perm_ver";
    /**
     * Claim key for stable user UUID identifier (replaces userId in new tokens).
     */
    public static final String UID = "uid";
    /**
     * Claim key for human-readable display name (mirrors sub for gateway header).
     */
    public static final String USERNAME = "username";
    /** Claim key for optional CRM person identifier linked to this user. */
    public static final String PERSON_ID = "personId";
    /**
     * Claim key for the Base64URL bitset of permissions that are location-scoped along the
     * {@code FINANCIAL} hierarchy (ADR-0061 §2). Same codec and bit indexes as {@code perm_bits},
     * so it is covered by {@code perm_ver}. Access tokens only.
     */
    public static final String LOC_FIN_BITS = "loc_fin_bits";
    /** As {@link #LOC_FIN_BITS}, along the {@code OTHER} hierarchy. */
    public static final String LOC_OTH_BITS = "loc_oth_bits";
    /**
     * Claim key for the assigned location nodes: a JSON object {@code {"v":1,"nodes":[...]}}.
     * Omitted when both scope bitsets are empty, and — fail closed — when they are not but no
     * assigned node could be resolved (ADR-0061 §2). Never {@code "ALL"}, never a bare list.
     */
    public static final String LOC_SCOPE = "loc_scope";

    /**
     * Tenant id claim (ADR-0062 §3, ADR-0040 §2 amendment): required on access and refresh tokens;
     * the gateway injects {@code X-Tenant-Id} from it and a refresh exchange cannot change tenant.
     */
    public static final String TID = "tid";
    /**
     * Actor claim of an impersonation token (ADR-0062 §7, WS2b-4): a JSON object
     * {@code {"sub": "<operator user id>", "username": "<operator username>"}} naming the platform
     * operator the token was minted for. Absent from every other token.
     */
    public static final String ACT = "act";
    /**
     * Token-use discriminator. Present only on an impersonation token, with the value
     * {@link #TOKEN_USE_IMPERSONATION}; the refresh exchange refuses such a token outright, and the
     * issuer never pairs one with a refresh token.
     */
    public static final String TOKEN_USE = "token_use";
    /** The {@link #TOKEN_USE} value of an impersonation token. */
    public static final String TOKEN_USE_IMPERSONATION = "impersonation";
    /** Lifetime of an impersonation token; there is no refresh, so this is the whole session. */
    public static final Duration IMPERSONATION_TOKEN_VALIDITY = Duration.ofMinutes(15);
    /**
     * Discriminator value of the {@link #LOC_SCOPE} object. Deliberate: a denser node encoding can
     * be introduced under a new value without a {@code CATALOG_VERSION} bump.
     */
    public static final int LOC_SCOPE_VERSION = 1;

    /**
     * Generates a JWT token for the given username and roles, stores it in the
     * repository, and returns the token string.
     *
     * **Implementation:**
     * - Token ID (JTI): Unique UUID v7 identifier for revocation tracking
     * - Expiration: 1 hour (3600 seconds)
     * - Permissions: Encoded as perm_bits Base64URL BitSet via
     * PermissionBitsetCodec at issuance.
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
     * @return stable user identifier claim, or {@code null} if the claim is absent
     *         or cannot be parsed as a {@link UUID}
     */
    @Nullable
    UUID getUserIdFromToken(@NonNull String token);

    /**
     * Extracts the {@code personId} claim from the given JWT access token.
     *
     * @param token the JWT token string
     * @return the person UUID, or {@code null} if the claim is absent or unparsable
     */
    @Nullable
    UUID getPersonIdFromToken(@NonNull String token);

    /**
     * Extracts the {@code jti} claim from a signed access or refresh token issued by this service.
     *
     * @param token the JWT token string
     * @return the JWT ID, or {@code null} if the token carries none
     * @throws io.jsonwebtoken.JwtException if the token does not verify or has expired
     */
    @Nullable
    String getJtiFromToken(@NonNull String token);

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
     * Decodes the {@code loc_fin_bits} claim to permission codes (ADR-0061 §2).
     *
     * @return the permissions location-scoped along {@code FINANCIAL}; empty when the claim is absent
     */
    Set<String> getFinancialLocationScopedPermissionsFromToken(@NonNull String token);

    /**
     * Decodes the {@code loc_oth_bits} claim to permission codes (ADR-0061 §2).
     *
     * @return the permissions location-scoped along {@code OTHER}; empty when the claim is absent
     */
    Set<String> getOtherLocationScopedPermissionsFromToken(@NonNull String token);

    /**
     * Reads the {@code loc_scope} claim (ADR-0061 §2).
     *
     * @return the assigned nodes, or empty when the claim is absent — which a scope-checking
     *         reader must treat as "deny", never as unrestricted reach
     */
    Optional<LocationScopeClaim> getLocationScopeFromToken(@NonNull String token);

    /**
     * The decoded {@code loc_scope} claim.
     *
     * @param version the discriminator ({@link #LOC_SCOPE_VERSION})
     * @param nodes   the assigned location node ids, verbatim and never expanded
     */
    record LocationScopeClaim(int version, List<UUID> nodes) {
        public LocationScopeClaim {
            nodes = List.copyOf(nodes);
        }
    }

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
     * Revokes all active access and refresh tokens for the given user.
     *
     * <p>Deletes all {@code JwtToken} records for the subject and marks each
     * token's JTI as revoked in the Redis cache. Called during account-state
     * transitions (disable, expireAccount, expireCredentials) to ensure no
     * active token survives an account deactivation.
     *
     * @param username the subject (username) of the user whose tokens should be revoked
     */
    void revokeAllTokensForUser(@NonNull String username);

    /**
     * Record representing a pair of access and refresh tokens.
     *
     * @param accessToken  the access token
     * @param refreshToken the refresh token
     */
    record TokenPair(String accessToken, String refreshToken) {}

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
     * PermissionBitsetCodec at issuance.
     *
     * @implNote Access token claims: {@code sub}, {@code uid}, {@code username},
     *           {@code iss} ("pos-security-service"), {@code aud} ("api-gateway"),
     *           {@code perm_bits}, {@code perm_ver}, {@code loc_fin_bits}, {@code loc_oth_bits},
     *           optional {@code loc_scope}, {@code iat}, {@code exp}, {@code jti}
     *           (ADR-0061 §2). When either scope bitset is non-empty, {@code exp} is clamped to
     *           the end of the day the earliest contributing staffing assignment ends
     *           (ADR-0061 §4). Refresh token claims: {@code sub}, {@code uid},
     *           {@code type}="refresh", {@code iat}, {@code exp}, {@code jti} — never the
     *           permission or scope claims.
     *
     * @param username the subject for the tokens
     * @param userId   stable user identifier for audit lineage
     * @param personId optional CRM person identifier; when non-null, included as
     *                 {@code personId} claim in the access token only. When null,
     *                 the claim is omitted (expected for users not yet linked to a
     *                 person record).
     * @param roles    the set of roles used to derive the permission bitset claim
     *                 (perm_bits)
     * @return a TokenPair containing the access and refresh tokens
     *
     * @throws IllegalArgumentException if username, userId, or roles are invalid
     * @see #generateTokenPair(String, UUID, UUID, Set, Instant) the overload the login and refresh
     *      paths use, which additionally clamps {@code exp} to a role-assignment end date. This
     *      overload always passes {@code null} for that bound — the internal token-issuance
     *      endpoints (client-supplied roles, no resolved user) deliberately get no assignment
     *      clamp (ADR-0061 §4 amendment, 2026-09-09, #1914 phase 3).
     */
    TokenPair generateTokenPair(
            @NonNull String username, @NonNull UUID userId, @Nullable UUID personId, @NonNull Set<String> roles);

    /**
     * As {@link #generateTokenPair(String, UUID, UUID, Set)}, additionally clamping {@code exp} to
     * {@code grantsExpireAt} when it is earlier than every other applicable bound.
     *
     * <p>ADR-0061 §4 amendment (2026-09-09, #1914 phase 3): the location-reach clamp already
     * bounds {@code exp} to the end of the earliest contributing staffing assignment. The role
     * assignments {@code perm_bits} is built from were left out of that clamp — this overload
     * closes that gap. {@code exp} is {@code min(now + 3600s, the location-reach bound,
     * grantsExpireAt)}, floored at {@code now}; used by the login path ({@code
     * AuthenticationServiceImpl}, which resolves the bound via {@code
     * UserService#getGrantsExpireAt}) and the refresh path ({@link #refreshAccessToken}, which
     * resolves it the same way).
     *
     * @param grantsExpireAt the earliest end of a role assignment currently contributing to {@code
     *                       roles}, or {@code null} when every contributing assignment is
     *                       open-ended (no clamp from this bound)
     * @return a TokenPair containing the access and refresh tokens
     *
     * @throws IllegalArgumentException if username, userId, or roles are invalid
     */
    TokenPair generateTokenPair(
            @NonNull String username,
            @NonNull UUID userId,
            @Nullable UUID personId,
            @NonNull Set<String> roles,
            @Nullable Instant grantsExpireAt);

    /**
     * Validates the given refresh token by checking:
     * 1. JWT signature (HMAC-SHA256)
     * 2. Token expiration
     * 3. Revocation status in Redis
     * 4. Presence in database
     *
     * <p>An impersonation token ({@link #TOKEN_USE} = {@link #TOKEN_USE_IMPERSONATION}) is never a
     * valid refresh token, whatever else it carries.
     *
     * @param refreshToken the refresh token string to validate
     * @return true if the refresh token is valid and not expired, false otherwise
     */
    boolean validateRefreshToken(@NonNull String refreshToken);

    /**
     * A freshly minted impersonation token (ADR-0062 §7, WS2b-4).
     *
     * @param token     the signed access token; there is no refresh token
     * @param jti       its JWT id
     * @param expiresAt its {@code exp}, {@link #IMPERSONATION_TOKEN_VALIDITY} after minting
     */
    record IssuedImpersonationToken(
            @NonNull String token,
            @NonNull String jti,
            @NonNull Instant expiresAt) {}

    /**
     * Mints an impersonation token for the bound tenant (ADR-0062 §7, plan WS2b-4, decided
     * 2026-09-10): a platform operator's short-lived, read-only session inside a tenant.
     *
     * <p>The caller binds the <em>target</em> tenant first; as for every token this service signs,
     * {@code tid} is the bound tenant, and the token row is stored under it so that
     * {@link #validateToken} finds it there. Claims: {@code sub} = {@code subject} (the synthetic
     * principal, e.g. {@code support:admin.platform@acme}), {@code uid} = {@code operatorUserId}
     * (so downstream audit lineage names the human behind the request), {@code tid},
     * {@code username} = {@code subject}, {@code roles}, {@code perm_bits} / {@code perm_ver}
     * resolved from {@code roles} under the bound tenant, empty {@code loc_fin_bits} /
     * {@code loc_oth_bits} and no {@code loc_scope} (a support token is never location-scoped),
     * {@link #ACT} = {@code {sub, username}} of the operator, {@link #TOKEN_USE} =
     * {@link #TOKEN_USE_IMPERSONATION}, {@code iat}, {@code exp} = {@code iat} +
     * {@link #IMPERSONATION_TOKEN_VALIDITY}, {@code jti}. No refresh token is issued, no
     * assignment or location clamp applies (the lifetime is already the floor), and
     * {@link #refreshAccessToken} refuses the token.
     *
     * @param subject          the synthetic principal the token authenticates as
     * @param operatorUserId   the platform operator's user id
     * @param operatorUsername the platform operator's username
     * @param roles            the tenant roles whose grants make up {@code perm_bits}; the
     *                         {@code SUPPORT} template role in practice
     * @return the token, its id and its expiry
     * @throws com.positivity.securityservice.internal.exception.SecurityValidationException if
     *         {@code subject} is blank or {@code roles} is empty
     */
    @NonNull
    IssuedImpersonationToken generateImpersonationToken(
            @NonNull String subject,
            @NonNull UUID operatorUserId,
            @NonNull String operatorUsername,
            @NonNull Set<String> roles);

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
     * @throws com.positivity.securityservice.internal.exception.InvalidRefreshTokenException if the
     *         token is an impersonation token ({@link #TOKEN_USE_IMPERSONATION}): such a token is
     *         never refreshable, and the answer is 401 {@code INVALID_REFRESH_TOKEN}
     */
    TokenPair refreshAccessToken(@NonNull String refreshToken);
}
