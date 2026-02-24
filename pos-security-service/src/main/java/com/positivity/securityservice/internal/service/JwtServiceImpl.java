package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.entity.JwtToken;
import com.positivity.securityservice.internal.repository.JwtTokenRepository;
import com.positivity.securityservice.service.JwtService;
import com.positivity.securityservice.service.RoleAuthorityService;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Service for handling JWT token operations such as generation, validation,
 * extraction, and deletion.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtServiceImpl implements JwtService {
    private static final long ACCESS_TOKEN_EXPIRATION_SECONDS = 3600L;
    private static final long REFRESH_TOKEN_EXPIRATION_SECONDS = 604800L;

    private final JwtTokenRepository jwtTokenRepository;
    private final RoleAuthorityService roleAuthorityService;
    private final TokenRevocationManager tokenRevocationManager;

    @Value("${security.jwt.secret}")
    private String jwtSecret;

    private SecretKey secretKey;

    /**
     * Post-construct initialization that validates and initializes the secret key.
     */
    @PostConstruct
    void initializeSecretKey() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "JWT secret must be provided via SECURITY_JWT_SECRET environment variable");
        }

        byte[] secretBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "JWT secret must be at least 32 characters (256 bits) for HMAC-SHA256 security. "
                            + "Current length: " + secretBytes.length + " bytes");
        }

        this.secretKey = new SecretKeySpec(
                secretBytes,
                0,
                secretBytes.length,
                "HmacSHA256");

        log.info("JwtService initialized with environment-injected JWT secret");
    }

    @Override
    public String generateToken(String username, String personId, Set<String> roles) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username cannot be blank");
        }
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("Roles cannot be empty");
        }
        String resolvedPersonId = resolvePersonId(username, personId);

        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(ACCESS_TOKEN_EXPIRATION_SECONDS);
        String jti = UUID.randomUUID().toString();
        Set<String> authorities = roleAuthorityService.expandRolesToAuthorities(roles);

        String token = Jwts.builder()
                .id(jti)
                .subject(username)
                .claim(PERSON_ID, resolvedPersonId)
                .claim(ROLES, roles)
                .claim(AUTHORITIES, authorities)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey)
                .compact();

        JwtToken jwtToken = new JwtToken();
        jwtToken.setToken(token);
        jwtToken.setIssuedAt(now);
        jwtToken.setExpiresAt(expiry);
        jwtToken.setSubject(username);
        jwtTokenRepository.save(jwtToken);

        log.debug("Generated JWT token: username={}, personId={}, jti={}, expiresAt={}",
                username, resolvedPersonId, jti, expiry);

        return token;
    }

    @Override
    public boolean validateToken(String token) {
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);

            Claims claims = jws.getPayload();
            String jti = claims.getId();

            if (jti != null && tokenRevocationManager.isRevoked(jti)) {
                log.debug("Token validation failed: token is revoked. jti={}", jti);
                return false;
            }

            Instant expiresAt = claims.getExpiration().toInstant();
            if (expiresAt.isBefore(Instant.now())) {
                log.debug("Token validation failed: token is expired. jti={}", jti);
                return false;
            }

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

    @Override
    public String getUsernameFromToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    @Override
    public String getPersonIdFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        String personId = claims.get(PERSON_ID, String.class);
        if (personId != null && !personId.isBlank()) {
            return personId;
        }
        return claims.getSubject();
    }

    @Override
    public Set<String> getRolesFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token).getPayload();
        Object rolesObj = claims.get(ROLES);
        if (rolesObj instanceof List<?> rolesList) {
            Set<String> roles = new HashSet<>();
            for (Object role : rolesList) {
                if (role instanceof String str) {
                    roles.add(str);
                }
            }
            return roles;
        }
        return Collections.emptySet();
    }

    @Override
    public Set<String> getAuthoritiesFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token).getPayload();
        Object authObj = claims.get(AUTHORITIES);
        if (authObj instanceof List<?> list) {
            Set<String> authorities = new HashSet<>();
            for (Object a : list) {
                if (a instanceof String str) {
                    authorities.add(str);
                }
            }
            return authorities;
        }
        return roleAuthorityService.expandRolesToAuthorities(getRolesFromToken(token));
    }

    @Override
    @Transactional
    public boolean deleteToken(String token) {
        Optional<JwtToken> existingToken = jwtTokenRepository.findByToken(token);
        if (existingToken.isEmpty()) {
            log.debug("Token deletion skipped: token not found in database");
            return false;
        }

        jwtTokenRepository.delete(existingToken.get());

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
            log.warn("Token deleted from DB but failed to revoke in Redis: error={}",
                    e.getClass().getSimpleName());
        }

        return true;
    }

    @Override
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

    @Override
    public TokenPair generateTokenPair(String username, String personId, Set<String> roles) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username cannot be blank");
        }
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("Roles cannot be empty");
        }
        String resolvedPersonId = resolvePersonId(username, personId);

        Instant now = Instant.now();
        Instant accessExpiry = now.plusSeconds(ACCESS_TOKEN_EXPIRATION_SECONDS);
        Instant refreshExpiry = now.plusSeconds(REFRESH_TOKEN_EXPIRATION_SECONDS);

        String accessJti = UUID.randomUUID().toString();
        String refreshJti = UUID.randomUUID().toString();

        Set<String> authorities = roleAuthorityService.expandRolesToAuthorities(roles);

        String accessToken = Jwts.builder()
                .id(accessJti)
                .subject(username)
                .claim(PERSON_ID, resolvedPersonId)
                .claim(ROLES, roles)
                .claim(AUTHORITIES, authorities)
                .issuedAt(Date.from(now))
                .expiration(Date.from(accessExpiry))
                .signWith(secretKey)
                .compact();

        String refreshToken = Jwts.builder()
                .id(refreshJti)
                .subject(username)
                .claim(PERSON_ID, resolvedPersonId)
                .claim("type", "refresh")
                .issuedAt(Date.from(now))
                .expiration(Date.from(refreshExpiry))
                .signWith(secretKey)
                .compact();

        JwtToken jwtToken = new JwtToken();
        jwtToken.setToken(accessToken);
        jwtToken.setRefreshToken(refreshToken);
        jwtToken.setIssuedAt(now);
        jwtToken.setExpiresAt(accessExpiry);
        jwtToken.setRefreshExpiresAt(refreshExpiry);
        jwtToken.setSubject(username);
        jwtTokenRepository.save(jwtToken);

        log.debug("Generated token pair: username={}, personId={}, accessJti={}, refreshJti={}",
                username, resolvedPersonId, accessJti, refreshJti);

        return new TokenPair(accessToken, refreshToken);
    }

    @Override
    public boolean validateRefreshToken(String refreshToken) {
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(refreshToken);

            Claims claims = jws.getPayload();
            String jti = claims.getId();

            if (jti != null && tokenRevocationManager.isRevoked(jti)) {
                log.debug("Refresh token validation failed: token is revoked. jti={}", jti);
                return false;
            }

            Instant expiresAt = claims.getExpiration().toInstant();
            if (expiresAt.isBefore(Instant.now())) {
                log.debug("Refresh token validation failed: token is expired. jti={}", jti);
                return false;
            }

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

    @Override
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

        jwtTokenRepository.delete(jwtToken);

        log.debug("Refreshed token pair: username={}", username);

        String personId = getPersonIdFromToken(refreshToken);
        return generateTokenPair(username, personId, roles);
    }

    private String resolvePersonId(String username, String personId) {
        if (personId == null || personId.isBlank()) {
            log.warn("JWT generation without personId claim for subject '{}'; falling back to subject", username);
            return username;
        }
        return personId;
    }
}
