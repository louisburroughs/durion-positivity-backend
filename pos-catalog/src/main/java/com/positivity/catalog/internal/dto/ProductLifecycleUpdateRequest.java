package com.positivity.catalog.internal.dto;

import com.positivity.catalog.internal.entity.ProductLifecycleState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "Product lifecycle update request")
public class ProductLifecycleUpdateRequest {
    @Schema(description = "Target lifecycle state", implementation = ProductLifecycleState.class)
    private ProductLifecycleState lifecycleState;

    @Schema(description = "Effective instant for lifecycle change")
    private Instant effectiveAt;

    @Schema(description = "Effective date for lifecycle change (date-only)")
    private LocalDate effectiveDate;

    @Schema(description = "Username performing the lifecycle change", example = "jane.doe")
    private String changedBy;

    @Schema(description = "Reason for lifecycle override")
    private String overrideReason;
}
