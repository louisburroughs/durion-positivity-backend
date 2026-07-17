package com.positivity.warranty.internal.dto;

import com.positivity.warranty.internal.enums.PartReturnDisposition;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Opens the defective-part RMA lifecycle for one claim line (PRD §3.8) — because the governing
 * policy has {@code requiresPartReturn} or staff chose to return/hold the part. At most one part
 * return per claim line; the record starts in {@code AWAITING_PART}.
 */
@Schema(description = "Create a part return (RMA) for a claim line; starts AWAITING_PART")
public record PartReturnCreateRequest(
        @Schema(description = "Claim line whose failed part is being returned/held") @NotNull
        UUID claimLineId,

        @Schema(description = "What happens to the defective part") @NotNull
        PartReturnDisposition disposition,

        @Schema(description = "Vendor-issued RMA number, if already assigned") @Size(max = 100)
        String rmaNumber,

        @Schema(description = "Where the part sits on the physical hold shelf") @Size(max = 10_000)
        String holdLocationNote) {}
