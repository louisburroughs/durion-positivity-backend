package com.positivity.tenant.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A reading tier as the platform operator sees it (#2619). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A reading tier")
public class ReadingTierResponse {

    @Schema(description = "Tier id", requiredMode = REQUIRED)
    private UUID id;

    @Schema(description = "Tier code", example = "STANDARD", requiredMode = REQUIRED)
    private String code;

    @Schema(description = "Display name", example = "Standard", requiredMode = REQUIRED)
    private String displayName;

    @Schema(description = "Monthly document-reading allowance", requiredMode = REQUIRED)
    private MonthlyAllowanceDto monthlyAllowance;

    @Schema(description = "Whether the tier can be assigned", requiredMode = REQUIRED)
    private boolean active;

    @Schema(description = "Optimistic-lock version", requiredMode = REQUIRED)
    private Long version;

    @Schema(description = "Created at", requiredMode = REQUIRED)
    private Instant createdAt;

    @Schema(description = "Last changed at", requiredMode = REQUIRED)
    private Instant updatedAt;
}
