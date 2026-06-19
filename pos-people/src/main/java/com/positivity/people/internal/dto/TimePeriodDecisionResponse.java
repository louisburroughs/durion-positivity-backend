package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Result of an approve/reject decision applied across a person's time period entries")
public class TimePeriodDecisionResponse {

    @Schema(description = "Person identifier", example = "01960011-0000-7000-8000-000000000001", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID personId;

    @Schema(description = "Time period identifier", example = "01960011-0000-7000-8000-000000000010", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID timePeriodId;

    @Schema(description = "Number of entries processed by the decision", example = "7", requiredMode = Schema.RequiredMode.REQUIRED)
    private int processedCount;

    @Schema(description = "Resulting status of the operation", example = "APPROVED", requiredMode = Schema.RequiredMode.REQUIRED)
    private String status;
}
