package com.positivity.price.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.price.internal.entity.LaborRate;
import com.positivity.price.internal.entity.LaborRateAdjustment;
import com.positivity.price.internal.enums.LaborRateAdjustmentType;
import com.positivity.price.internal.enums.ServiceOperationCategory;
import com.positivity.price.internal.repository.LaborRateAdjustmentRepository;
import com.positivity.price.internal.repository.LaborRateRepository;
import com.positivity.price.service.model.LaborRateQuoteRequest;
import com.positivity.price.service.model.LaborRateQuoteResponse;
import com.positivity.price.service.model.LaborRateQuoteResponse.Scope;
import com.positivity.price.service.model.LaborRateQuoteResponse.Status;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Rate resolution and matrix arithmetic (#1575 Tier 0, T0-3): the narrowest scope in force wins,
 * matrix steps compound in sequence, a missing rate is a typed status rather than an exception.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LaborRateResolutionServiceImpl")
class LaborRateResolutionServiceImplTest {

    private static final UUID SHOP_A = UUID.fromString("0198f2a1-0000-7000-8000-00000000000a");
    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");

    @Mock
    private LaborRateRepository rateRepository;

    @Mock
    private LaborRateAdjustmentRepository adjustmentRepository;

    private LaborRateResolutionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LaborRateResolutionServiceImpl(
                rateRepository, adjustmentRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        when(rateRepository.findCandidates(any(), any(), any())).thenReturn(List.of());
        when(adjustmentRepository.findApplicable(any(), any(), any(), any())).thenReturn(List.of());
    }

    private static LaborRate rate(UUID locationId, ServiceOperationCategory category, String hourly) {
        LaborRate rate = new LaborRate();
        rate.setId(UUID.randomUUID());
        rate.setLocationId(locationId);
        rate.setOperationCategory(category);
        rate.setCurrency("USD");
        rate.setHourlyRate(new BigDecimal(hourly));
        rate.setEffectiveFrom(Instant.parse("2026-01-01T00:00:00Z"));
        return rate;
    }

    private static LaborRateAdjustment step(String code, LaborRateAdjustmentType type, String value, int sequence) {
        LaborRateAdjustment step = new LaborRateAdjustment();
        step.setId(UUID.randomUUID());
        step.setAdjustmentCode(code);
        step.setAdjustmentType(type);
        step.setAdjustmentValue(new BigDecimal(value));
        step.setSequence(sequence);
        step.setEffectiveFrom(Instant.parse("2026-01-01T00:00:00Z"));
        return step;
    }

    @Nested
    @DisplayName("scope resolution")
    class ScopeResolution {

        @Test
        @DisplayName("the location's own category rate beats every wider scope in the candidate set")
        void narrowestScopeWins() {
            when(rateRepository.findCandidates(eq(SHOP_A), eq("TIRE_SERVICE"), any()))
                    .thenReturn(List.of(
                            rate(null, null, "125.00"),
                            rate(null, ServiceOperationCategory.TIRE_SERVICE, "95.00"),
                            rate(SHOP_A, null, "142.00"),
                            rate(SHOP_A, ServiceOperationCategory.TIRE_SERVICE, "105.00")));

            LaborRateQuoteResponse response = service.resolve(LaborRateQuoteRequest.of(SHOP_A, "TIRE_SERVICE"));

            assertThat(response.status()).isEqualTo(Status.RESOLVED);
            assertThat(response.hourlyRate()).isEqualByComparingTo("105.00");
            assertThat(response.scope()).isEqualTo(Scope.LOCATION_CATEGORY);
            assertThat(response.currency()).isEqualTo("USD");
        }

        @Test
        @DisplayName("a location's own general rate beats the platform's category rate — scope narrows before category")
        void locationDefaultBeatsPlatformCategory() {
            when(rateRepository.findCandidates(eq(SHOP_A), eq("TIRE_SERVICE"), any()))
                    .thenReturn(List.of(
                            rate(null, ServiceOperationCategory.TIRE_SERVICE, "95.00"), rate(SHOP_A, null, "118.00")));

            LaborRateQuoteResponse response = service.resolve(LaborRateQuoteRequest.of(SHOP_A, "TIRE_SERVICE"));

            assertThat(response.hourlyRate()).isEqualByComparingTo("118.00");
            assertThat(response.scope()).isEqualTo(Scope.LOCATION_DEFAULT);
        }

        @Test
        @DisplayName("no rate in force is a typed miss, never an exception — the writer types the price")
        void missIsTyped() {
            LaborRateQuoteResponse response = service.resolve(LaborRateQuoteRequest.of(SHOP_A, "REPAIR"));

            assertThat(response.status()).isEqualTo(Status.NO_RATE_AVAILABLE);
            assertThat(response.hourlyRate()).isNull();
            assertThat(response.scope()).isNull();
            assertThat(response.steps()).isEmpty();
        }

        @Test
        @DisplayName(
                "an unknown category widens rather than failing — vocabulary drift costs precision, not availability")
        void unknownCategoryWidens() {
            when(rateRepository.findCandidates(eq(SHOP_A), eq("BODYWORK"), any()))
                    .thenReturn(List.of(rate(SHOP_A, null, "142.00")));

            assertThat(service.resolve(LaborRateQuoteRequest.of(SHOP_A, "bodywork"))
                            .status())
                    .isEqualTo(Status.RESOLVED);
        }

        @Test
        @DisplayName("the request's instant is what is priced at, so re-quoting an old estimate reproduces its rate")
        void requestedInstantIsUsed() {
            Instant backThen = Instant.parse("2026-03-01T00:00:00Z");
            service.resolve(new LaborRateQuoteRequest(SHOP_A, "REPAIR", List.of(), backThen));

            ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
            verify(rateRepository).findCandidates(eq(SHOP_A), eq("REPAIR"), at.capture());
            assertThat(at.getValue()).isEqualTo(backThen);
        }
    }

    @Nested
    @DisplayName("the labor matrix")
    class Matrix {

        @BeforeEach
        void rateInForce() {
            when(rateRepository.findCandidates(any(), any(), any())).thenReturn(List.of(rate(SHOP_A, null, "100.00")));
        }

        @Test
        @DisplayName("percentage steps compound in sequence order, and each step reports the rate it produced")
        void percentStepsCompoundInOrder() {
            when(adjustmentRepository.findApplicable(any(), any(), any(), any()))
                    .thenReturn(List.of(
                            step("CORROSION", LaborRateAdjustmentType.PERCENT, "15.0", 10),
                            step("AFTER_HOURS", LaborRateAdjustmentType.PERCENT, "25.0", 30)));

            LaborRateQuoteResponse response =
                    service.resolve(new LaborRateQuoteRequest(SHOP_A, null, List.of("CORROSION", "AFTER_HOURS"), null));

            // 100 -> +15% -> 115 -> +25% -> 143.75. A naive additive +40% would give 140.
            assertThat(response.baseHourlyRate()).isEqualByComparingTo("100.00");
            assertThat(response.hourlyRate()).isEqualByComparingTo("143.75");
            assertThat(response.steps()).hasSize(2);
            assertThat(response.steps().get(0).resultingRate()).isEqualByComparingTo("115.00");
            assertThat(response.steps().get(1).resultingRate()).isEqualByComparingTo("143.75");
        }

        @Test
        @DisplayName("a fixed step adds to the running rate, after the percentages that precede it")
        void fixedStepAddsToRunningRate() {
            when(adjustmentRepository.findApplicable(any(), any(), any(), any()))
                    .thenReturn(List.of(
                            step("CORROSION", LaborRateAdjustmentType.PERCENT, "15.0", 10),
                            step("MOBILE_CALLOUT", LaborRateAdjustmentType.FIXED, "35.0", 40)));

            LaborRateQuoteResponse response = service.resolve(
                    new LaborRateQuoteRequest(SHOP_A, null, List.of("CORROSION", "MOBILE_CALLOUT"), null));

            assertThat(response.hourlyRate()).isEqualByComparingTo("150.00");
        }

        @Test
        @DisplayName("a discount applies to the adjusted rate, not the base — which is what its sequence means")
        void discountAppliesToTheAdjustedRate() {
            when(adjustmentRepository.findApplicable(any(), any(), any(), any()))
                    .thenReturn(List.of(
                            step("CORROSION", LaborRateAdjustmentType.PERCENT, "15.0", 10),
                            step("FLEET_CONTRACT", LaborRateAdjustmentType.PERCENT, "-10.0", 90)));

            LaborRateQuoteResponse response = service.resolve(
                    new LaborRateQuoteRequest(SHOP_A, null, List.of("CORROSION", "FLEET_CONTRACT"), null));

            // 100 -> 115 -> 103.50. Off the base it would have been 105.
            assertThat(response.hourlyRate()).isEqualByComparingTo("103.50");
        }

        @Test
        @DisplayName("a discount deeper than the rate clamps at zero rather than inverting the charge")
        void discountNeverInvertsTheRate() {
            when(adjustmentRepository.findApplicable(any(), any(), any(), any()))
                    .thenReturn(List.of(step("WRITE_OFF", LaborRateAdjustmentType.PERCENT, "-150.0", 10)));

            assertThat(service.resolve(new LaborRateQuoteRequest(SHOP_A, null, List.of("WRITE_OFF"), null))
                            .hourlyRate())
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("naming no codes skips the matrix lookup entirely")
        void noCodesSkipsTheMatrixQuery() {
            LaborRateQuoteResponse response = service.resolve(LaborRateQuoteRequest.of(SHOP_A, null));

            assertThat(response.hourlyRate()).isEqualByComparingTo("100.00");
            assertThat(response.steps()).isEmpty();
            verify(adjustmentRepository, never()).findApplicable(any(), any(), anyString(), any());
        }

        @Test
        @DisplayName("codes are uppercased and de-duplicated before the lookup, so a repeat applies once")
        void codesAreNormalizedAndDeduplicated() {
            service.resolve(new LaborRateQuoteRequest(SHOP_A, null, List.of("corrosion", "CORROSION", " "), null));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Collection<String>> codes = ArgumentCaptor.forClass(java.util.Collection.class);
            verify(adjustmentRepository).findApplicable(codes.capture(), any(), any(), any());
            assertThat(codes.getValue()).containsExactly("CORROSION");
        }

        @Test
        @DisplayName("a code the shop has not priced is simply not applied — the base rate still answers")
        void unpricedCodeLeavesTheBaseRate() {
            LaborRateQuoteResponse response =
                    service.resolve(new LaborRateQuoteRequest(SHOP_A, null, List.of("NOT_A_STEP"), null));

            assertThat(response.status()).isEqualTo(Status.RESOLVED);
            assertThat(response.hourlyRate()).isEqualByComparingTo("100.00");
            assertThat(response.steps()).isEmpty();
        }
    }
}
