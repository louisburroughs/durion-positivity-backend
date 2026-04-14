package com.positivity.customer.internal.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
public class RecordRedemptionRequest {

    @NotNull
    private UUID promotionId;

    @NotNull
    private UUID customerId;

    @NotNull
    private UUID workorderId;

    @Nullable
    private UUID invoiceId;

    @NotNull
    @DecimalMin("0.00")
    private BigDecimal discountAmount;

    @NotBlank
    private String discountType;

    @NotBlank
    private String promotionCode;

    @Nullable
    private Boolean recordedOverLimit;

    @Nullable
    private LocalDateTime redemptionTimestamp;
}
