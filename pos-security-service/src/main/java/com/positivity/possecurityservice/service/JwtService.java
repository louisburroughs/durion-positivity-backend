package com.positivity.possecurityservice.service;

import com.positivity.possecurityservice.model.JwtToken;
import com.positivity.possecurityservice.repository.JwtTokenRepository;
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
        long expirationMillis = 3600_000;
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
}


