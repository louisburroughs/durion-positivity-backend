package com.positivity.accounting.internal.bankrec.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.bankrec.enums.OutstandingItemKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Register a non-posting outstanding (timing) item (SPEC §3.6; story S4, #2303): a ledger line the bank has
 * not shown yet ({@code DEPOSIT_IN_TRANSIT}, {@code OUTSTANDING_CHECK}, {@code OTHER_LEDGER_TIMING}) or a bank
 * transaction the bank will correct ({@code BANK_ERROR_PENDING}). Registering posts nothing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Register an outstanding item on a ledger line or a bank transaction")
public class OutstandingItemRegisterRequest {

    @Schema(description = "The ledger line of a ledger-side item")
    private UUID glLineId;

    @Schema(description = "The bank transaction of a BANK_ERROR_PENDING item")
    private UUID bankTransactionId;

    @NotNull(message = "itemKind is required")
    @Schema(description = "Kind of the item", example = "DEPOSIT_IN_TRANSIT", requiredMode = REQUIRED)
    private OutstandingItemKind itemKind;

    @Size(max = 1000, message = "justification must not exceed 1000 characters")
    @Schema(
            description = "Required (at least 10 characters) for OTHER_LEDGER_TIMING, BANK_ERROR_PENDING and any"
                    + " item older than the aging days",
            example = "Deposit made after the bank's cut-off on the last day")
    private String justification;
}
