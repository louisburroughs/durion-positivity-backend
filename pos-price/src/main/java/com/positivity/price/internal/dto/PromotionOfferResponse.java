package com.positivity.price.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.positivity.price.internal.enums.DiscountType;
import com.positivity.price.internal.enums.PromotionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** API response payload for promotion offers. Issue: #97 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Response payload representing a promotion offer")
public class PromotionOfferResponse {

    @Schema(
            description = "Promotion offer identifier",
            example = "f51d2c5b-a1f2-4f4e-a7cf-4e7b1752e6aa",
            requiredMode = REQUIRED)
    @NotNull
    private UUID promotionOfferId;

    @Schema(description = "Unique promotion code", example = "SUMMER20", requiredMode = REQUIRED)
    @NotNull
    private String promoCode;

    @Schema(description = "Promotion display name", example = "Summer Labor Discount", requiredMode = REQUIRED)
    @NotNull
    private String name;

    @Schema(
            description = "Optional promotion description",
            example = "20% off labor for seasonal service",
            nullable = true,
            requiredMode = NOT_REQUIRED)
    private String description;

    @Schema(description = "Discount strategy", example = "PERCENT_LABOR", requiredMode = REQUIRED)
    @NotNull
    private DiscountType discountType;

    @Schema(description = "Discount value based on discount type", example = "20.00", requiredMode = REQUIRED)
    @NotNull
    private BigDecimal discountValue;

    @Schema(description = "Promotion validity start date", example = "2026-03-01", requiredMode = REQUIRED)
    @NotNull
    private LocalDate startDate;

    @Schema(description = "Promotion validity end date", example = "2026-03-31", requiredMode = REQUIRED)
    @NotNull
    private LocalDate endDate;

    @Schema(description = "Optional usage limit", example = "100", nullable = true, requiredMode = NOT_REQUIRED)
    private Integer usageLimit;

    @Schema(description = "Number of times the promotion has been applied", example = "14", requiredMode = REQUIRED)
    private int usageCount;

    @Schema(description = "Current promotion status", example = "ACTIVE", requiredMode = REQUIRED)
    @NotNull
    private PromotionStatus status;

    @Schema(
            description = "Optional store code scope",
            example = "NYC-001",
            nullable = true,
            requiredMode = NOT_REQUIRED)
    private String storeCode;

    @Schema(
            description = "Record creation timestamp",
            example = "2026-03-01T08:00:00Z",
            nullable = true,
            requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(
            description = "Record last update timestamp",
            example = "2026-03-02T10:15:00Z",
            nullable = true,
            requiredMode = NOT_REQUIRED)
    private Instant updatedAt;

    @Schema(
            description = "User that created the record",
            example = "user-001",
            nullable = true,
            requiredMode = NOT_REQUIRED)
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
