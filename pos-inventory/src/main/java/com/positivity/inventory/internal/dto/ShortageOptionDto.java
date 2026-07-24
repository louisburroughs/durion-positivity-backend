package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.ShortageResolutionOption;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * A computed candidate option for resolving an inventory shortage (odoo-parity G2, issue #1049).
 * Each option carries an expected-resolution date and a cost delta where computable.
 */
@Schema(description = "A computed option for resolving an inventory shortage on an allocation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortageOptionDto {

    @Schema(
            description = "Identifier of the allocation the option applies to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID allocationId;

    @Schema(description = "The computed resolution strategy", example = "SUBSTITUTE", requiredMode = REQUIRED)
    @NotNull
    private ShortageResolutionOption optionType;

    @Schema(
            description = "Human-readable description of the resolution option",
            example = "Substitute with group member 0196... (12 available at site)",
            requiredMode = REQUIRED)
    @NotNull
    private String description;

    @Schema(
            description = "Substitute stock-keeping unit offered, when the option is SUBSTITUTE",
            example = "01960003-0000-7000-8000-000000000099",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private String substituteSku;

    @Schema(
            description = "Source site surplus can be pulled from, when the option is TRANSFER_IN",
            example = "01960004-0001-7000-8000-00000000000a",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID sourceLocationId;

    @Schema(
            description = "Quantity available toward the shortage for SUBSTITUTE / TRANSFER_IN options",
            example = "12",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private Long availableQuantity;

    @Schema(
            description = "Estimated date the shortage would be resolved via this option",
            example = "2026-08-01",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private LocalDate expectedResolutionDate;

    @Schema(
            description = "Per-unit cost delta versus the original SKU, when computable (positive = costs more)",
            example = "4.50",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private BigDecimal costDelta;
}
