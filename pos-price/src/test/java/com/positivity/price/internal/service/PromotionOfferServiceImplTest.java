package com.positivity.price.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.positivity.price.internal.dto.ApplyPromotionRequest;
import com.positivity.price.internal.dto.ApplyPromotionResponse;
import com.positivity.price.internal.entity.PromotionOffer;
import com.positivity.price.internal.enums.DiscountType;
import com.positivity.price.internal.enums.PromotionStatus;
import com.positivity.price.internal.exception.PromotionCodeNotFoundException;
import com.positivity.price.internal.exception.PromotionMultipleNotAllowedException;
import com.positivity.price.internal.exception.PromotionNotApplicableException;
import com.positivity.price.internal.repository.PromotionOfferRepository;
import com.positivity.price.service.EligibilityDecision;
import com.positivity.price.service.EligibilityEvaluationService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PromotionOfferServiceImplTest {

    @Mock
    private PromotionOfferRepository promotionOfferRepository;

    @Mock
    private EligibilityEvaluationService eligibilityEvaluationService;

    @InjectMocks
    private PromotionOfferServiceImpl promotionOfferService;

    private PromotionOffer testPromo;

    @BeforeEach
    void setUp() {
        testPromo = new PromotionOffer();
        testPromo.setPromoCode("TESTCODE");
        testPromo.setStatus(PromotionStatus.ACTIVE);
        testPromo.setStartDate(OffsetDateTime.now().minusDays(1));
        testPromo.setEndDate(OffsetDateTime.now().plusDays(1));
        testPromo.setDiscountType(DiscountType.PERCENT_LABOR);
        testPromo.setDiscountValue(BigDecimal.TEN);
        testPromo.setUsageCount(0);
        testPromo.setUsageLimit(100);
    }

    @Test
    @DisplayName("applyPromotion: Happy path with PERCENT_LABOR returns negative discount")
    void applyPromotion_happyPath_percentLabor() {
        when(promotionOfferRepository.findByPromoCodeIgnoreCase("TESTCODE")).thenReturn(Optional.of(testPromo));
        when(eligibilityEvaluationService.evaluate(any(), any())).thenReturn(EligibilityDecision.eligible());

        ApplyPromotionRequest request = new ApplyPromotionRequest("TESTCODE", null, Collections.emptySet());
        ApplyPromotionResponse response = promotionOfferService.applyPromotion(request);

        assertThat(response).isNotNull();
        assertThat(response.appliedDiscount()).isEqualTo(BigDecimal.TEN.negate());
        assertThat(response.finalPromoCode()).isEqualTo("TESTCODE");
    }

    @Test
    @DisplayName("applyPromotion: FIXED_INVOICE discount returns exact negative discount value")
    void applyPromotion_fixedInvoice_returnsExactDiscount() {
        testPromo.setDiscountType(DiscountType.FIXED_INVOICE);
        testPromo.setDiscountValue(new BigDecimal("50.00"));
        when(promotionOfferRepository.findByPromoCodeIgnoreCase("TESTCODE")).thenReturn(Optional.of(testPromo));
        when(eligibilityEvaluationService.evaluate(any(), any())).thenReturn(EligibilityDecision.eligible());

        ApplyPromotionRequest request = new ApplyPromotionRequest("TESTCODE", null, Collections.emptySet());
        ApplyPromotionResponse response = promotionOfferService.applyPromotion(request);

        assertThat(response).isNotNull();
        assertThat(response.appliedDiscount()).isEqualTo(new BigDecimal("-50.00"));
    }

    @Test
    @DisplayName("applyPromotion: Throws PromotionCodeNotFoundException when promo code does not exist")
    void applyPromotion_throwsPromotionCodeNotFoundException() {
        when(promotionOfferRepository.findByPromoCodeIgnoreCase(anyString())).thenReturn(Optional.empty());
        ApplyPromotionRequest request = new ApplyPromotionRequest("NOTFOUND", null, Collections.emptySet());

        assertThatThrownBy(() -> promotionOfferService.applyPromotion(request))
                .isInstanceOf(PromotionCodeNotFoundException.class)
                .hasMessage("Promotion code 'NOTFOUND' not found.");
    }

    @Test
    @DisplayName("applyPromotion: Throws PromotionNotApplicableException for INACTIVE offer")
    void applyPromotion_throwsNotApplicable_forInactiveOffer() {
        testPromo.setStatus(PromotionStatus.INACTIVE);
        when(promotionOfferRepository.findByPromoCodeIgnoreCase("TESTCODE")).thenReturn(Optional.of(testPromo));
        ApplyPromotionRequest request = new ApplyPromotionRequest("TESTCODE", null, Collections.emptySet());

        assertThatThrownBy(() -> promotionOfferService.applyPromotion(request))
                .isInstanceOf(PromotionNotApplicableException.class)
                .hasMessage("Promotion 'TESTCODE' is not active.");
    }

    @Test
    @DisplayName("applyPromotion: Throws PromotionNotApplicableException for past end date")
    void applyPromotion_throwsNotApplicable_forPastEndDate() {
        testPromo.setEndDate(OffsetDateTime.now().minusDays(1));
        when(promotionOfferRepository.findByPromoCodeIgnoreCase("TESTCODE")).thenReturn(Optional.of(testPromo));
        ApplyPromotionRequest request = new ApplyPromotionRequest("TESTCODE", null, Collections.emptySet());

        assertThatThrownBy(() -> promotionOfferService.applyPromotion(request))
                .isInstanceOf(PromotionNotApplicableException.class)
                .hasMessage("Promotion 'TESTCODE' is outside its valid date range.");
    }

    @Test
    @DisplayName("applyPromotion: Throws PromotionNotApplicableException for future start date")
    void applyPromotion_throwsNotApplicable_forFutureStartDate() {
        testPromo.setStartDate(OffsetDateTime.now().plusDays(1));
        when(promotionOfferRepository.findByPromoCodeIgnoreCase("TESTCODE")).thenReturn(Optional.of(testPromo));
        ApplyPromotionRequest request = new ApplyPromotionRequest("TESTCODE", null, Collections.emptySet());

        assertThatThrownBy(() -> promotionOfferService.applyPromotion(request))
                .isInstanceOf(PromotionNotApplicableException.class)
                .hasMessage("Promotion 'TESTCODE' is outside its valid date range.");
    }

    @Test
    @DisplayName("applyPromotion: Throws PromotionNotApplicableException when not eligible")
    void applyPromotion_throwsNotApplicable_whenNotEligible() {
        when(promotionOfferRepository.findByPromoCodeIgnoreCase("TESTCODE")).thenReturn(Optional.of(testPromo));
        when(eligibilityEvaluationService.evaluate(any(), any()))
                .thenReturn(EligibilityDecision.notEligible("NOT_ELIGIBLE"));
        ApplyPromotionRequest request = new ApplyPromotionRequest("TESTCODE", null, Collections.emptySet());

        assertThatThrownBy(() -> promotionOfferService.applyPromotion(request))
                .isInstanceOf(PromotionNotApplicableException.class)
                .hasMessage("Promotion 'TESTCODE' is not applicable: NOT_ELIGIBLE");
    }

    @Test
    @DisplayName("applyPromotion: Throws PromotionMultipleNotAllowedException for multiple promos")
    void applyPromotion_throwsMultipleNotAllowed() {
        ApplyPromotionRequest request = new ApplyPromotionRequest("TESTCODE", null, Set.of("OTHERPROMO"));

        assertThatThrownBy(() -> promotionOfferService.applyPromotion(request))
                .isInstanceOf(PromotionMultipleNotAllowedException.class)
                .hasMessage("Another promotion has already been applied; multiple promotions are not allowed.");
    }
}
