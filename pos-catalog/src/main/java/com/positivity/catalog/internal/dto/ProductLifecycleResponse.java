package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.catalog.internal.entity.ProductLifecycleState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@Schema(description = "Product lifecycle details and replacement options")
public class ProductLifecycleResponse {
    @Schema(
            description = "Product identifier",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = REQUIRED)
    private UUID productId;

    @Schema(
            description = "Current lifecycle state",
            example = "ACTIVE",
            implementation = ProductLifecycleState.class,
            requiredMode = REQUIRED)
    private ProductLifecycleState lifecycleState;

    @Schema(
            description = "Lifecycle state effective instant",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant lifecycleStateEffectiveAt;

    @Schema(
            description = "User that last changed lifecycle state",
            example = "8b8df63e-18d8-4bde-a8f4-88bc36bc57d7",
            requiredMode = NOT_REQUIRED)
    private UUID lastStateChangedBy;

    @Schema(
            description = "Last lifecycle state change instant",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant lastStateChangedAt;

    @Schema(description = "Lifecycle override reason", example = "Vendor discontinued", requiredMode = NOT_REQUIRED)
    private String lifecycleOverrideReason;

    @Schema(description = "Replacement options for this product lifecycle state", example = "[]", requiredMode = NOT_REQUIRED)
    private List<ReplacementOption> replacementOptions;

    @Getter
    @Builder
    @Schema(description = "Replacement option details")
    public static class ReplacementOption {
        @Schema(
                description = "Replacement option identifier",
                example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
                requiredMode = REQUIRED)
        private UUID replacementId;

        @Schema(
                description = "Replacement product identifier",
                example = "2c018664-dfea-46ef-a838-6b9dbf8f9b1d",
                requiredMode = REQUIRED)
        private UUID replacementProductId;

        @Schema(description = "Display priority order", example = "1", requiredMode = NOT_REQUIRED)
        private Integer priorityOrder;

        @Schema(description = "Replacement notes", example = "Direct successor model", requiredMode = NOT_REQUIRED)
        private String notes;

        @Schema(
                description = "Replacement option effective instant",
                example = "2026-01-15T09:30:00Z",
                requiredMode = NOT_REQUIRED)
        private Instant effectiveAt;
    }
}
