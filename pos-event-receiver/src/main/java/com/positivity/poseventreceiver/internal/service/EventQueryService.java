package com.positivity.poseventreceiver.internal.service;

import com.positivity.poseventreceiver.internal.dto.EmittedEventResponse;
import com.positivity.poseventreceiver.internal.dto.PagedResponse;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Service API for querying recorded events by entity id (issue #1521).
 */
public interface EventQueryService {

    /**
     * Returns a page of emitted events recorded against {@code entityId}, newest first by
     * publishedAt. Only events recorded with an entity id are ever returned.
     *
     * @param entityId the entity id events were recorded against
     * @param since    lower bound on publishedAt, inclusive; {@code null} defaults to 7 days
     *                 before now; must resolve to no earlier than 90 days before now and no
     *                 later than now
     * @param page     zero-based page index
     * @param size     page size
     * @return the matching page, wrapped for the REST response
     */
    @NonNull
    PagedResponse<EmittedEventResponse> findByEntity(
            @NonNull String entityId, @Nullable Instant since, int page, int size);
}
