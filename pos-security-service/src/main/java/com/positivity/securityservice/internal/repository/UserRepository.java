package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByUsername(String username);

    Optional<User> findByPersonId(UUID personId);

    boolean existsByUsername(String username);
}
