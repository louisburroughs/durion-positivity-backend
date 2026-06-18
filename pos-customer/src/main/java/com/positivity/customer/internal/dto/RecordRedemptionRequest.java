package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to record a promotion redemption for a customer")
public class RecordRedemptionRequest {

    @Schema(
            description = "Identifier of the promotion being redeemed",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID promotionId;

    @Schema(
            description = "Identifier of the customer redeeming the promotion",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = REQUIRED)
    @NotNull
    private UUID customerId;

    @Schema(
            description = "Identifier of the workorder the redemption applies to",
            example = "01960003-0000-7000-8000-000000000004",
            requiredMode = REQUIRED)
    @NotNull
    private UUID workorderId;

    @Schema(
            description = "Identifier of the invoice the redemption applies to, if any",
            example = "01960003-0000-7000-8000-000000000005",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID invoiceId;

    @Schema(
            description = "Discount amount to record for the redemption",
            example = "25.00",
            requiredMode = REQUIRED)
    @NotNull
    @DecimalMin("0.00")
    private BigDecimal discountAmount;

    @Schema(
            description = "Type of discount being recorded",
            example = "PERCENTAGE",
            requiredMode = REQUIRED)
    @NotBlank
    @Size(max = 50)
    private String discountType;

    @Schema(
            description = "Promotion code being redeemed",
            example = "SUMMER25",
            requiredMode = REQUIRED)
    @NotBlank
    @Size(max = 100)
    private String promotionCode;

    @Schema(
            description = "Whether the redemption is being recorded over the configured usage limit",
            example = "false",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private Boolean recordedOverLimit;

    @Schema(
            description = "Timestamp when the redemption occurred",
            example = "2026-02-20T14:45:00",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private LocalDateTime redemptionTimestamp;
}
