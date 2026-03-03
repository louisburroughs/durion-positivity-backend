package com.positivity.customer.internal.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.positivity.customer.internal.enums.RedemptionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PromotionRedemptionResponse {

    private UUID promotionRedemptionId;
    private UUID promotionId;
    private UUID customerId;
    private UUID workorderId;
    private UUID invoiceId;
    private BigDecimal discountAmount;
    private String discountType;
    private String promotionCode;
    private Boolean recordedOverLimit;
    private RedemptionStatus status;
    private LocalDateTime redemptionTimestamp;
    private LocalDateTime createdAt;
}
