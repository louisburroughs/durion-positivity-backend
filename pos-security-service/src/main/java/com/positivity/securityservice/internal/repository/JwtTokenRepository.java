package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.model.JwtToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface JwtTokenRepository extends JpaRepository<JwtToken, Long> {
    Optional<JwtToken> findByToken(String token);
    void deleteByToken(String token);
    Optional<JwtToken> findByRefreshToken(String refreshToken);
    void deleteByRefreshToken(String refreshToken);
}

