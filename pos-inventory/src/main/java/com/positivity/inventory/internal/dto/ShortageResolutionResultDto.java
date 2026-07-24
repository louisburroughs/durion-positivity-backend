package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.ShortageResolutionOption;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Result of executing a shortage resolution option (odoo-parity G2, issue #1049). References the
 * created artifact (backorder / reservation / transfer order / purchase suggestion); CANCEL_LINE
 * creates no artifact.
 */
@Schema(description = "Result of applying a resolution to an inventory shortage")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortageResolutionResultDto {

    @Schema(
            description = "Identifier of the allocation that was resolved",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID allocationId;

    @Schema(description = "The resolution option that was executed", example = "BACKORDER", requiredMode = REQUIRED)
    @NotNull
    private ShortageResolutionOption optionType;

    @Schema(description = "Idempotency key the resolution was recorded under", requiredMode = REQUIRED)
    @NotNull
    private String idempotencyKey;

    @Schema(
            description =
                    "Kind of artifact created (BACKORDER, RESERVATION, TRANSFER_ORDER, PURCHASE_SUGGESTION, NONE)",
            example = "BACKORDER",
            requiredMode = REQUIRED)
    @NotNull
    private String artifactType;

    @Schema(
            description = "Identifier of the created artifact; null for CANCEL_LINE",
            example = "01960003-0000-7000-8000-0000000000aa",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID artifactId;

    @Schema(
            description = "Timestamp when the shortage was resolved",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant resolvedAt;

    @Schema(description = "Resulting status of the resolution", example = "RESOLVED", requiredMode = REQUIRED)
    @NotNull
    private String status;
}
