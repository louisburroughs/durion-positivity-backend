package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Person summary shown in timekeeping approval workflows")
public class ApprovalPersonDto {

    @Schema(description = "Person identifier", example = "01960011-0000-7000-8000-000000000001", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID personId;

    @Schema(description = "Person display name", example = "Jane Smith", requiredMode = Schema.RequiredMode.REQUIRED)
    private String displayName;

    @Schema(description = "Employee number", example = "EMP-0001", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String employeeNumber;
}
