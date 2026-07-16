package com.positivity.warranty.internal.dto;

import com.positivity.warranty.internal.enums.LineSourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One failed part or service being claimed (PRD §3.5). Provenance follows the SalesOrderLine
 * pattern; {@code sourceId}/{@code sourceLineId} are null for {@code MANUAL} lines.
 * Tread depths are integers in 32nds of an inch.
 */
@Schema(description = "Warranty claim line — one failed part or service, with source provenance and snapshots")
public record ClaimLineRequest(
        @Schema(description = "Provenance of the line", example = "INVOICE_LINE") @NotNull
        LineSourceType sourceType,

        @Schema(description = "Originating invoice or workorder id (null for MANUAL lines)")
        UUID sourceId,

        @Schema(description = "Originating invoice/workorder line id (null for MANUAL lines)")
        UUID sourceLineId,

        @Schema(description = "pos-catalog product id, when known")
        UUID productEntityId,

        @Schema(description = "Catalog SKU snapshot") @Size(max = 100)
        String sku,

        @Schema(description = "Line description snapshot") @Size(max = 512)
        String description,

        @Schema(description = "Serial number of the failed unit") @Size(max = 100)
        String serialNumber,

        @Schema(description = "Tire DOT number", example = "DOT Y9RJ FPUU 2325") @Size(max = 20)
        String dotNumber,

        @Schema(description = "Quantity claimed; defaults to 1") @DecimalMin(value = "0", inclusive = false)
        BigDecimal quantity,

        @Schema(description = "Original unit price snapshot") @DecimalMin("0")
        BigDecimal originalUnitPrice,

        @Schema(description = "Original tread depth in 32nds of an inch", example = "12") @Min(0)
        Integer originalTreadDepth,

        @Schema(description = "Measured remaining tread depth in 32nds of an inch", example = "6") @Min(0)
        Integer measuredTreadDepth) {}
