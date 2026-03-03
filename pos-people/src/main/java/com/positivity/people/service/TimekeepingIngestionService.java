package com.positivity.people.service;

import com.positivity.people.internal.dto.WorkSessionCompletedEvent;
import com.positivity.people.internal.dto.WorkSessionCorrectedEvent;
import org.jspecify.annotations.NonNull;

public interface TimekeepingIngestionService {

    /**
     * Ingests a finalized work session from shopmgr.
     * Idempotent: duplicate (tenantId, sessionId) deliveries are no-op.
     *
     * @param event the work session completed event
     * @throws IllegalArgumentException if tenantId or sessionId is null
     */
    void ingestWorkSession(@NonNull WorkSessionCompletedEvent event);

    /**
     * Records a work session correction as an immutable adjustment entry.
     *
     * @param event the work session corrected event
     */
    void recordCorrection(@NonNull WorkSessionCorrectedEvent event);
}