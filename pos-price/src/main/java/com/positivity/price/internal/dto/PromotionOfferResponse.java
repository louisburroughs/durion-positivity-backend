package com.positivity.price.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.positivity.price.internal.enums.DiscountType;
import com.positivity.price.internal.enums.PromotionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** API response payload for promotion offers. Issue: #97 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PromotionOfferResponse {

    private UUID promotionOfferId;
    private String promoCode;
    private String name;
    private String description;
    private DiscountType discountType;
    private BigDecimal discountValue;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer usageLimit;
    private int usageCount;
    private PromotionStatus status;
    private String storeCode;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;

    public UUID getPromotionOfferId() {
        return promotionOfferId;
    }

    public void setPromotionOfferId(UUID promotionOfferId) {
        this.promotionOfferId = promotionOfferId;
    }

    public String getPromoCode() {
        return promoCode;
    }

    public void setPromoCode(String promoCode) {
        this.promoCode = promoCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Nullable
    public String getDescription() {
        return description;
    }

    public void setDescription(@Nullable String description) {
        this.description = description;
    }

    public DiscountType getDiscountType() {
        return discountType;
    }

    public void setDiscountType(DiscountType discountType) {
        this.discountType = discountType;
    }

    public BigDecimal getDiscountValue() {
        return discountValue;
    }

    public void setDiscountValue(BigDecimal discountValue) {
        this.discountValue = discountValue;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    @Nullable
    public Integer getUsageLimit() {
        return usageLimit;
    }

    public void setUsageLimit(@Nullable Integer usageLimit) {
        this.usageLimit = usageLimit;
    }

    public int getUsageCount() {
        return usageCount;
    }

    public void setUsageCount(int usageCount) {
        this.usageCount = usageCount;
    }

    public PromotionStatus getStatus() {
        return status;
    }

    public void setStatus(PromotionStatus status) {
        this.status = status;
    }

    @Nullable
    public String getStoreCode() {
        return storeCode;
    }

    public void setStoreCode(@Nullable String storeCode) {
        this.storeCode = storeCode;
    }

    @Nullable
    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(@Nullable Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Nullable
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(@Nullable Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Nullable
    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(@Nullable String createdBy) {
        this.createdBy = createdBy;
    }
}