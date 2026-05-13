package com.positivity.accounting.internal.dto;

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
    @Schema(description = "Type of export")
    private String exportType;

    @Schema(description = "Optional filter criteria as key-value pairs")
    private Map<String, Object> filters;

    @NotBlank
    @Schema(description = "Export format: CSV or JSON")
    private String format;

    @Schema(description = "How the export will be delivered")
    private String deliveryMode;
}
