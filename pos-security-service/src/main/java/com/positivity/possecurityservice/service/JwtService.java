package com.positivity.possecurityservice.service;

import com.positivity.possecurityservice.model.JwtToken;
import com.positivity.possecurityservice.repository.JwtTokenRepository;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
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
    private final SecretKey secretKey = Keys.secretKeyFor(SignatureAlgorithm.HS256);
    private final long expirationMillis = 3600_000; // 1 hour

    public String generateToken(String username, Set<String> roles) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(expirationMillis);
        String token = Jwts.builder()
                .setSubject(username)
                .claim("roles", roles)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(expiry))
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
            Jws<Claims> claims = Jwts.parserBuilder()
                    .setSigningKey(secretKey)
                    .build()
                    .parseClaimsJws(token);
            Optional<JwtToken> stored = jwtTokenRepository.findByToken(token);
            return stored.isPresent() && !stored.get().getExpiresAt().isBefore(Instant.now());
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public String getUsernameFromToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(secretKey)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject();
    }

    public Set<String> getRolesFromToken(String token) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(secretKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
        return new java.util.HashSet<>((java.util.List<String>) claims.get("roles"));
    }

    public void deleteToken(String token) {
        jwtTokenRepository.deleteByToken(token);
    }
}

