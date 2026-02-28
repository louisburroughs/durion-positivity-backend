package com.positivity.inventory.internal.client;

import org.jspecify.annotations.NonNull;

import com.positivity.inventory.internal.dto.shortage.ResolutionOption;

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