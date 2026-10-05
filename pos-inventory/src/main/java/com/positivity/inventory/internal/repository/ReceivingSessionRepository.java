package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ReceivingSession;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReceivingSessionRepository extends JpaRepository<ReceivingSession, UUID> {

    /**
     * The session, write-locked until the transaction ends (#2455): receive and cross-dock take it
     * first, so concurrent calls on one session serialise. The second same-key call then sees the
     * first's receipt and replays, and the cumulative over-receipt guard reads settled totals.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ReceivingSession s WHERE s.sessionId = :sessionId")
    @NonNull
    Optional<ReceivingSession> findByIdForUpdate(@Param("sessionId") @NonNull UUID sessionId);
}
