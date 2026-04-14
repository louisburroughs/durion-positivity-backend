package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ReprocessingAttemptHistory;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for reprocessing attempt history.
 * Tracks all attempts to reprocess suspended accounting events.
 *
 * @see ReprocessingAttemptHistory
 */
@Repository
public interface ReprocessingAttemptHistoryRepository extends JpaRepository<ReprocessingAttemptHistory, UUID> {

    /**
     * Find all reprocessing attempts for a specific accounting event.
     * Ordered by attempted_at DESC to show most recent first.
     *
     * @param eventId the accounting event ID
     * @return list of reprocessing attempts, most recent first
     */
    @NonNull
    List<ReprocessingAttemptHistory> findByAccountingEvent_EventIdOrderByAttemptedAtDesc(@NonNull UUID eventId);

    /**
     * Count reprocessing attempts for a specific accounting event.
     *
     * @param eventId the accounting event ID
     * @return count of attempts
     */
    long countByAccountingEvent_EventId(@NonNull UUID eventId);
}
