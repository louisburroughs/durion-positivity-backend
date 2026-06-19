package com.positivity.bulkloader.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.bulkloader.internal.enums.DomainType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Result of content detection over an uploaded file, used to infer the target domain and mappings")
public class ContentDetectionResult {

    @Schema(
            description = "Domain detected from the file content",
            example = "CATALOG_PRODUCT",
            requiredMode = REQUIRED)
    @NotNull
    private DomainType detectedDomain;

    @Schema(
            description = "Confidence score of the detection, between 0 and 1",
            example = "0.92",
            requiredMode = REQUIRED)
    private double confidence;

    @Schema(
            description = "Suggested mappings from source column names to target field names",
            example = "{\"Product SKU\": \"sku\", \"Price\": \"basePrice\"}",
            requiredMode = NOT_REQUIRED)
    private Map<String, String> suggestedColumnMappings;

    @Schema(
            description = "Whether an LLM fallback was used to perform the detection",
            example = "false",
            requiredMode = REQUIRED)
    private boolean llmFallbackUsed;
}
