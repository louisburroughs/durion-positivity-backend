package com.positivity.customer.internal.enums;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Status of a party/account in the CRM system.
 */
@Schema(description = "Status of a party/account")
public enum AccountStatus {
    @Schema(description = "Account is active and operational")
    ACTIVE,

    @Schema(description = "Account is temporarily inactive")
    INACTIVE,

    @Schema(description = "Account is on hold (e.g., billing issues)")
    ON_HOLD,

    @Schema(description = "Account has been merged into another account")
    MERGED
}
