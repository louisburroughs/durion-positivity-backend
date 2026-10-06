package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The day-end item (#2508, §9.5a): walk-in invoices still open after their business day ended. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Walk-in invoices still open after their business day ended")
public class WalkInNeedsAttention {

    @Schema(description = "Number of such invoices", example = "1", requiredMode = REQUIRED)
    private int count;

    @Schema(description = "Sum of their balances due", example = "40.00", requiredMode = REQUIRED)
    private BigDecimal amount;
}
