package com.positivity.price.internal.dto;

import com.positivity.price.internal.entity.PromotionOffer;

/** Maps promotion offer domain entities to API response DTOs. Issue: #97 */
public final class PromotionOfferMapper {

    private PromotionOfferMapper() {}

    public static PromotionOfferResponse toResponse(PromotionOffer offer) {
        PromotionOfferResponse response = new PromotionOfferResponse();
        response.setPromotionOfferId(offer.getPromotionOfferId());
        response.setPromoCode(offer.getPromoCode());
        response.setName(offer.getName());
        response.setDescription(offer.getDescription());
        response.setDiscountType(offer.getDiscountType());
        response.setDiscountValue(offer.getDiscountValue());
        response.setStartDate(offer.getStartDate());
        response.setEndDate(offer.getEndDate());
        response.setUsageLimit(offer.getUsageLimit());
        response.setUsageCount(offer.getUsageCount());
        response.setStatus(offer.getStatus());
        response.setStoreCode(offer.getStoreCode());
        response.setCreatedAt(offer.getCreatedAt());
        response.setUpdatedAt(offer.getUpdatedAt());
        response.setCreatedBy(offer.getCreatedBy());
        return response;
    }
}
