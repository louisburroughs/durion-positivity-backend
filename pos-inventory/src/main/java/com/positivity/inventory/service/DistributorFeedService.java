package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.DistributorFeedItemDto;
import java.util.Collection;
import org.jspecify.annotations.NonNull;

/**
 * Service contract for processing distributor inventory feeds.
 *
 * Issue: CAP-170 (#47)
 */
public interface DistributorFeedService {

    /**
     * Processes distributor feed items by normalizing valid data and routing
     * failures to exception queue.
     *
     * @param feedItems distributor feed records
     */
    void processFeed(@NonNull Collection<DistributorFeedItemDto> feedItems);
}
