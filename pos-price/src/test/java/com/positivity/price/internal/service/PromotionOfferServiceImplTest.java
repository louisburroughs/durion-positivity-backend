package com.positivity.price.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.price.internal.dto.ApplyPromotionRequest;
import com.positivity.price.internal.dto.ApplyPromotionResponse;
import com.positivity.price.internal.dto.EligibilityDecision;
import com.positivity.price.internal.dto.EstimateContext;
import com.positivity.price.internal.dto.LineItemContext;
import com.positivity.price.internal.dto.PromotionOfferResponse;
import com.positivity.price.internal.entity.PromotionOffer;
import com.positivity.price.internal.enums.DiscountType;
import com.positivity.price.internal.enums.EligibilityReasonCode;
import com.positivity.price.internal.enums.PromotionStatus;
import com.positivity.price.internal.exception.PromotionCodeNotFoundException;
import com.positivity.price.internal.exception.PromotionMultipleNotAllowedException;
import com.positivity.price.internal.exception.PromotionNotApplicableException;
import com.positivity.price.internal.repository.PromotionOfferRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PromotionOfferServiceImplTest {

    @Mock
    private PromotionOfferRepository promotionOfferRepository;

    @Mock
    private EligibilityEvaluationService eligibilityEvaluationService;

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    @InjectMocks
    private PromotionOfferServiceImpl promotionOfferService;

    private PromotionOffer testPromo;

    @BeforeEach
    void setUp() {
        testPromo = new PromotionOffer();
        testPromo.setPromotionOfferId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        testPromo.setPromoCode("TESTCODE");
        testPromo.setStatus(PromotionStatus.ACTIVE);
        testPromo.setStartDate(LocalDate.now(TEST_CLOCK).minusDays(1));
        testPromo.setEndDate(LocalDate.now(TEST_CLOCK).plusDays(1));
        testPromo.setDiscountType(DiscountType.PERCENT_LABOR);
        testPromo.setDiscountValue(BigDecimal.TEN);
        testPromo.setUsageCount(0);
        testPromo.setUsageLimit(100);
    }

    private EstimateContext validEstimateContext() {
        LineItemContext line = new LineItemContext("SKU-001", BigDecimal.ONE, new BigDecimal("100.00"));
        return new EstimateContext(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                null,
                List.of(line),
                new BigDecimal("100.00"),
                null);
    }

    private EstimateContext estimateContextWithPromos(List<String> applied) {
        LineItemContext line = new LineItemContext("SKU-001", BigDecimal.ONE, new BigDecimal("100.00"));
        return new EstimateContext(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                null,
                List.of(line),
                new BigDecimal("100.00"),
                applied);
    }

    @Test
    @DisplayName("applyPromotion: Happy path with PERCENT_LABOR returns negative discount")
    void applyPromotion_happyPath_percentLabor() {
        when(promotionOfferRepository.findByPromoCode("TESTCODE")).thenReturn(Optional.of(testPromo));
        when(eligibilityEvaluationService.evaluateEligibility(any(), any(), any()))
                .thenReturn(new EligibilityDecision(true, EligibilityReasonCode.ELIGIBLE));
        when(promotionOfferRepository.incrementUsageCountIfUnderLimit(testPromo.getPromotionOfferId()))
                .thenReturn(1);

        ApplyPromotionRequest request = new ApplyPromotionRequest("TESTCODE", validEstimateContext());
        ApplyPromotionResponse response = promotionOfferService.applyPromotion(request);

        assertThat(response).isNotNull();
        assertThat(response.getTotal()).isEqualByComparingTo(new BigDecimal("90.00"));
        assertThat(response.getAppliedAdjustments()).hasSize(1);
        assertThat(response.getAppliedAdjustments().get(0).getAmount()).isEqualByComparingTo(BigDecimal.TEN.negate());
        verify(promotionOfferRepository).incrementUsageCountIfUnderLimit(testPromo.getPromotionOfferId());
    }

    @Test
    @DisplayName("applyPromotion: FIXED_INVOICE discount returns exact negative discount value")
    void applyPromotion_fixedInvoice_returnsExactDiscount() {
        testPromo.setDiscountType(DiscountType.FIXED_INVOICE);
        testPromo.setDiscountValue(new BigDecimal("50.00"));
        when(promotionOfferRepository.findByPromoCode("TESTCODE")).thenReturn(Optional.of(testPromo));
        when(eligibilityEvaluationService.evaluateEligibility(any(), any(), any()))
                .thenReturn(new EligibilityDecision(true, EligibilityReasonCode.ELIGIBLE));
        when(promotionOfferRepository.incrementUsageCountIfUnderLimit(testPromo.getPromotionOfferId()))
                .thenReturn(1);

        ApplyPromotionRequest request = new ApplyPromotionRequest("TESTCODE", validEstimateContext());
        ApplyPromotionResponse response = promotionOfferService.applyPromotion(request);

        assertThat(response).isNotNull();
        assertThat(response.getTotal()).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    @DisplayName("applyPromotion: Throws PromotionCodeNotFoundException when promo code does not exist")
    void applyPromotion_throwsPromotionCodeNotFoundException() {
        when(promotionOfferRepository.findByPromoCode(anyString())).thenReturn(Optional.empty());
        ApplyPromotionRequest request = new ApplyPromotionRequest("NOTFOUND", validEstimateContext());

        assertThatThrownBy(() -> promotionOfferService.applyPromotion(request))
                .isInstanceOf(PromotionCodeNotFoundException.class)
                .hasMessage("Promotion code not found: NOTFOUND");
    }

    @Test
    @DisplayName("applyPromotion: Throws PromotionNotApplicableException for INACTIVE offer")
    void applyPromotion_throwsNotApplicable_forInactiveOffer() {
        testPromo.setStatus(PromotionStatus.INACTIVE);
        when(promotionOfferRepository.findByPromoCode("TESTCODE")).thenReturn(Optional.of(testPromo));
        ApplyPromotionRequest request = new ApplyPromotionRequest("TESTCODE", validEstimateContext());

        assertThatThrownBy(() -> promotionOfferService.applyPromotion(request))
                .isInstanceOf(PromotionNotApplicableException.class)
                .hasMessage("Promotion 'TESTCODE' is not active");
    }

    @Test
    @DisplayName("applyPromotion: Throws PromotionNotApplicableException for past end date")
    void applyPromotion_throwsNotApplicable_forPastEndDate() {
        testPromo.setEndDate(LocalDate.now(TEST_CLOCK).minusDays(1));
        when(promotionOfferRepository.findByPromoCode("TESTCODE")).thenReturn(Optional.of(testPromo));
        ApplyPromotionRequest request = new ApplyPromotionRequest("TESTCODE", validEstimateContext());

        assertThatThrownBy(() -> promotionOfferService.applyPromotion(request))
                .isInstanceOf(PromotionNotApplicableException.class)
                .hasMessage("Promotion 'TESTCODE' is outside its valid date range");
    }

    @Test
    @DisplayName("applyPromotion: Throws PromotionNotApplicableException for future start date")
    void applyPromotion_throwsNotApplicable_forFutureStartDate() {
        testPromo.setStartDate(LocalDate.now(TEST_CLOCK).plusDays(1));
        when(promotionOfferRepository.findByPromoCode("TESTCODE")).thenReturn(Optional.of(testPromo));
        ApplyPromotionRequest request = new ApplyPromotionRequest("TESTCODE", validEstimateContext());

        assertThatThrownBy(() -> promotionOfferService.applyPromotion(request))
                .isInstanceOf(PromotionNotApplicableException.class)
                .hasMessage("Promotion 'TESTCODE' is outside its valid date range");
    }

    @Test
    @DisplayName("applyPromotion: Throws PromotionNotApplicableException when not eligible")
    void applyPromotion_throwsNotApplicable_whenNotEligible() {
        when(promotionOfferRepository.findByPromoCode("TESTCODE")).thenReturn(Optional.of(testPromo));
        when(eligibilityEvaluationService.evaluateEligibility(any(), any(), any()))
                .thenReturn(new EligibilityDecision(false, EligibilityReasonCode.ACCOUNT_NOT_IN_LIST));
        ApplyPromotionRequest request = new ApplyPromotionRequest("TESTCODE", validEstimateContext());

        assertThatThrownBy(() -> promotionOfferService.applyPromotion(request))
                .isInstanceOf(PromotionNotApplicableException.class)
                .hasMessage("Promotion 'TESTCODE' is not applicable: ACCOUNT_NOT_IN_LIST");
    }

    @Test
    @DisplayName("applyPromotion: Throws PromotionNotApplicableException when usage limit is reached")
    void applyPromotion_throwsNotApplicable_whenUsageLimitReached() {
        testPromo.setUsageLimit(1);
        testPromo.setUsageCount(1);
        when(promotionOfferRepository.findByPromoCode("TESTCODE")).thenReturn(Optional.of(testPromo));
        when(eligibilityEvaluationService.evaluateEligibility(any(), any(), any()))
                .thenReturn(new EligibilityDecision(true, EligibilityReasonCode.ELIGIBLE));
        when(promotionOfferRepository.incrementUsageCountIfUnderLimit(testPromo.getPromotionOfferId()))
                .thenReturn(0);

        ApplyPromotionRequest request = new ApplyPromotionRequest("TESTCODE", validEstimateContext());

        assertThatThrownBy(() -> promotionOfferService.applyPromotion(request))
                .isInstanceOf(PromotionNotApplicableException.class)
                .hasMessage("Promotion 'TESTCODE' has reached its usage limit");
    }

    @Test
    @DisplayName("applyPromotion: Throws PromotionMultipleNotAllowedException for multiple promos")
    void applyPromotion_throwsMultipleNotAllowed() {
        ApplyPromotionRequest request =
                new ApplyPromotionRequest("TESTCODE", estimateContextWithPromos(List.of("OTHERPROMO")));

        assertThatThrownBy(() -> promotionOfferService.applyPromotion(request))
                .isInstanceOf(PromotionMultipleNotAllowedException.class)
                .hasMessage("Only one promotion may be applied per estimate");
    }

    /**
     * save() on an already-managed entity does not flush, so @LastModifiedDate has not run yet
     * when the mapper reads it; only saveAndFlush() forces the @PreUpdate callback that stamps
     * updatedAt before the entity is mapped to a response. The stub below mirrors that: save()
     * returns the entity untouched (as an unflushed managed save would), while saveAndFlush()
     * applies the auditing update, so this test fails if activateOffer/deactivateOffer regress
     * to calling save() instead of saveAndFlush().
     */
    private static final Instant STALE_UPDATED_AT = Instant.parse("2023-12-01T00:00:00Z");

    private static final Instant FRESH_UPDATED_AT = Instant.parse("2024-01-01T00:00:00Z");

    private void stubSaveAndFlushAppliesAuditing() {
        lenient()
                .when(promotionOfferRepository.save(any(PromotionOffer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(promotionOfferRepository.saveAndFlush(any(PromotionOffer.class))).thenAnswer(invocation -> {
            PromotionOffer offer = invocation.getArgument(0);
            offer.setUpdatedAt(FRESH_UPDATED_AT);
            return offer;
        });
    }

    @Test
    @DisplayName("activateOffer: returned response reflects post-flush updatedAt")
    void activateOffer_returnsResponseWithFreshUpdatedAt() {
        testPromo.setStatus(PromotionStatus.DRAFT);
        testPromo.setUpdatedAt(STALE_UPDATED_AT);
        when(promotionOfferRepository.findById(testPromo.getPromotionOfferId())).thenReturn(Optional.of(testPromo));
        stubSaveAndFlushAppliesAuditing();

        PromotionOfferResponse response = promotionOfferService.activateOffer(testPromo.getPromotionOfferId());

        assertThat(response.getStatus()).isEqualTo(PromotionStatus.ACTIVE);
        assertThat(response.getUpdatedAt()).isEqualTo(FRESH_UPDATED_AT);
        assertThat(response.getUpdatedAt()).isNotEqualTo(STALE_UPDATED_AT);
    }

    @Test
    @DisplayName("deactivateOffer: returned response reflects post-flush updatedAt")
    void deactivateOffer_returnsResponseWithFreshUpdatedAt() {
        testPromo.setStatus(PromotionStatus.ACTIVE);
        testPromo.setUpdatedAt(STALE_UPDATED_AT);
        when(promotionOfferRepository.findById(testPromo.getPromotionOfferId())).thenReturn(Optional.of(testPromo));
        stubSaveAndFlushAppliesAuditing();

        PromotionOfferResponse response = promotionOfferService.deactivateOffer(testPromo.getPromotionOfferId());

        assertThat(response.getStatus()).isEqualTo(PromotionStatus.INACTIVE);
        assertThat(response.getUpdatedAt()).isEqualTo(FRESH_UPDATED_AT);
        assertThat(response.getUpdatedAt()).isNotEqualTo(STALE_UPDATED_AT);
    }
}
