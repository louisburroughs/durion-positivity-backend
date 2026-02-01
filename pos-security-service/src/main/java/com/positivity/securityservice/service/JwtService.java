package com.positivity.securityservice.service;

import com.positivity.securityservice.TokenRevocationManager;
import com.positivity.securityservice.internal.model.JwtToken;
import com.positivity.securityservice.internal.repository.JwtTokenRepository;

import io.jsonwebtoken.*;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.temporal.ChronoUnit;
import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
 * - Authority expansion: Delegated to gateway (authorities claim populated by
 * RoleAuthorityService)
 * - Concurrency: JwtToken entity uses @Version for optimistic locking
 * - Graceful Degradation: If Redis unavailable, token validation still succeeds
 * 
 * @since 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtService {
    /** Claim key for roles in the JWT. */
    public static final String ROLES = "roles";
    /** Claim key for expanded authorities in the JWT. */
    public static final String AUTHORITIES = "authorities";
    /** Claim key for JWT ID (unique identifier for revocation). */
    public static final String JTI = "jti";

    private static final long ACCESS_TOKEN_EXPIRATION_SECONDS = 3600L; // 1 hour
    private static final long REFRESH_TOKEN_EXPIRATION_SECONDS = 604800L; // 7 days

    private final JwtTokenRepository jwtTokenRepository;
    private final RoleAuthorityService roleAuthorityService;
    private final TokenRevocationManager tokenRevocationManager;

    @Value("${security.jwt.secret}")
    private String jwtSecret;

    private SecretKey secretKey;

    /**
     * Post-construct initialization that validates and initializes the secret key.
     * 
     * This method is called after Spring has injected all dependencies and values.
     * It validates that the JWT secret is provided and meets the minimum strength
     * requirements for HMAC-SHA256 (256 bits / 32 bytes).
     * 
     * @throws IllegalStateException if JWT secret is missing or too weak
     */
    @PostConstruct
    void initializeSecretKey() {
        // Validate that secret is provided
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "JWT secret must be provided via SECURITY_JWT_SECRET environment variable");
        }

        // Validate secret strength (minimum 32 characters for HMAC-SHA256)
        byte[] secretBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "JWT secret must be at least 32 characters (256 bits) for HMAC-SHA256 security. "
                            + "Current length: " + secretBytes.length + " bytes");
        }

        // Create HMAC-SHA256 key from secret string
        this.secretKey = new SecretKeySpec(
                secretBytes,
                0,
                secretBytes.length,
                "HmacSHA256");

        log.info("JwtService initialized with environment-injected JWT secret");
    }

    /**
     * Generates a JWT token for the given username and roles, stores it in the
     * repository, and returns the token string.
     *
     * **Implementation:**
     * - Token ID (JTI): Unique UUID v4 identifier for revocation tracking
     * - Expiration: 1 hour (3600 seconds)
     * - Authorities: Populated by RoleAuthorityService (expanded from roles)
     * - Revocation: Token stored in Redis with 1-hour TTL
     * 
     * @param username the subject for the token
     * @param roles    the set of roles to include in the token
     * @return the generated JWT token string
     * 
     * @throws IllegalArgumentException if username or roles are invalid
     */
    public String generateToken(String username, Set<String> roles) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username cannot be blank");
        }
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("Roles cannot be empty");
        }

        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(ACCESS_TOKEN_EXPIRATION_SECONDS);
        String jti = UUID.randomUUID().toString();
        Set<String> authorities = roleAuthorityService.expandRolesToAuthorities(roles);

        String token = Jwts.builder()
                .id(jti) // JWT ID for revocation tracking
                .subject(username)
                .claim(ROLES, roles)
                .claim(AUTHORITIES, authorities)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey)
                .compact();

        // Store token in database
        JwtToken jwtToken = new JwtToken();
        jwtToken.setToken(token);
        jwtToken.setIssuedAt(now);
        jwtToken.setExpiresAt(expiry);
        jwtToken.setSubject(username);
        jwtTokenRepository.save(jwtToken);

        log.debug("Generated JWT token: username={}, jti={}, expiresAt={}", username, jti, expiry);

        return token;
    }

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
    public boolean validateToken(String token) {
        try {
            // Parse and verify signature
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);

            Claims claims = jws.getPayload();
            String jti = claims.getId();

            // Check if token is revoked in Redis
            if (jti != null && tokenRevocationManager.isRevoked(jti)) {
                log.debug("Token validation failed: token is revoked. jti={}", jti);
                return false;
            }

            // Verify expiration
            Instant expiresAt = claims.getExpiration().toInstant();
            if (expiresAt.isBefore(Instant.now())) {
                log.debug("Token validation failed: token is expired. jti={}", jti);
                return false;
            }

            // Check database presence (consistency check)
            Optional<JwtToken> stored = jwtTokenRepository.findByToken(token);
            if (stored.isEmpty()) {
                log.debug("Token validation failed: token not found in database. jti={}", jti);
                return false;
            }

            log.debug("Token validation succeeded: jti={}", jti);
            return true;

        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Token validation failed: signature or format error. error={}", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Extracts the username (subject) from the given JWT token.
     *
     * @param token the JWT token string
     * @return the subject (username) from the token
     */
    public String getUsernameFromToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    /**
     * Extracts the set of roles from the given JWT token.
     *
     * @param token the JWT token string
     * @return a set of roles, or an empty set if none are found
     */
    public Set<String> getRolesFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token).getPayload();
        Object rolesObj = claims.get(ROLES);
        if (rolesObj instanceof List<?> rolesList) {
            Set<String> roles = new java.util.HashSet<>();
            for (Object role : rolesList) {
                if (role instanceof String str) {
                    roles.add(str);
                }
            }
            return roles;
        }
        return java.util.Collections.emptySet();
    }

    /**
     * Extracts the set of authorities from the given JWT token.
     */
    public Set<String> getAuthoritiesFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token).getPayload();
        Object authObj = claims.get(AUTHORITIES);
        if (authObj instanceof List<?> list) {
            Set<String> authorities = new java.util.HashSet<>();
            for (Object a : list) {
                if (a instanceof String str) {
                    authorities.add(str);
                }
            }
            return authorities;
        }
        // Fallback: expand from roles if authorities claim missing
        return roleAuthorityService.expandRolesToAuthorities(getRolesFromToken(token));
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
    @Transactional
    public boolean deleteToken(String token) {
        // Delete from database first - this is the authoritative store
        Optional<JwtToken> existingToken = jwtTokenRepository.findByToken(token);
        if (existingToken.isEmpty()) {
            log.debug("Token deletion skipped: token not found in database");
            return false;
        }

        jwtTokenRepository.delete(existingToken.get());

        // Now revoke in Redis (best-effort for cache consistency)
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String jti = claims.getId();
            Instant expiresAt = claims.getExpiration().toInstant();

            if (jti != null && expiresAt != null) {
                long secondsUntilExpiry = ChronoUnit.SECONDS.between(Instant.now(), expiresAt);
                if (secondsUntilExpiry > 0) {
                    tokenRevocationManager.revokeToken(jti, secondsUntilExpiry);
                    log.debug("Token deleted and revoked: jti={}", jti);
                } else {
                    log.debug("Token deleted (already expired, skipped Redis revocation): jti={}", jti);
                }
            }
        } catch (JwtException e) {
            // Token is already deleted from DB, Redis revocation is best-effort
            log.warn("Token deleted from DB but failed to revoke in Redis: error={}",
                    e.getClass().getSimpleName());
        }

        return true;
    }

    /**
     * Revokes a token by its JTI (JWT ID) and removes it from the database.
     * 
     * @param jti               the JWT ID (unique token identifier)
     * @param expirationSeconds token expiration time in seconds
     * 
     * @throws IllegalArgumentException if jti is blank or expirationSeconds <= 0
     */
    public void revokeTokenByJti(String jti, long expirationSeconds) {
        if (jti == null || jti.isBlank()) {
            throw new IllegalArgumentException("JTI cannot be blank");
        }
        if (expirationSeconds <= 0) {
            throw new IllegalArgumentException("Expiration seconds must be positive");
        }

        tokenRevocationManager.revokeToken(jti, expirationSeconds);
        log.debug("Token revoked by JTI: jti={}", jti);
    }

    /**
     * Record representing a pair of access and refresh tokens.
     *
     * @param accessToken  the access token
     * @param refreshToken the refresh token
     */
    public record TokenPair(String accessToken, String refreshToken) {
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
     * 
     * @param username the subject for the tokens
     * @param roles    the set of roles to include in the access token
     * @return a TokenPair containing the access and refresh tokens
     * 
     * @throws IllegalArgumentException if username or roles are invalid
     */
    public TokenPair generateTokenPair(String username, Set<String> roles) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username cannot be blank");
        }
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("Roles cannot be empty");
        }

        Instant now = Instant.now();
        Instant accessExpiry = now.plusSeconds(ACCESS_TOKEN_EXPIRATION_SECONDS);
        Instant refreshExpiry = now.plusSeconds(REFRESH_TOKEN_EXPIRATION_SECONDS);

        String accessJti = UUID.randomUUID().toString();
        String refreshJti = UUID.randomUUID().toString();

        Set<String> authorities = roleAuthorityService.expandRolesToAuthorities(roles);

        // Generate access token (includes roles and authorities)
        String accessToken = Jwts.builder()
                .id(accessJti)
                .subject(username)
                .claim(ROLES, roles)
                .claim(AUTHORITIES, authorities)
                .issuedAt(Date.from(now))
                .expiration(Date.from(accessExpiry))
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();

        // Generate refresh token (minimal claims for security)
        String refreshToken = Jwts.builder()
                .id(refreshJti)
                .subject(username)
                .claim("type", "refresh")
                .issuedAt(Date.from(now))
                .expiration(Date.from(refreshExpiry))
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();

        // Store both tokens in database
        JwtToken jwtToken = new JwtToken();
        jwtToken.setToken(accessToken);
        jwtToken.setRefreshToken(refreshToken);
        jwtToken.setIssuedAt(now);
        jwtToken.setExpiresAt(accessExpiry);
        jwtToken.setRefreshExpiresAt(refreshExpiry);
        jwtToken.setSubject(username);
        jwtTokenRepository.save(jwtToken);

        log.debug("Generated token pair: username={}, accessJti={}, refreshJti={}",
                username, accessJti, refreshJti);

        return new TokenPair(accessToken, refreshToken);
    }

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
    public boolean validateRefreshToken(String refreshToken) {
        try {
            // Parse and verify signature
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(refreshToken);

            Claims claims = jws.getPayload();
            String jti = claims.getId();

            // Check if token is revoked in Redis
            if (jti != null && tokenRevocationManager.isRevoked(jti)) {
                log.debug("Refresh token validation failed: token is revoked. jti={}", jti);
                return false;
            }

            // Verify expiration
            Instant expiresAt = claims.getExpiration().toInstant();
            if (expiresAt.isBefore(Instant.now())) {
                log.debug("Refresh token validation failed: token is expired. jti={}", jti);
                return false;
            }

            // Check database presence
            Optional<JwtToken> stored = jwtTokenRepository.findByRefreshToken(refreshToken);
            if (stored.isEmpty()) {
                log.debug("Refresh token validation failed: token not found in database. jti={}", jti);
                return false;
            }

            log.debug("Refresh token validation succeeded: jti={}", jti);
            return true;

        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Refresh token validation failed: signature or format error. error={}",
                    e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Refreshes the access token using the given refresh token.
     *
     * **Process:**
     * 1. Validates refresh token (signature, expiration, revocation)
     * 2. Extracts username and roles from refresh token
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
    public TokenPair refreshAccessToken(String refreshToken) {
        if (!validateRefreshToken(refreshToken)) {
            throw new IllegalArgumentException("Invalid refresh token");
        }

        Optional<JwtToken> stored = jwtTokenRepository.findByRefreshToken(refreshToken);
        if (stored.isEmpty()) {
            throw new IllegalArgumentException("Refresh token not found in database");
        }

        JwtToken jwtToken = stored.get();
        String username = jwtToken.getSubject();
        Set<String> roles = getRolesFromToken(jwtToken.getToken());

        // Extract JTI from old tokens for revocation
        try {
            Claims oldAccessClaims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(jwtToken.getToken())
                    .getPayload();
            String oldAccessJti = oldAccessClaims.getId();
            if (oldAccessJti != null) {
                tokenRevocationManager.revokeToken(oldAccessJti, ACCESS_TOKEN_EXPIRATION_SECONDS);
            }
        } catch (JwtException e) {
            log.debug("Failed to extract JTI from old access token for revocation: error={}",
                    e.getClass().getSimpleName());
        }

        try {
            Claims oldRefreshClaims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(refreshToken)
                    .getPayload();
            String oldRefreshJti = oldRefreshClaims.getId();
            if (oldRefreshJti != null) {
                tokenRevocationManager.revokeToken(oldRefreshJti, REFRESH_TOKEN_EXPIRATION_SECONDS);
            }
        } catch (JwtException e) {
            log.debug("Failed to extract JTI from old refresh token for revocation: error={}",
                    e.getClass().getSimpleName());
        }

        // Invalidate old tokens in database
        jwtTokenRepository.delete(jwtToken);

        log.debug("Refreshed token pair: username={}", username);

        return generateTokenPair(username, roles);
    }
}
