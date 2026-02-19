package com.positivity.vehicle.internal.dto;

import org.jspecify.annotations.Nullable;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * Request DTO for searching vehicles (CAP:091 Story #103).
 */
@Getter
@Builder(toBuilder = true)
@ToString
public class SearchVehiclesRequest {
    private final String query;
    @Nullable
    private final Integer limit; // default 25, max 50
    @Nullable
    private final String cursor; // for pagination (reserved for future)
    @Nullable
    private final Boolean enableContainsMatching; // default false for strict matching

    public Integer getLimit() {
        return limit != null ? limit : 25;
    }

    public Boolean isEnableContainsMatching() {
        return enableContainsMatching != null && enableContainsMatching;
    }
}
