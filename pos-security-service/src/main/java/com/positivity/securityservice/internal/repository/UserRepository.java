package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.User;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByUsername(String username);

    Optional<User> findByPersonId(UUID personId);

    /** Every user linked to the pos-people person; {@code person_id} is not unique (V7). */
    List<User> findAllByPersonId(UUID personId);

    boolean existsByUsername(String username);

    /**
     * The user row under a pessimistic write lock, serializing concurrent writers on that user for
     * the rest of the transaction (WS2b-3: two mints for one administrator cannot both see "no open
     * token" and commit two live activation tokens). Subject to the tenant filter like every other
     * query.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);
}
