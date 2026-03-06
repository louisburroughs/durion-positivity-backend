package com.positivity.price.internal.dto;

import com.positivity.price.internal.enums.DiscountType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** API request payload to create a promotion offer. Issue: #97 */
@Schema(description = "Request payload for creating a promotion offer")
public class CreatePromotionOfferRequest {

    @Schema(description = "Unique promotion code", requiredMode = Schema.RequiredMode.REQUIRED, example = "SUMMER20")
    @NotBlank(message = "promoCode is required")
    @Size(max = 64, message = "promoCode must not exceed 64 characters")
    private String promoCode;

    @Schema(description = "Display name for the promotion", requiredMode = Schema.RequiredMode.REQUIRED, example = "Summer Labor Discount")
    @NotBlank(message = "name is required")
    @Size(max = 255, message = "name must not exceed 255 characters")
    private String name;

    @Schema(description = "Optional promotion description", example = "20% off labor for seasonal service", nullable = true)
    @Nullable
    @Size(max = 255, message = "description must not exceed 255 characters")
    private String description;

    @Schema(description = "Discount strategy to apply", requiredMode = Schema.RequiredMode.REQUIRED, example = "PERCENT_LABOR")
    @NotNull(message = "discountType is required")
    private DiscountType discountType;

    @Schema(description = "Discount value based on selected discount type", requiredMode = Schema.RequiredMode.REQUIRED, example = "20.00")
    @NotNull(message = "discountValue is required")
    @DecimalMin(value = "0.01", message = "discountValue must be at least 0.01")
    @Digits(integer = 10, fraction = 2, message = "discountValue must have up to 10 integer digits and 2 decimal places")
    private BigDecimal discountValue;

    @Schema(description = "Date when promotion becomes valid", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-03-01")
    @NotNull(message = "startDate is required")
    private LocalDate startDate;

    @Schema(description = "Date when promotion expires", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-03-31")
    @NotNull(message = "endDate is required")
    private LocalDate endDate;

    @Schema(description = "Maximum number of times promotion can be applied", example = "100", nullable = true)
    @Nullable
    @Positive(message = "usageLimit must be positive when provided")
    private Integer usageLimit;

    @Schema(description = "Optional store/location code that scopes the promotion", example = "NYC-001", nullable = true)
    @Nullable
    @Size(max = 255, message = "storeCode must not exceed 255 characters")
    private String storeCode;

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

    @Nullable
    public String getStoreCode() {
        return storeCode;
    }

    public void setStoreCode(@Nullable String storeCode) {
        this.storeCode = storeCode;
    }
}
