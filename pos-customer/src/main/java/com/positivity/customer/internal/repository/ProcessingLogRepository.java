package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.ProcessingLog;
import com.positivity.customer.internal.enums.ProcessingStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link ProcessingLog} entities.
 *
 * <p>
 * Provides the idempotency-check query used by the workorder event handler
 * to detect duplicate event delivery, and the window scan used by the
 * reconciliation-manifest comparison (ADR-0044 §4).
 * </p>
 */
public interface ProcessingLogRepository extends JpaRepository<ProcessingLog, UUID> {

    /**
     * Finds a processing log entry by the unique inbound event ID.
     *
     * @param eventId the unique event identifier from the event envelope
     * @return an {@link Optional} containing the log entry if found
     */
    Optional<ProcessingLog> findByEventId(String eventId);

    /**
     * Event ids received in a reconciliation window. UUIDv7 strings sort lexicographically by
     * their embedded timestamp, so the bounds are the window edges rendered via
     * {@code UuidV7Timestamps.minStringAt} ({@code lowerBound} inclusive, {@code upperBound}
     * exclusive). {@code SKIPPED_DUPLICATE} rows are excluded — their eventId is synthetic, not
     * the envelope's.
     */
    @Query("select p.eventId from ProcessingLog p"
            + " where p.eventId >= :lowerBound and p.eventId < :upperBound and p.status <> :excludedStatus")
    @NonNull
    List<String> findEventIdsInRange(
            @Param("lowerBound") @NonNull String lowerBound,
            @Param("upperBound") @NonNull String upperBound,
            @Param("excludedStatus") @NonNull ProcessingStatus excludedStatus);
}
