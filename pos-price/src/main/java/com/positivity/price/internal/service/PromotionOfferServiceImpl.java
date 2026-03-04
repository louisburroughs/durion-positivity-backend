package com.positivity.price.internal.service;

import com.positivity.price.internal.dto.ApplyPromotionRequest;
import com.positivity.price.internal.dto.ApplyPromotionResponse;
import com.positivity.price.internal.dto.CreatePromotionOfferRequest;
import com.positivity.price.internal.dto.PricingAdjustment;
import com.positivity.price.internal.entity.PromotionOffer;
import com.positivity.price.internal.enums.PromotionStatus;
import com.positivity.price.internal.exception.DuplicatePromoCodeException;
import com.positivity.price.internal.exception.PromotionCodeNotFoundException;
import com.positivity.price.internal.exception.PromotionMultipleNotAllowedException;
import com.positivity.price.internal.exception.PromotionNotApplicableException;
import com.positivity.price.internal.exception.PromotionOfferNotFoundException;
import com.positivity.price.internal.exception.PromotionOfferStateException;
import com.positivity.price.internal.repository.PromotionOfferRepository;
import com.positivity.price.service.EligibilityDecision;
import com.positivity.price.service.EligibilityEvaluationService;
import com.positivity.price.service.PromotionOfferService;
import com.positivity.security.common.SecurityContextHelper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of promotion offer lifecycle service.
 *
 * Issues: #97, #95
 */
@Service
@Slf4j
public class PromotionOfferServiceImpl implements PromotionOfferService {

    private final PromotionOfferRepository promotionOfferRepository;
    private final EligibilityEvaluationService eligibilityEvaluationService;

    public PromotionOfferServiceImpl(
            PromotionOfferRepository promotionOfferRepository,
            EligibilityEvaluationService eligibilityEvaluationService) {
        this.promotionOfferRepository = promotionOfferRepository;
        this.eligibilityEvaluationService = eligibilityEvaluationService;
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
        if (offer.getEndDate() != null && offer.getEndDate().isBefore(LocalDate.now())) {
            throw new PromotionOfferStateException("Cannot activate a promotion whose end date has already passed");
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

    @Override
    @NonNull
    @Transactional
    public ApplyPromotionResponse applyPromotion(@NonNull ApplyPromotionRequest request) {
        // Guard: only one promotion per estimate
        List<String> already = request.getEstimateContext().getAppliedPromoCodes();
        if (already != null && !already.isEmpty()) {
            throw new PromotionMultipleNotAllowedException();
        }

        String promoCode = request.getPromotionCode();
        var ctx = request.getEstimateContext();

        PromotionOffer offer = promotionOfferRepository.findByPromoCode(promoCode)
                .orElseThrow(() -> new PromotionCodeNotFoundException(promoCode));

        if (offer.getStatus() != PromotionStatus.ACTIVE) {
            throw new PromotionNotApplicableException("Promotion '" + promoCode + "' is not active");
        }

        LocalDate today = LocalDate.now();
        if (today.isBefore(offer.getStartDate()) || today.isAfter(offer.getEndDate())) {
            throw new PromotionNotApplicableException(
                    "Promotion '" + promoCode + "' is outside its valid date range");
        }

        EligibilityDecision decision = eligibilityEvaluationService.evaluateEligibility(
                offer.getPromotionOfferId(),
                ctx.getCustomerId(),
                ctx.getVehicleId());
        if (!decision.isEligible()) {
            throw new PromotionNotApplicableException(
                    "Promotion '" + promoCode + "' is not applicable: " + decision.reasonCode());
        }

        BigDecimal subtotal = ctx.getSubtotal();
        BigDecimal discountAmount = switch (offer.getDiscountType()) {
            case PERCENT_LABOR, PERCENT_PARTS -> subtotal.multiply(offer.getDiscountValue())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                    .negate();
            case FIXED_INVOICE -> offer.getDiscountValue().negate();
        };

        PricingAdjustment adjustment = PricingAdjustment.builder()
                .type("PROMOTION")
                .sourceId(offer.getPromotionOfferId())
                .label(offer.getName() != null ? offer.getName() : offer.getDescription())
                .amount(discountAmount)
                .build();

        BigDecimal total = subtotal.add(discountAmount);

        ApplyPromotionResponse response = ApplyPromotionResponse.builder()
                .subtotal(subtotal)
                .total(total)
                .appliedAdjustments(List.of(adjustment))
                .build();

        String userId = SecurityContextHelper.getCurrentUsernameOrDefault("system");
        log.info("Promotion applied: estimateId={}, promotionId={}, promotionCode={}, userId={}, discountAmount={}",
                ctx.getEstimateId(), offer.getPromotionOfferId(), promoCode, userId, discountAmount);

        return response;
    }
}