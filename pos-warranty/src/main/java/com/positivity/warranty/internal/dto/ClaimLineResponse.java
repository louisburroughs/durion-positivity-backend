package com.positivity.warranty.internal.dto;

import com.positivity.warranty.internal.enums.LineDisposition;
import com.positivity.warranty.internal.enums.LineSourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** Claim line view (PRD §3.5), including computed proration and its frozen audit inputs. */
@Schema(description = "Warranty claim line")
public record ClaimLineResponse(
        UUID id,
        LineSourceType sourceType,
        UUID sourceId,
        UUID sourceLineId,
        UUID productEntityId,
        String sku,
        String description,
        String serialNumber,
        String dotNumber,
        BigDecimal quantity,
        BigDecimal originalUnitPrice,

        @Schema(description = "Original tread depth in 32nds of an inch")
        Integer originalTreadDepth,

        @Schema(description = "Measured remaining tread depth in 32nds of an inch")
        Integer measuredTreadDepth,

        @Schema(description = "Computed credit fraction in [0, 1]")
        BigDecimal prorationPct,

        @Schema(description = "Every number that fed the proration computation")
        Map<String, Object> prorationInputs,

        BigDecimal amountRequested,
        BigDecimal amountApproved,
        LineDisposition lineDisposition) {}
