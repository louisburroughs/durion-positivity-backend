package com.positivity.catalog.internal.dto;

import com.positivity.catalog.internal.entity.ProductLifecycleState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
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

    @Schema(description = "User performing the lifecycle change", example = "8b8df63e-18d8-4bde-a8f4-88bc36bc57d7")
    private UUID changedBy;

    @Schema(description = "Reason for lifecycle override")
    private String overrideReason;
}
