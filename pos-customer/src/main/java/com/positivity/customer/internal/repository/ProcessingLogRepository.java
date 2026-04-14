package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.ProcessingLog;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link ProcessingLog} entities.
 *
 * <p>
 * Provides the idempotency-check query used by the workorder event handler
 * to detect duplicate event delivery.
 * </p>
 */
@Repository
public interface ProcessingLogRepository extends JpaRepository<ProcessingLog, UUID> {

    /**
     * Finds a processing log entry by the unique inbound event ID.
     *
     * @param eventId the unique event identifier from the event envelope
     * @return an {@link Optional} containing the log entry if found
     */
    Optional<ProcessingLog> findByEventId(String eventId);
}
