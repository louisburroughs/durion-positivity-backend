package com.positivity.price.internal.dto;

import com.positivity.price.internal.enums.DiscountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** API request payload to create a promotion offer. Issue: #97 */
public class CreatePromotionOfferRequest {

    @NotBlank
    private String promoCode;

    @NotBlank
    private String name;

    @Nullable
    private String description;

    @NotNull
    private DiscountType discountType;

    @NotNull
    @DecimalMin("0.01")
    private BigDecimal discountValue;

    @NotNull
    private LocalDate startDate;

    @NotNull
    private LocalDate endDate;

    @Nullable
    private Integer usageLimit;

    @Nullable
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