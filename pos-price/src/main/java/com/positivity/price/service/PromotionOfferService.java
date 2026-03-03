package com.positivity.price.service;

import com.positivity.price.internal.dto.CreatePromotionOfferRequest;
import com.positivity.price.internal.entity.PromotionOffer;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** Manages promotion offer lifecycle. Issue: #97 */
public interface PromotionOfferService {

    /** Create a new promotion offer in DRAFT status. Issue: #97 */
    @NonNull
    PromotionOffer createOffer(@NonNull CreatePromotionOfferRequest request);

    /** Get offer by UUID. Issue: #97 */
    @NonNull
    PromotionOffer getOfferById(@NonNull UUID promotionOfferId);

    /** Get offer by promo code. Issue: #97 */
    @NonNull
    PromotionOffer getOfferByCode(@NonNull String promoCode);

    /** Transition offer from DRAFT or INACTIVE to ACTIVE. Issue: #97 */
    @NonNull
    PromotionOffer activateOffer(@NonNull UUID promotionOfferId);

    /** Transition offer to INACTIVE. Issue: #97 */
    @NonNull
    PromotionOffer deactivateOffer(@NonNull UUID promotionOfferId);
}