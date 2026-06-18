package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request body for a timekeeping export job")
public class ExportJobRequest {

    @NotBlank
    @Schema(description = "Type of export", example = "TIMEKEEPING", requiredMode = REQUIRED)
    private String exportType;

    @Schema(description = "Optional filter criteria as key-value pairs", requiredMode = NOT_REQUIRED)
    private Map<String, Object> filters;

    @NotBlank
    @Schema(description = "Export format: CSV or JSON", example = "CSV", requiredMode = REQUIRED)
    private String format;

    @Schema(description = "How the export will be delivered", example = "DOWNLOAD", requiredMode = NOT_REQUIRED)
    private String deliveryMode;
}
