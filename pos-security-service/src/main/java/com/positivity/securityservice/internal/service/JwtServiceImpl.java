package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.domain.LocationScopeBits;
import com.positivity.securityservice.internal.domain.PermissionBitsetCodec;
import com.positivity.securityservice.internal.dto.UserDto;
import com.positivity.securityservice.internal.entity.JwtToken;
import com.positivity.securityservice.internal.enums.PermissionCode;
import com.positivity.securityservice.internal.exception.InvalidRefreshTokenException;
import com.positivity.securityservice.internal.exception.NoRolesAssignedException;
import com.positivity.securityservice.internal.exception.SecurityValidationException;
import com.positivity.securityservice.internal.repository.JwtTokenRepository;
import com.positivity.securityservice.internal.security.service.JwtService;
import com.positivity.shared.id.UUIDv7Generator;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.core.userdetails.UserDetailsChecker;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Enforces {@code isEnabled()}, {@code isAccountNonLocked()}, {@code isAccountNonExpired()} and
     * {@code isCredentialsNonExpired()} on the refresh path (#1803), the same checker
     * {@code JwtAuthenticationFilter} runs on the bearer path. Stateless and thread-safe.
     */
    private static final UserDetailsChecker ACCOUNT_STATUS_CHECKER = new AccountStatusUserDetailsChecker();

    private final JwtTokenRepository jwtTokenRepository;
    private final RoleAuthorityService roleAuthorityService;
    private final UserService userService;
    private final TokenRevocationManager tokenRevocationManager;
    private final UserDetailsService userDetailsService;
    private final StaffingAssignmentProjectionService staffingAssignmentProjectionService;

    @Value("${security.jwt.secret}")
    private String jwtSecret;

    private SecretKey secretKey;

    /**
     * Post-construct initialization that validates and initializes the secret key.
     */
    @PostConstruct
    void initializeSecretKey() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException("JWT secret must be provided via SECURITY_JWT_SECRET environment variable");
        }

        byte[] secretBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "JWT secret must be at least 32 characters (256 bits) for HMAC-SHA256 security. "
                            + "Current length: " + secretBytes.length + " bytes");
        }

        this.secretKey = new SecretKeySpec(secretBytes, 0, secretBytes.length, "HmacSHA256");

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
            log.debug(
                    "Token validation failed: signature or format error. error={}",
                    e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public String getUsernameFromToken(@NonNull String token) {
        return getClaims(token).getSubject();
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
                    String normalizedRole = normalizeRoleClaim(str);
                    if (!normalizedRole.isBlank()) {
                        roles.add(normalizedRole);
                    }
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
            return PermissionBitsetCodec.decodeToPermissions(permBitsValue, permissionCatalogVersion(claims)).stream()
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
            log.warn(
                    "Token deleted from DB but failed to revoke in Redis: error={}",
                    e.getClass().getSimpleName());
        }

        return true;
    }

    // (d) defensive/internal: revokeTokenByJti is declared on the JwtService interface but has
    // no caller in this module today (no controller or other service invokes it), so these
    // guards are not reachable from an HTTP request. Left as IllegalArgumentException.
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

            long secondsLeft = ChronoUnit.SECONDS.between(
                    Instant.now(clock), claims.getExpiration().toInstant());
            if (secondsLeft > 0) {
                tokenRevocationManager.revokeToken(jti, secondsLeft);
            }
        } catch (JwtException e) {
            log.debug(
                    "Failed to revoke {} token JTI: error={}",
                    tokenType,
                    e.getClass().getSimpleName());
        }
    }

    @Override
    public TokenPair generateTokenPair(
            @NonNull String username, @NonNull UUID userId, @Nullable UUID personId, @NonNull Set<String> roles) {
        // Internal token-issuance endpoints (client-supplied roles, no resolved user) get no
        // assignment clamp — see the interface javadoc.
        return generateTokenPair(username, userId, personId, roles, null);
    }

    @Override
    public TokenPair generateTokenPair(
            @NonNull String username,
            @NonNull UUID userId,
            @Nullable UUID personId,
            @NonNull Set<String> roles,
            @Nullable Instant grantsExpireAt) {
        if (username == null || username.isBlank()) {
            throw new SecurityValidationException("Username cannot be blank");
        }
        if (userId == null) {
            throw new SecurityValidationException("UserId cannot be null");
        }
        if (roles == null || roles.isEmpty()) {
            // (a) request-shape validation: JwtController's issueInternalToken and
            // generateTokenPair pass a client-supplied `roles` set straight through, and both
            // document "Returns 400 ... or the role set is empty". The login flow
            // (AuthenticationServiceImpl) rejects an account with no roles before calling this
            // method (403 USER_HAS_NO_ROLES, ADR-0017 §2 / #1725), so this guard only ever sees
            // a caller-supplied empty set.
            throw new SecurityValidationException("Roles cannot be empty");
        }

        Instant now = Instant.now(clock);
        Instant refreshExpiry = now.plusSeconds(REFRESH_TOKEN_EXPIRATION_SECONDS);

        String accessJti = UUIDv7Generator.generate().toString();
        String refreshJti = UUIDv7Generator.generate().toString();

        Set<String> expandedAuthorities = roleAuthorityService.expandRolesToAuthorities(roles);
        Set<PermissionCode> permCodes = expandedAuthorities.stream()
                .flatMap(authority -> PermissionCode.fromCode(authority).stream())
                .collect(Collectors.toUnmodifiableSet());
        String permBits = PermissionBitsetCodec.encode(permCodes);
        List<String> roleClaims = roles.stream()
                .map(this::normalizeRoleClaim)
                .filter(role -> !role.isBlank())
                .distinct()
                .sorted()
                .toList();
        if (roleClaims.isEmpty()) {
            throw new SecurityValidationException("Roles cannot be blank");
        }

        // ADR-0061 §2: the scope bitsets are composed per role, so a permission an ALL role grants
        // stays global even when a LOCATION role grants it too. perm_bits above is untouched.
        LocationScopeBits scopeBits = LocationScopeBits.compose(roleAuthorityService.resolveRoleGrants(roles));
        LocationReach reach = resolveLocationReach(scopeBits, personId, now);
        Instant accessExpiry = reach.clampedExpiry();

        // ADR-0061 §4 amendment (2026-09-09, #1914 phase 3): exp is the minimum of every
        // applicable bound — the natural 3600s lifetime, the location-reach clamp above, and now
        // the earliest end of a role assignment perm_bits was built from — floored at now so a
        // stale or racing bound can never produce an already-expired token.
        if (grantsExpireAt != null && grantsExpireAt.isBefore(accessExpiry)) {
            accessExpiry = grantsExpireAt;
        }
        if (accessExpiry.isBefore(now)) {
            accessExpiry = now;
        }

        var accessBuilder = Jwts.builder()
                .id(accessJti)
                .subject(username)
                .issuer(ISSUER)
                .audience()
                .add(AUDIENCE)
                .and()
                .claim(UID, userId.toString())
                .claim(USERNAME, username)
                .claim(ROLES, roleClaims)
                .claim(PERM_BITS, permBits)
                .claim(PERM_VER, PermissionCode.CATALOG_VERSION)
                .claim(LOC_FIN_BITS, PermissionBitsetCodec.encode(scopeBits.financial()))
                .claim(LOC_OTH_BITS, PermissionBitsetCodec.encode(scopeBits.other()))
                .issuedAt(Date.from(now))
                .expiration(Date.from(accessExpiry))
                .signWith(secretKey);

        if (personId != null) {
            accessBuilder = accessBuilder.claim(PERSON_ID, personId.toString());
        }
        if (!reach.nodes().isEmpty()) {
            accessBuilder = accessBuilder.claim(LOC_SCOPE, locationScopeClaim(reach.nodes()));
        }

        String accessToken = accessBuilder.compact();

        String refreshToken = Jwts.builder()
                .id(refreshJti)
                .subject(username)
                .issuer(ISSUER)
                .audience()
                .add(AUDIENCE)
                .and()
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

        log.debug(
                "Generated token pair: username={}, userId={}, accessJti={}, refreshJti={}",
                username,
                userId,
                accessJti,
                refreshJti);

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
            log.debug(
                    "Refresh token validation failed: signature or format error. error={}",
                    e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public TokenPair refreshAccessToken(@NonNull String refreshToken) {
        if (!validateRefreshToken(refreshToken)) {
            throw new SecurityValidationException("Invalid refresh token");
        }

        Optional<JwtToken> stored = jwtTokenRepository.findByRefreshToken(refreshToken);
        if (stored.isEmpty()) {
            throw new SecurityValidationException("Refresh token not found in database");
        }

        JwtToken jwtToken = stored.get();
        UUID userId = getUserIdFromToken(refreshToken);
        Optional<UserDto> userOpt = userService.getUserById(userId);
        if (userOpt.isEmpty()) {
            throw new InvalidRefreshTokenException("Refresh token references a user that no longer exists");
        }
        UserDto user = userOpt.get();
        // /v1/auth/refresh is permitAll and carries the refresh token in the body, so
        // JwtAuthenticationFilter — and the account-state check it runs on bearer tokens — never
        // sees this request. LockoutServiceImpl locks an account without revoking its tokens, so
        // without this check a locked-out account could rotate a refresh token into a fresh access
        // token (#1803). The checker throws LockedException / DisabledException /
        // AccountExpiredException / CredentialsExpiredException, which GlobalExceptionHandler
        // answers as 401 ACCOUNT_LOCKED / ACCOUNT_DISABLED / ACCOUNT_EXPIRED / CREDENTIALS_EXPIRED —
        // the same explicit answers the credential login path gives. That is deliberate and differs
        // from the bearer path, where the filter stays generic (INVALID_CREDENTIALS): a
        // refresh-token holder is a credential-equivalent caller, not an anonymous bearer.
        final org.springframework.security.core.userdetails.UserDetails account;
        try {
            account = userDetailsService.loadUserByUsername(user.getUsername());
        } catch (UsernameNotFoundException raced) {
            // The row resolved by id a moment ago and is gone (or renamed) now. It is the same
            // condition the isEmpty() branch above answers, so it answers the same code
            // (ADR-0017 §2: one condition, one code) instead of falling through to the entry
            // point's generic INVALID_CREDENTIALS.
            throw new InvalidRefreshTokenException("Refresh token references a user that no longer exists", raced);
        }
        ACCOUNT_STATUS_CHECKER.check(account);
        Set<String> roles = user.getRoles() == null ? Collections.<String>emptySet() : user.getRoles();
        if (roles.isEmpty()) {
            // ADR-0017 §2 (question 1): the refresh token is well-formed, unexpired, unrevoked,
            // and present in the token store — the refusal is about the caller's authorization
            // (an account with no roles holds no effective permissions), so it answers 403
            // USER_HAS_NO_ROLES, the same status the login path gives for the same condition
            // (#1725). Distinct from generateTokenPair's "Roles cannot be empty" above, which is
            // request-shape validation of a client-supplied set.
            throw new NoRolesAssignedException("User has no roles assigned");
        }
        String username = jwtToken.getSubject();

        try {
            Claims oldAccessClaims =
                    jwtParser().parseSignedClaims(jwtToken.getToken()).getPayload();
            String oldAccessJti = oldAccessClaims.getId();
            if (oldAccessJti != null) {
                tokenRevocationManager.revokeToken(oldAccessJti, ACCESS_TOKEN_EXPIRATION_SECONDS);
            }
        } catch (JwtException e) {
            log.debug(
                    "Failed to extract JTI from old access token for revocation: error={}",
                    e.getClass().getSimpleName());
        }

        try {
            Claims oldRefreshClaims =
                    jwtParser().parseSignedClaims(refreshToken).getPayload();
            String oldRefreshJti = oldRefreshClaims.getId();
            if (oldRefreshJti != null) {
                tokenRevocationManager.revokeToken(oldRefreshJti, REFRESH_TOKEN_EXPIRATION_SECONDS);
            }
        } catch (JwtException e) {
            log.debug(
                    "Failed to extract JTI from old refresh token for revocation: error={}",
                    e.getClass().getSimpleName());
        }

        jwtTokenRepository.delete(jwtToken);

        log.debug("Refreshed token pair: username={}", username);

        // ADR-0061 §4 amendment (2026-09-09, #1914 phase 3): re-resolved on every refresh, exactly
        // like the location-reach clamp above it — a refresh after an assignment ended simply no
        // longer carries that role (roles, above), and a refresh while a bounded assignment is
        // still effective re-applies the clamp against its current end date.
        Instant grantsExpireAt = userService.getGrantsExpireAt(userId).orElse(null);
        return generateTokenPair(username, userId, user.getPersonId(), roles, grantsExpireAt);
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

    @Override
    public @Nullable String getJtiFromToken(@NonNull String token) {
        return getClaims(token).getId();
    }

    @Override
    public Set<String> getFinancialLocationScopedPermissionsFromToken(@NonNull String token) {
        return decodeScopedPermissions(getClaims(token), LOC_FIN_BITS);
    }

    @Override
    public Set<String> getOtherLocationScopedPermissionsFromToken(@NonNull String token) {
        return decodeScopedPermissions(getClaims(token), LOC_OTH_BITS);
    }

    @Override
    public Optional<LocationScopeClaim> getLocationScopeFromToken(@NonNull String token) {
        Object raw = getClaims(token).get(LOC_SCOPE);
        if (raw == null) {
            return Optional.empty();
        }
        // A token this service signed carries the shape this service wrote; anything else is a
        // defect to surface, not a state to quietly read as "no scope" (which would still deny)
        // or, worse, as unrestricted reach.
        if (!(raw instanceof Map<?, ?> object)) {
            throw new SecurityValidationException("Malformed loc_scope claim: expected an object");
        }
        Object version = object.get("v");
        if (!(version instanceof Number number) || number.intValue() != LOC_SCOPE_VERSION) {
            throw new SecurityValidationException(
                    "Unsupported loc_scope version: " + version + " (expected " + LOC_SCOPE_VERSION + ")");
        }
        if (!(object.get("nodes") instanceof List<?> rawNodes)) {
            throw new SecurityValidationException("Malformed loc_scope claim: nodes must be a list");
        }
        List<UUID> nodes = new ArrayList<>(rawNodes.size());
        for (Object node : rawNodes) {
            try {
                nodes.add(UUID.fromString(String.valueOf(node)));
            } catch (IllegalArgumentException ex) {
                throw new SecurityValidationException("Malformed loc_scope node id: " + node, ex);
            }
        }
        return Optional.of(new LocationScopeClaim(LOC_SCOPE_VERSION, nodes));
    }

    private Set<String> decodeScopedPermissions(Claims claims, String claimName) {
        String bits = claims.get(claimName, String.class);
        if (bits == null || bits.isBlank()) {
            return Collections.emptySet();
        }
        return PermissionBitsetCodec.decodeToPermissions(bits, permissionCatalogVersion(claims)).stream()
                .map(PermissionCode::code)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static int permissionCatalogVersion(Claims claims) {
        Object permVerRaw = claims.get(PERM_VER);
        return (permVerRaw instanceof Number n) ? n.intValue() : PermissionCode.CATALOG_VERSION;
    }

    /**
     * The assigned nodes contributing to a token and the access expiry after the effective-dating
     * clamp (ADR-0061 §2/§4).
     *
     * @param nodes         assigned location node ids, verbatim; empty when the caller holds no
     *                      location-scoped grant, has no person, or has no effective assignment
     * @param clampedExpiry {@code min(now + ACCESS_TOKEN_EXPIRATION_SECONDS, end of the day the
     *                      earliest contributing assignment ends)}; the unclamped value whenever
     *                      the clamp does not apply
     */
    private record LocationReach(List<UUID> nodes, Instant clampedExpiry) {}

    private LocationReach resolveLocationReach(LocationScopeBits scopeBits, @Nullable UUID personId, Instant now) {
        Instant accessExpiry = now.plusSeconds(ACCESS_TOKEN_EXPIRATION_SECONDS);
        if (scopeBits.isEmpty()) {
            // No location-scoped grant: no projection lookup, no clamp — the common case is
            // byte-for-byte what it was before ADR-0061, apart from two empty bitset claims.
            return new LocationReach(List.of(), accessExpiry);
        }
        if (personId == null) {
            // Internal-token path (no person). Fail closed: bitsets are emitted, loc_scope is not.
            log.debug("Location-scoped grants with no personId; loc_scope omitted (fail closed)");
            return new LocationReach(List.of(), accessExpiry);
        }

        ZoneId zone = clock.getZone();
        LocalDate today = LocalDate.ofInstant(now, zone);
        List<UUID> nodes = staffingAssignmentProjectionService.assignedLocationIds(personId, today);
        if (nodes.isEmpty()) {
            // ADR-0061 §2: absence must never widen to unrestricted reach, so loc_scope is omitted
            // rather than substituted with ALL. The bitsets still say which grants are scoped.
            log.info(
                    "Location-scoped grants but no effective staffing assignment: personId={} asOf={};"
                            + " loc_scope omitted (fail closed)",
                    personId,
                    today);
            return new LocationReach(List.of(), accessExpiry);
        }

        // ADR-0061 §4: effective_to is an inclusive date, so the token may live to the end of that
        // day in the issuer's zone, and no longer. A refresh re-enters here and re-evaluates.
        Optional<LocalDate> earliestEnd = staffingAssignmentProjectionService.earliestEffectiveTo(personId, today);
        if (earliestEnd.isPresent()) {
            Instant endOfDay = earliestEnd.get().plusDays(1).atStartOfDay(zone).toInstant();
            if (endOfDay.isBefore(accessExpiry)) {
                accessExpiry = endOfDay;
            }
        }
        return new LocationReach(nodes, accessExpiry);
    }

    /** {@code {"v":1,"nodes":[...]}} — insertion-ordered so the serialised token is deterministic. */
    private static Map<String, Object> locationScopeClaim(List<UUID> nodes) {
        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("v", LOC_SCOPE_VERSION);
        claim.put("nodes", nodes.stream().map(UUID::toString).toList());
        return claim;
    }

    private JwtParser jwtParser() {
        return Jwts.parser()
                .verifyWith(secretKey)
                .requireIssuer(ISSUER)
                .requireAudience(AUDIENCE)
                .clock(() -> Date.from(Instant.now(clock)))
                .build();
    }

    private Claims getClaims(String token) {
        return jwtParser().parseSignedClaims(token).getPayload();
    }

    private String normalizeRoleClaim(@NonNull String role) {
        String trimmed = role.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (upper.startsWith("ROLE_") || upper.startsWith("ROLE")) {
            return upper;
        }
        if (upper.isBlank()) {
            return "";
        }
        return "ROLE_" + upper;
    }
}
