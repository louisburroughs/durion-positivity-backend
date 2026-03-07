package com.positivity.workorder.service;

import com.positivity.workorder.internal.dto.WorkorderPartAdjustmentEventResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Public service contract for workorder part substitution operations.
 *
 * Issue: #49
 */
public interface WorkorderSubstitutionService {

    /**
     * Substitutes a part line on a workorder and records immutable substitution
     * metadata.
     *
     * @param workorderId      workorder identifier
     * @param originalPartId   original part line identifier
     * @param substitutePartId substitute product identifier
     * @param reason           substitution reason
     * @param idempotencyKey   optional idempotency key
     * @param notes            optional notes
     * @return created substitution adjustment event response
     */
    @NonNull
    WorkorderPartAdjustmentEventResponse substitutePartLine(
            @NonNull UUID workorderId,
            @NonNull UUID originalPartId,
            @NonNull UUID substitutePartId,
            @NonNull String reason,
            @Nullable String idempotencyKey,
            @Nullable String notes);
}
