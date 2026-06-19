package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "Paid time off entry affecting technician availability")
public class PtoEntry {

    @Schema(description = "Identifier of the PTO entry", example = "PTO-12345", requiredMode = REQUIRED)
    String ptoId;

    @Schema(description = "Start timestamp of the PTO period", example = "2026-01-15T09:30:00Z", requiredMode = REQUIRED)
    Instant start;

    @Schema(description = "End timestamp of the PTO period", example = "2026-01-15T09:30:00Z", requiredMode = REQUIRED)
    Instant end;

    @Schema(description = "Type of paid time off", example = "VACATION", requiredMode = REQUIRED)
    String ptoType;
}
