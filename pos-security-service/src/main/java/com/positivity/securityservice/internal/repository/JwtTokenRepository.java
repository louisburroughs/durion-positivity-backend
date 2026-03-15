package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.JwtToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JwtTokenRepository extends JpaRepository<JwtToken, UUID> {
    Optional<JwtToken> findByToken(String token);

    void deleteByToken(String token);

    Optional<JwtToken> findByRefreshToken(String refreshToken);

    void deleteByRefreshToken(String refreshToken);

    List<JwtToken> findAllBySubject(String subject);
}
