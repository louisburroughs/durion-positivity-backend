package com.positivity.vehicle.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/**
 * Request DTO for searching vehicles (CAP:091 Story #103).
 */
@Getter
@Builder(toBuilder = true)
@ToString
@Schema(description = "Request payload for searching vehicles by free-text query with optional pagination")
public class SearchVehiclesRequest {

    @Schema(
            description = "Free-text search query matched against VIN, unit number, plate, and description",
            example = "1HGCM82633A004352",
            requiredMode = REQUIRED)
    @NotBlank
    private final String query;

    @Schema(
            description = "Maximum number of results to return; defaults to 25 when omitted",
            example = "25",
            requiredMode = NOT_REQUIRED)
    @Min(1)
    @Max(50)
    @Nullable
    private final Integer limit; // default 25, max 50

    @Schema(
            description = "Opaque pagination cursor for retrieving subsequent result pages (reserved for future use)",
            example = "eyJvZmZzZXQiOjI1fQ==",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private final String cursor; // for pagination (reserved for future)

    @Schema(
            description = "When true, enables substring (contains) matching; defaults to false for strict matching",
            example = "false",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private final Boolean enableContainsMatching; // default false for strict matching

    public Integer getLimit() {
        return limit != null ? limit : 25;
    }

    public Boolean isEnableContainsMatching() {
        return enableContainsMatching != null && enableContainsMatching;
    }
}
