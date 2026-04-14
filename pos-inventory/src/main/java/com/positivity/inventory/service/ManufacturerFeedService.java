package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.ManufacturerFeedItemDto;
import java.util.Collection;
import org.jspecify.annotations.NonNull;

/**
 * Service contract for processing manufacturer inventory feeds.
 *
 * Issue: CAP-170 (#46)
 */
public interface ManufacturerFeedService {

    /**
     * Processes manufacturer feed items by normalizing mapped items and routing
     * unmapped items to review queue.
     *
     * @param feedItems manufacturer feed records
     */
    void processFeed(@NonNull Collection<ManufacturerFeedItemDto> feedItems);
}
