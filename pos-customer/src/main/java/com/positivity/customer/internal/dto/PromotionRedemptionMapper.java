package com.positivity.customer.internal.dto;

import com.positivity.customer.internal.entity.PromotionRedemption;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.NonNull;

public final class PromotionRedemptionMapper {

    private PromotionRedemptionMapper() {}

    public static PromotionRedemptionResponse toResponse(@NonNull PromotionRedemption redemption) {
        return new PromotionRedemptionResponse(
                redemption.getPromotionRedemptionId(),
                redemption.getPromotionId(),
                redemption.getCustomerId(),
                redemption.getWorkorderId(),
                redemption.getInvoiceId(),
                redemption.getDiscountAmount(),
                redemption.getDiscountType(),
                redemption.getPromotionCode(),
                redemption.getRecordedBy(),
                redemption.getRecordedOverLimit(),
                redemption.getStatus(),
                redemption.getRedemptionTimestamp(),
                redemption.getCreatedAt() != null ? LocalDateTime.ofInstant(redemption.getCreatedAt(), ZoneOffset.UTC) : null);
    }
}
