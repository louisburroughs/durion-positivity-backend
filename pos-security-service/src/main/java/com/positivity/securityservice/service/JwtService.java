package com.positivity.securityservice.service;

import com.positivity.securityservice.model.JwtToken;
import com.positivity.securityservice.repository.JwtTokenRepository;
import io.jsonwebtoken.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class JwtService {
    private final JwtTokenRepository jwtTokenRepository;
    private final SecretKey secretKey = Jwts.SIG.HS256.key().build();

    public String generateToken(String username, Set<String> roles) {
        Instant now = Instant.now();
        // 1 hour
        long expirationMillis = 900_000;
        Instant expiry = now.plusMillis(expirationMillis);
        String token = Jwts.builder()
                .subject(username)
                .claim("roles", roles)
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

    public String getUsernameFromToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    public Set<String> getRolesFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token).getPayload();
        return new java.util.HashSet<>((java.util.List<String>) claims.get("roles"));
    }

    public void deleteToken(String token) {
        jwtTokenRepository.deleteByToken(token);
    }

    public record TokenPair(String accessToken, String refreshToken) {}

    public TokenPair generateTokenPair(String username, Set<String> roles) {
        Instant now = Instant.now();
        long accessExpirationMillis = 900_000; // 15 minutes
        long refreshExpirationMillis = 7 * 24 * 60 * 60 * 1000L; // 7 days
        Instant accessExpiry = now.plusMillis(accessExpirationMillis);
        Instant refreshExpiry = now.plusMillis(refreshExpirationMillis);
        String accessToken = Jwts.builder()
                .subject(username)
                .claim("roles", roles)
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
