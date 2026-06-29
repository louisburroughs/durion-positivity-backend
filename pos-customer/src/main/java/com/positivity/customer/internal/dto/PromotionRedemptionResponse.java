package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.positivity.customer.internal.enums.RedemptionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Result of recording a promotion redemption for a customer")
public class PromotionRedemptionResponse {

    @Schema(
            description = "Unique identifier of the promotion redemption record",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID promotionRedemptionId;

    @Schema(
            description = "Identifier of the redeemed promotion",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID promotionId;

    @Schema(
            description = "Identifier of the customer who redeemed the promotion",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = REQUIRED)
    @NotNull
    private UUID customerId;

    @Schema(
            description = "Identifier of the workorder the redemption was applied to",
            example = "01960003-0000-7000-8000-000000000004",
            requiredMode = REQUIRED)
    @NotNull
    private UUID workorderId;

    @Schema(
            description = "Identifier of the invoice the redemption was applied to, if any",
            example = "01960003-0000-7000-8000-000000000005",
            requiredMode = NOT_REQUIRED)
    private UUID invoiceId;

    @Schema(description = "Discount amount applied by the redemption", example = "25.00", requiredMode = REQUIRED)
    @NotNull
    private BigDecimal discountAmount;

    @Schema(description = "Type of discount applied", example = "PERCENTAGE", requiredMode = REQUIRED)
    @NotNull
    private String discountType;

    @Schema(description = "Promotion code that was redeemed", example = "SUMMER25", requiredMode = REQUIRED)
    @NotNull
    private String promotionCode;

    @Schema(
            description = "Identifier of the actor who recorded the redemption",
            example = "01960003-0000-7000-8000-000000000006",
            requiredMode = NOT_REQUIRED)
    private String recordedBy;

    @Schema(
            description = "Whether the redemption was recorded over the configured usage limit",
            example = "false",
            requiredMode = NOT_REQUIRED)
    private Boolean recordedOverLimit;

    @Schema(description = "Current status of the redemption", example = "RECORDED", requiredMode = REQUIRED)
    @NotNull
    private RedemptionStatus status;

    @Schema(
            description = "Timestamp when the redemption occurred",
            example = "2026-02-20T14:45:00",
            requiredMode = NOT_REQUIRED)
    private LocalDateTime redemptionTimestamp;

    @Schema(
            description = "Timestamp when the redemption record was created",
            example = "2026-02-20T14:45:01",
            requiredMode = REQUIRED)
    @NotNull
    private LocalDateTime createdAt;
}
