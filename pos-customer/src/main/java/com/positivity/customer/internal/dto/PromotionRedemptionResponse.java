package com.positivity.customer.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.positivity.customer.internal.enums.RedemptionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
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
    private String recordedBy;
    private Boolean recordedOverLimit;
    private RedemptionStatus status;
    private LocalDateTime redemptionTimestamp;
    private Instant createdAt;
}
