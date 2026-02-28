package com.positivity.inventory.internal.client;

import com.positivity.inventory.dto.shortage.ResolutionOption;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Calls Product Domain to resolve substitute parts.
 * Timeout and HTTP errors propagate as exceptions; the caller
 * (ShortageResolutionServiceImpl) catches them and sets
 * partialResultsBanner=true.
 */
public interface ProductSubstituteClient {
    @NonNull
    List<ResolutionOption> resolveSubstitutes(@NonNull String sku, int shortQuantity);
}