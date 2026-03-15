package com.positivity.securityservice.internal.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import com.positivity.securityservice.internal.domain.PermissionBitsetCodec;
import com.positivity.securityservice.internal.dto.UserDto;
import com.positivity.securityservice.internal.entity.JwtToken;
import com.positivity.securityservice.internal.enums.PermissionCode;
import com.positivity.securityservice.internal.exception.InvalidRefreshTokenException;
import com.positivity.securityservice.internal.repository.JwtTokenRepository;
import com.positivity.securityservice.service.JwtService;
import com.positivity.securityservice.service.RoleAuthorityService;
import com.positivity.securityservice.service.UserService;
import com.positivity.shared.id.UUIDv7Generator;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service for handling JWT token operations such as generation, validation,
 * extraction, and deletion.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtServiceImpl implements JwtService {
    private final Clock clock;

    private static final long ACCESS_TOKEN_EXPIRATION_SECONDS = 3600L;
    private static final long REFRESH_TOKEN_EXPIRATION_SECONDS = 604800L;
    private static final String ISSUER = "pos-security-service";
    private static final String AUDIENCE = "api-gateway";

    private final JwtTokenRepository jwtTokenRepository;
    private final RoleAuthorityService roleAuthorityService;
    private final UserService userService;
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
    public String generateToken(@NonNull String username, @NonNull UUID userId, @NonNull Set<String> roles) {
        return generateTokenPair(username, userId, null, roles).accessToken();
    }

    @Override
    public boolean validateToken(@NonNull String token) {
        try {
            Jws<Claims> jws = jwtParser().parseSignedClaims(token);

            Claims claims = jws.getPayload();
            String jti = claims.getId();

            if (jti != null && tokenRevocationManager.isRevoked(jti)) {
                log.debug("Token validation failed: token is revoked. jti={}", jti);
                return false;
            }

            Instant expiresAt = claims.getExpiration().toInstant();
            if (expiresAt.isBefore(Instant.now(clock))) {
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
    public String getUsernameFromToken(@NonNull String token) {
        return getClaims(token)
                .getSubject();
    }

    @Override
    public UUID getUserIdFromToken(@NonNull String token) {
        Claims claims = getClaims(token);
        String uid = claims.get(UID, String.class);
        if (uid != null) {
            try {
                return UUID.fromString(uid);
            } catch (IllegalArgumentException ex) {
                log.debug("Invalid UUID value in 'uid' claim", ex);
                return null;
            }
        }

        String legacyUserId = claims.get(USER_ID, String.class);
        if (legacyUserId != null) {
            try {
                return UUID.fromString(legacyUserId);
            } catch (IllegalArgumentException ex) {
                log.debug("Invalid UUID value in legacy 'userId' claim", ex);
                return null;
            }
        }

        log.debug("JWT token does not contain 'uid' or legacy 'userId' claim");
        return null;
    }

    @Override
    public Set<String> getRolesFromToken(@NonNull String token) {
        Claims claims = getClaims(token);
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
    public Set<String> getAuthoritiesFromToken(@NonNull String token) {
        Claims claims = getClaims(token);

        String permBitsValue = claims.get(PERM_BITS, String.class);
        if (permBitsValue != null) {
            Object permVerRaw = claims.get(PERM_VER);
            int permVer = (permVerRaw instanceof Number n) ? n.intValue() : PermissionCode.CATALOG_VERSION;
            return PermissionBitsetCodec.decodeToPermissions(permBitsValue, permVer)
                    .stream()
                    .map(PermissionCode::code)
                    .collect(Collectors.toSet());
        }

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
    public boolean deleteToken(@NonNull String token) {
        Optional<JwtToken> existingToken = jwtTokenRepository.findByToken(token);
        if (existingToken.isEmpty()) {
            log.debug("Token deletion skipped: token not found in database");
            return false;
        }

        jwtTokenRepository.delete(existingToken.get());

        try {
            Claims claims = jwtParser().parseSignedClaims(token).getPayload();

            String jti = claims.getId();
            Instant expiresAt = claims.getExpiration().toInstant();

            if (jti != null && expiresAt != null) {
                long secondsUntilExpiry = ChronoUnit.SECONDS.between(Instant.now(clock), expiresAt);
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
    public void revokeTokenByJti(@NonNull String jti, long expirationSeconds) {
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
    @Transactional
    public void revokeAllTokensForUser(@NonNull String username) {
        List<JwtToken> tokens = jwtTokenRepository.findAllBySubject(username);
        for (JwtToken jwtToken : tokens) {
            revokeJtiIfTokenActive(jwtToken.getToken(), "access");
            revokeJtiIfTokenActive(jwtToken.getRefreshToken(), "refresh");
        }
        if (!tokens.isEmpty()) {
            jwtTokenRepository.deleteAll(tokens);
            log.debug("Revoked all tokens for user: username={}, count={}", username, tokens.size());
        }
    }

    private void revokeJtiIfTokenActive(@NonNull String token, @NonNull String tokenType) {
        try {
            Claims claims = jwtParser().parseSignedClaims(token).getPayload();
            String jti = claims.getId();
            if (jti == null) {
                return;
            }

            long secondsLeft = ChronoUnit.SECONDS.between(Instant.now(clock), claims.getExpiration().toInstant());
            if (secondsLeft > 0) {
                tokenRevocationManager.revokeToken(jti, secondsLeft);
            }
        } catch (JwtException e) {
            log.debug("Failed to revoke {} token JTI: error={}", tokenType, e.getClass().getSimpleName());
        }
    }

    @Override
    public TokenPair generateTokenPair(@NonNull String username, @NonNull UUID userId, @Nullable UUID personId, @NonNull Set<String> roles) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username cannot be blank");
        }
        if (userId == null) {
            throw new IllegalArgumentException("UserId cannot be null");
        }
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("Roles cannot be empty");
        }

        Instant now = Instant.now(clock);
        Instant accessExpiry = now.plusSeconds(ACCESS_TOKEN_EXPIRATION_SECONDS);
        Instant refreshExpiry = now.plusSeconds(REFRESH_TOKEN_EXPIRATION_SECONDS);

        String accessJti = UUIDv7Generator.generate().toString();
        String refreshJti = UUIDv7Generator.generate().toString();

        Set<String> expandedAuthorities = roleAuthorityService.expandRolesToAuthorities(roles);
        Set<PermissionCode> permCodes = expandedAuthorities.stream()
                .flatMap(authority -> PermissionCode.fromCode(authority).stream())
                .collect(Collectors.toUnmodifiableSet());
        String permBits = PermissionBitsetCodec.encode(permCodes);

        var accessBuilder = Jwts.builder()
                .id(accessJti)
                .subject(username)
            .issuer(ISSUER)
            .audience().add(AUDIENCE).and()
                .claim(UID, userId.toString())
                .claim(USERNAME, username)
                .claim(PERM_BITS, permBits)
                .claim(PERM_VER, PermissionCode.CATALOG_VERSION)
                .issuedAt(Date.from(now))
                .expiration(Date.from(accessExpiry))
                .signWith(secretKey);

        if (personId != null) {
            accessBuilder = accessBuilder.claim(PERSON_ID, personId.toString());
        }

        String accessToken = accessBuilder.compact();

        String refreshToken = Jwts.builder()
                .id(refreshJti)
                .subject(username)
                .claim(UID, userId.toString())
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

        log.debug("Generated token pair: username={}, userId={}, accessJti={}, refreshJti={}",
                username, userId, accessJti, refreshJti);

        return new TokenPair(accessToken, refreshToken);
    }

    @Override
    public boolean validateRefreshToken(@NonNull String refreshToken) {
        try {
            Jws<Claims> jws = jwtParser().parseSignedClaims(refreshToken);

            Claims claims = jws.getPayload();
            String jti = claims.getId();

            if (jti != null && tokenRevocationManager.isRevoked(jti)) {
                log.debug("Refresh token validation failed: token is revoked. jti={}", jti);
                return false;
            }

            Instant expiresAt = claims.getExpiration().toInstant();
            if (expiresAt.isBefore(Instant.now(clock))) {
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
    public TokenPair refreshAccessToken(@NonNull String refreshToken) {
        if (!validateRefreshToken(refreshToken)) {
            throw new IllegalArgumentException("Invalid refresh token");
        }

        Optional<JwtToken> stored = jwtTokenRepository.findByRefreshToken(refreshToken);
        if (stored.isEmpty()) {
            throw new IllegalArgumentException("Refresh token not found in database");
        }

        JwtToken jwtToken = stored.get();
        UUID userId = getUserIdFromToken(refreshToken);
        Optional<UserDto> userOpt = userService.getUserById(userId);
        if (userOpt.isEmpty()) {
            throw new InvalidRefreshTokenException("Refresh token references a user that no longer exists");
        }
        UserDto user = userOpt.get();
        Set<String> roles = user.getRoles() == null ? Collections.<String>emptySet() : user.getRoles();
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("User has no roles assigned");
        }
        String username = jwtToken.getSubject();

        try {
            Claims oldAccessClaims = jwtParser().parseSignedClaims(jwtToken.getToken()).getPayload();
            String oldAccessJti = oldAccessClaims.getId();
            if (oldAccessJti != null) {
                tokenRevocationManager.revokeToken(oldAccessJti, ACCESS_TOKEN_EXPIRATION_SECONDS);
            }
        } catch (JwtException e) {
            log.debug("Failed to extract JTI from old access token for revocation: error={}",
                    e.getClass().getSimpleName());
        }

        try {
            Claims oldRefreshClaims = jwtParser().parseSignedClaims(refreshToken).getPayload();
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

        return generateTokenPair(username, userId, user.getPersonId(), roles);
    }

    @Override
    public @Nullable UUID getPersonIdFromToken(@NonNull String token) {
        Claims claims = getClaims(token);
        String raw = claims.get(PERSON_ID, String.class);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            log.debug("Invalid UUID value in 'personId' claim", ex);
            return null;
        }
    }

    private JwtParser jwtParser() {
        return Jwts.parser()
                .verifyWith(secretKey)
                // TODO(ADR-0011): enable .requireIssuer(ISSUER).requireAudience(AUDIENCE)
                // after existing refresh tokens expire.
                .clock(() -> Date.from(Instant.now(clock)))
                .build();
    }

    private Claims getClaims(String token) {
        return jwtParser().parseSignedClaims(token).getPayload();
    }
}
