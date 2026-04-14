package com.positivity.inventory.internal.client;

import com.positivity.inventory.internal.dto.shortage.ResolutionOption;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Calls Positivity Domain for external purchase availability.
 * Timeout and HTTP errors propagate as exceptions; the caller
 * (ShortageResolutionServiceImpl) catches them and sets
 * partialResultsBanner=true.
 */
public interface ExternalAvailabilityClient {
    @NonNull
    List<ResolutionOption> resolveExternalOptions(@NonNull String sku, int shortQuantity);
}
