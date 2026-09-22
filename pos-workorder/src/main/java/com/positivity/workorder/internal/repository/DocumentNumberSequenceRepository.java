package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.DocumentNumberSequence;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface DocumentNumberSequenceRepository extends JpaRepository<DocumentNumberSequence, UUID> {

    /**
     * Load a scope's counter row under a pessimistic write lock ({@code SELECT ... FOR UPDATE}).
     * Concurrent allocators in the same scope wait here until the holding transaction commits.
     *
     * @return the locked row, or empty if the scope has never issued a number
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DocumentNumberSequence> findByScopeKey(String scopeKey);
}
