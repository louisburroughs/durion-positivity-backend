package com.positivity.securityservice.service;

import com.positivity.securityservice.model.JwtToken;
import com.positivity.securityservice.repository.JwtTokenRepository;
import io.jsonwebtoken.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Service for handling JWT token operations such as generation, validation, extraction, and deletion.
 */
@Service
@RequiredArgsConstructor
public class JwtService {
    /** Claim key for roles in the JWT. */
    public static final String ROLES = "roles";
    private final JwtTokenRepository jwtTokenRepository;
    private final SecretKey secretKey = Jwts.SIG.HS256.key().build();

    /**
     * Generates a JWT token for the given username and roles, stores it in the repository, and returns the token string.
     *
     * @param username the subject for the token
     * @param roles the set of roles to include in the token
     * @return the generated JWT token string
     */
    public String generateToken(String username, Set<String> roles) {
        Instant now = Instant.now();
        // 1 hour
        long expirationMillis = 900_000;
        Instant expiry = now.plusMillis(expirationMillis);
        String token = Jwts.builder()
                .subject(username)
                .claim(ROLES, roles)
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
        return token;
    }

    /**
     * Validates the given JWT token by checking its signature, expiration, and presence in the repository.
     *
     * @param token the JWT token string to validate
     * @return true if the token is valid and not expired, false otherwise
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
        Optional<JwtToken> stored = jwtTokenRepository.findByToken(token);
        return stored.isPresent() && !stored.get().getExpiresAt().isBefore(Instant.now());
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
     * Deletes the given JWT token from the repository.
     *
     * @param token the JWT token string to delete
     */
    public void deleteToken(String token) {
        jwtTokenRepository.deleteByToken(token);
    }

    /**
     * Record representing a pair of access and refresh tokens.
     *
     * @param accessToken the access token
     * @param refreshToken the refresh token
     */
    public record TokenPair(String accessToken, String refreshToken) {}

    /**
     * Generates a pair of access and refresh tokens for the given username and roles, stores them, and returns the pair.
     *
     * @param username the subject for the tokens
     * @param roles the set of roles to include in the access token
     * @return a TokenPair containing the access and refresh tokens
     */
    public TokenPair generateTokenPair(String username, Set<String> roles) {
        Instant now = Instant.now();
        long accessExpirationMillis = 900_000; // 15 minutes
        long refreshExpirationMillis = 7 * 24 * 60 * 60 * 1000L; // 7 days
        Instant accessExpiry = now.plusMillis(accessExpirationMillis);
        Instant refreshExpiry = now.plusMillis(refreshExpirationMillis);
        String accessToken = Jwts.builder()
                .subject(username)
                .claim(ROLES, roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(accessExpiry))
                .signWith(secretKey)
                .compact();
        String refreshToken = Jwts.builder()
                .subject(username)
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
        return new TokenPair(accessToken, refreshToken);
    }

    /**
     * Validates the given refresh token by checking its signature, expiration, and presence in the repository.
     *
     * @param refreshToken the refresh token string to validate
     * @return true if the refresh token is valid and not expired, false otherwise
     */
    public boolean validateRefreshToken(String refreshToken) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
        Optional<JwtToken> stored = jwtTokenRepository.findByRefreshToken(refreshToken);
        return stored.isPresent() && !stored.get().getRefreshExpiresAt().isBefore(Instant.now());
    }

    /**
     * Refreshes the access token using the given refresh token, invalidates the old tokens, and returns a new token pair.
     *
     * @param refreshToken the refresh token string
     * @return a new TokenPair with fresh access and refresh tokens
     * @throws IllegalArgumentException if the refresh token is invalid or not found
     */
    public TokenPair refreshAccessToken(String refreshToken) {
        if (!validateRefreshToken(refreshToken)) {
            throw new IllegalArgumentException("Invalid refresh token");
        }
        Optional<JwtToken> stored = jwtTokenRepository.findByRefreshToken(refreshToken);
        if (stored.isEmpty()) {
            throw new IllegalArgumentException("Refresh token not found");
        }
        String username = stored.get().getSubject();
        Set<String> roles = getRolesFromToken(stored.get().getToken());
        // Invalidate old tokens
        jwtTokenRepository.delete(stored.get());
        return generateTokenPair(username, roles);
    }
}
