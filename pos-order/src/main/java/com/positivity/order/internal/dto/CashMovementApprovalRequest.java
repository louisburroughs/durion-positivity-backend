package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;
import lombok.ToString;

/**
 * The step-up request (CAP:550 S16, #2512; AW31): a manager's own credentials, entered once at the
 * shared register under the cashier's sign-in, and the movement the approval is for. The password is
 * never stored, logged or printed.
 */
@Data
@Schema(description = "A manager's credentials and the cash movement they approve")
public class CashMovementApprovalRequest {

    @Schema(description = "The approving manager's sign-in name", example = "jane.manager", requiredMode = REQUIRED)
    private String managerUsername;

    @ToString.Exclude
    @Schema(
            description = "The approving manager's password; checked once, never stored or logged",
            format = "password",
            accessMode = Schema.AccessMode.WRITE_ONLY,
            requiredMode = REQUIRED)
    private String managerPassword;

    @Schema(
            description = "The reason of the movement approved",
            example = "PETTY_EXPENSE",
            allowableValues = {"PETTY_EXPENSE", "VENDOR_COD", "BANK_DROP", "FLOAT_INCREASE", "FLOAT_DECREASE"},
            requiredMode = REQUIRED)
    private String reason;

    @Schema(description = "The exact amount of the movement approved", example = "25.00", requiredMode = REQUIRED)
    private BigDecimal amount;

    @Schema(
            description = "The movement's petty-expense category, when it has one",
            example = "SHOP_SUPPLIES",
            requiredMode = NOT_REQUIRED)
    private String categoryCode;

    @Schema(description = "The movement's vendor, when it has one", requiredMode = NOT_REQUIRED)
    private UUID vendorId;
}
