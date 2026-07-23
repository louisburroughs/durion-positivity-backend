package com.positivity.inventory.internal.dto.scrap;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for approving a pending scrap document (odoo-parity D1, issue #1030).
 *
 * <p>The approving actor is taken from the security context (ADR-0018), not from the body.
 */
@Schema(description = "Request to approve a pending scrap document and post it to the ledger")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApproveScrapRequest {

    @Schema(
            description = "Explicit negative-stock override for the posting; honored only when the caller"
                    + " holds inventory:adjustment:override",
            example = "false",
            requiredMode = NOT_REQUIRED)
    @Builder.Default
    private Boolean negativeStockOverride = false;
}
