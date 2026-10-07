package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

/**
 * Request payload for recording a drawer cash movement (CAP:550 S16, #2512; AW15). The cashier is
 * never part of the body: it comes from the security context, and a {@code clerkId} a client still
 * sends is ignored (ADR-0018, §9.5a). Fields are validated by the service so every malformed request
 * answers {@code REGISTER_SESSION_INVALID_ARGUMENT}.
 */
@Data
@Schema(description = "Request payload for recording a drawer cash movement with one of the fixed reasons")
public class CashMovementRequest {

    @Schema(
            description = "The register's idempotency key (UUIDv7): a retry with the same id returns the first result",
            example = "01960003-0000-7000-8000-0000000000c1",
            requiredMode = REQUIRED)
    private UUID requestId;

    @Schema(
            description = "The fixed reason; it decides the direction and the required fields",
            example = "PETTY_EXPENSE",
            allowableValues = {"PETTY_EXPENSE", "VENDOR_COD", "BANK_DROP", "FLOAT_INCREASE", "FLOAT_DECREASE"},
            requiredMode = REQUIRED)
    private String reason;

    @Schema(description = "Positive cash amount moved", example = "30.00", requiredMode = REQUIRED)
    private BigDecimal amount;

    @Schema(
            description = "ACTIVE petty-expense category code; required for PETTY_EXPENSE",
            example = "SHOP_SUPPLIES",
            requiredMode = NOT_REQUIRED)
    private String categoryCode;

    @Schema(description = "The vendor paid; required for VENDOR_COD", requiredMode = NOT_REQUIRED)
    private UUID vendorId;

    @Schema(
            description = "Deposit bag number; required for BANK_DROP",
            example = "BAG-0042",
            requiredMode = NOT_REQUIRED)
    private String bagNumber;

    @Schema(
            description = "Receipt reference; required for PETTY_EXPENSE",
            example = "R-1001",
            requiredMode = NOT_REQUIRED)
    private String receiptReference;

    @Schema(
            description = "Free-text note; required for PETTY_EXPENSE, optional otherwise",
            example = "Rags and gloves for bay 2",
            requiredMode = NOT_REQUIRED)
    private String note;

    @Schema(
            description = "A manager's single-use approval token from the cash-movement-approvals step-up; required"
                    + " above the cashier limit and for every float change",
            requiredMode = NOT_REQUIRED)
    private String approvalToken;
}
