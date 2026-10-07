package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.Data;

/**
 * Replace the tenant's drawer policy (CAP:550 S16, #2512): the two configurable movement types, the
 * over/short tolerance, and why. Bank drop and float change are not configurable.
 */
@Data
@Schema(description = "The two configurable movement types, the over/short tolerance and a justification")
public class UpdateSessionPolicyRequest {

    @Schema(description = "Petty expenses", requiredMode = REQUIRED)
    private TypeSetting pettyExpense;

    @Schema(
            description = "Vendor cash on delivery; stays off until pos-order holds the vendor list",
            requiredMode = REQUIRED)
    private TypeSetting vendorCod;

    @Schema(
            description = "Over/short above which a close needs order:session:approve_variance",
            example = "5.00",
            requiredMode = REQUIRED)
    private BigDecimal overShortTolerance;

    @Schema(
            description = "Why the policy changes (at least 10 characters)",
            example = "Raise petty limit for winter supplies",
            requiredMode = REQUIRED)
    private String justification;

    /** One configurable movement type. */
    @Data
    @Schema(description = "Whether a movement type is allowed and its cashier limit")
    public static class TypeSetting {

        @Schema(description = "Whether cashiers may record it", requiredMode = REQUIRED)
        private Boolean allowed;

        @Schema(
                description = "Cashier limit on a session's running total; required while allowed",
                example = "50.00",
                requiredMode = NOT_REQUIRED)
        private BigDecimal cashierLimit;
    }
}
