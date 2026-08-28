package com.positivity.location.internal.service;

import com.positivity.location.internal.dto.LocationRef;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Public service contract for location roster synchronization operations.
 *
 * Issue: CAP-214 #40
 */
public interface LocationRosterService {

    /**
     * Returns a paginated location roster with optional filters.
     *
     * @param status         optional status filter
     * @param sinceUpdatedAt optional lower bound for updated-at timestamp
     * @param pageable       pagination information
     * @return page of location references
     */
    @NonNull
    Page<LocationRef> getRoster(@Nullable String status, @Nullable Instant sinceUpdatedAt, @NonNull Pageable pageable);
}
