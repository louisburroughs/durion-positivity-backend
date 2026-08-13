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
import lombok.extern.jackson.Jacksonized;
import org.jspecify.annotations.Nullable;

/**
 * Request DTO for searching vehicles (CAP:091 Story #103).
 *
 * <p>
 * {@code @Jacksonized} is load-bearing: {@code @Builder} on a class with final
 * fields generates only a package-private all-args constructor, which leaves
 * Jackson with no creator and makes {@code POST /v1/vehicles/search} fail during
 * message conversion for every body. It wires the generated builder up as the
 * creator while keeping the fields final.
 */
@Getter
@Builder(toBuilder = true)
@Jacksonized
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
