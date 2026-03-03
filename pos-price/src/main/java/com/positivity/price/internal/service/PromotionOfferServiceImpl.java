package com.positivity.price.internal.service;

import com.positivity.price.internal.dto.CreatePromotionOfferRequest;
import com.positivity.price.internal.entity.PromotionOffer;
import com.positivity.price.internal.enums.PromotionStatus;
import com.positivity.price.internal.exception.DuplicatePromoCodeException;
import com.positivity.price.internal.exception.PromotionOfferNotFoundException;
import com.positivity.price.internal.exception.PromotionOfferStateException;
import com.positivity.price.internal.repository.PromotionOfferRepository;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.price.service.PromotionOfferService;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of promotion offer lifecycle service.
 *
 * Issue: #97
 */
@Service
public class PromotionOfferServiceImpl implements PromotionOfferService {

    private final PromotionOfferRepository promotionOfferRepository;

    public PromotionOfferServiceImpl(PromotionOfferRepository promotionOfferRepository) {
        this.promotionOfferRepository = promotionOfferRepository;
    }

    @Override
    @NonNull
    @Transactional
    public PromotionOffer createOffer(@NonNull CreatePromotionOfferRequest request) {
        if (promotionOfferRepository.existsByPromoCode(request.getPromoCode())) {
            throw new DuplicatePromoCodeException(request.getPromoCode());
        }

        if (request.getStartDate().isAfter(request.getEndDate())) {
            throw new PromotionOfferStateException("startDate must not be after endDate");
        }

        PromotionOffer offer = new PromotionOffer();
        offer.setPromoCode(request.getPromoCode());
        offer.setName(request.getName());
        offer.setDescription(request.getDescription());
        offer.setDiscountType(request.getDiscountType());
        offer.setDiscountValue(request.getDiscountValue());
        offer.setStartDate(request.getStartDate());
        offer.setEndDate(request.getEndDate());
        offer.setUsageLimit(request.getUsageLimit());
        offer.setStoreCode(request.getStoreCode());
        offer.setStatus(PromotionStatus.DRAFT);
        offer.setCreatedBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"));

        return promotionOfferRepository.save(offer);
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public PromotionOffer getOfferById(@NonNull UUID promotionOfferId) {
        return promotionOfferRepository.findById(promotionOfferId)
                .orElseThrow(() -> new PromotionOfferNotFoundException(promotionOfferId));
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public PromotionOffer getOfferByCode(@NonNull String promoCode) {
        return promotionOfferRepository.findByPromoCode(promoCode)
                .orElseThrow(() -> new PromotionOfferNotFoundException(promoCode));
    }

    @Override
    @NonNull
    @Transactional
    public PromotionOffer activateOffer(@NonNull UUID promotionOfferId) {
        PromotionOffer offer = getOfferById(promotionOfferId);
        if (offer.getStatus() == PromotionStatus.EXPIRED) {
            throw new PromotionOfferStateException("Cannot activate an expired promotion");
        }

        offer.setStatus(PromotionStatus.ACTIVE);
        return promotionOfferRepository.save(offer);
    }

    @Override
    @NonNull
    @Transactional
    public PromotionOffer deactivateOffer(@NonNull UUID promotionOfferId) {
        PromotionOffer offer = getOfferById(promotionOfferId);
        if (offer.getStatus() != PromotionStatus.ACTIVE) {
            throw new PromotionOfferStateException("Only ACTIVE promotions can be deactivated");
        }

        offer.setStatus(PromotionStatus.INACTIVE);
        return promotionOfferRepository.save(offer);
    }
}