package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.catalog.internal.entity.ProductLifecycleState;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "Product lifecycle update request")
public class ProductLifecycleUpdateRequest {
    @NotNull
    @Schema(
            description = "Target lifecycle state",
            example = "DISCONTINUED",
            implementation = ProductLifecycleState.class,
            requiredMode = REQUIRED)
    private ProductLifecycleState lifecycleState;

    @Schema(
            description = "Effective instant for lifecycle change",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant effectiveAt;

    @Schema(
            description = "Effective date for lifecycle change (date-only)",
            example = "2026-01-15",
            requiredMode = NOT_REQUIRED)
    private LocalDate effectiveDate;

    @Schema(description = "Username performing the lifecycle change", example = "jane.doe", requiredMode = NOT_REQUIRED)
    private String changedBy;

    @Schema(description = "Reason for lifecycle override", example = "Vendor discontinued", requiredMode = NOT_REQUIRED)
    private String overrideReason;
}
