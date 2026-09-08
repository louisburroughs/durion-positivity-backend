package com.positivity.price.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.price.internal.entity.LaborRate;
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
import java.util.Arrays;
import java.util.Collection;
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
 * What resolution does with the ragged edges of a request (#1575 Tier 0, T0-3).
 *
 * <p>{@link LaborRateResolutionServiceImplTest} covers well-formed queries: which scope wins,
 * how the matrix compounds. This covers what arrives from a caller in another module — an
 * omitted instant, a blank category, a code list with holes in it. Every one of these widens or
 * is dropped rather than erroring, which is the documented contract and the reason a vocabulary
 * drift between two services costs precision instead of availability.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LaborRateResolutionServiceImpl — request normalization")
class LaborRateResolutionNormalizationTest {

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

    @Nested
    @DisplayName("the moment priced at")
    class PricedAt {

        @Test
        @DisplayName("omitting the instant prices at the clock's now, so a fresh quote needs no timestamp")
        void omittedInstantPricesAtNow() {
            service.resolve(new LaborRateQuoteRequest(SHOP_A, "TIRE_SERVICE", List.of(), null));

            verify(rateRepository).findCandidates(SHOP_A, "TIRE_SERVICE", NOW);
        }
    }

    @Nested
    @DisplayName("the operation category")
    class Category {

        @Test
        @DisplayName("omitting the category asks for the category-agnostic rate, not for nothing")
        void omittedCategoryIsNull() {
            service.resolve(new LaborRateQuoteRequest(SHOP_A, null, List.of(), NOW));

            verify(rateRepository).findCandidates(SHOP_A, null, NOW);
        }

        @Test
        @DisplayName("a blank category widens the same way an omitted one does")
        void blankCategoryIsNull() {
            service.resolve(new LaborRateQuoteRequest(SHOP_A, "   ", List.of(), NOW));

            verify(rateRepository).findCandidates(SHOP_A, null, NOW);
        }

        @Test
        @DisplayName("a category in the wrong case still matches — it is uppercased before the lookup")
        void categoryIsUppercased() {
            service.resolve(new LaborRateQuoteRequest(SHOP_A, " tire_service ", List.of(), NOW));

            verify(rateRepository).findCandidates(SHOP_A, "TIRE_SERVICE", NOW);
        }
    }

    @Nested
    @DisplayName("the matrix code list")
    class Codes {

        @Test
        @DisplayName("null and blank entries are dropped, so a sparse list still queries the real codes")
        void holesInTheListAreDropped() {
            when(rateRepository.findCandidates(any(), any(), any()))
                    .thenReturn(List.of(rate(SHOP_A, ServiceOperationCategory.TIRE_SERVICE, "100.00")));

            service.resolve(new LaborRateQuoteRequest(
                    SHOP_A, "TIRE_SERVICE", Arrays.asList("corrosion", null, "   ", "after_hours"), NOW));

            ArgumentCaptor<Collection<String>> codes = ArgumentCaptor.captor();
            verify(adjustmentRepository).findApplicable(codes.capture(), eq(SHOP_A), eq("TIRE_SERVICE"), eq(NOW));
            assertThat(codes.getValue()).containsExactly("CORROSION", "AFTER_HOURS");
        }

        @Test
        @DisplayName("a list of nothing but holes skips the matrix query entirely")
        void allBlankListSkipsTheMatrixQuery() {
            when(rateRepository.findCandidates(any(), any(), any()))
                    .thenReturn(List.of(rate(SHOP_A, ServiceOperationCategory.TIRE_SERVICE, "100.00")));

            LaborRateQuoteResponse response =
                    service.resolve(new LaborRateQuoteRequest(SHOP_A, "TIRE_SERVICE", Arrays.asList(null, "  "), NOW));

            verify(adjustmentRepository, never()).findApplicable(any(), any(), any(), any());
            assertThat(response.steps()).isEmpty();
            assertThat(response.hourlyRate()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("an omitted code list is read as no codes rather than dereferenced")
        void omittedCodeListIsEmpty() {
            when(rateRepository.findCandidates(any(), any(), any()))
                    .thenReturn(List.of(rate(SHOP_A, ServiceOperationCategory.TIRE_SERVICE, "100.00")));

            LaborRateQuoteResponse response =
                    service.resolve(new LaborRateQuoteRequest(SHOP_A, "TIRE_SERVICE", null, NOW));

            verify(adjustmentRepository, never()).findApplicable(any(), any(), any(), any());
            assertThat(response.status()).isEqualTo(Status.RESOLVED);
        }
    }

    @Nested
    @DisplayName("the widest answer")
    class PlatformDefault {

        @Test
        @DisplayName("a request naming no scope at all resolves the platform default and says so")
        void platformDefaultIsReportedAsItsOwnScope() {
            when(rateRepository.findCandidates(null, null, NOW)).thenReturn(List.of(rate(null, null, "125.00")));

            LaborRateQuoteResponse response = service.resolve(new LaborRateQuoteRequest(null, null, List.of(), NOW));

            assertThat(response.status()).isEqualTo(Status.RESOLVED);
            assertThat(response.scope()).isEqualTo(Scope.PLATFORM_DEFAULT);
            assertThat(response.hourlyRate()).isEqualByComparingTo("125.0000");
            assertThat(response.currency()).isEqualTo("USD");
        }

        @Test
        @DisplayName("the platform's category rate is reported as category-scoped, not as the default")
        void platformCategoryIsItsOwnScope() {
            when(rateRepository.findCandidates(null, "TIRE_SERVICE", NOW))
                    .thenReturn(List.of(rate(null, ServiceOperationCategory.TIRE_SERVICE, "95.00")));

            LaborRateQuoteResponse response =
                    service.resolve(new LaborRateQuoteRequest(null, "TIRE_SERVICE", List.of(), NOW));

            assertThat(response.scope()).isEqualTo(Scope.PLATFORM_CATEGORY);
        }
    }

    @Nested
    @DisplayName("the request record's own conveniences")
    class RequestHelpers {

        @Test
        @DisplayName("the two-argument factory prices now with no matrix, which is the common case")
        void factoryFillsTheCommonCase() {
            LaborRateQuoteRequest request = LaborRateQuoteRequest.of(SHOP_A, "TIRE_SERVICE");

            assertThat(request.locationId()).isEqualTo(SHOP_A);
            assertThat(request.operationCategory()).isEqualTo("TIRE_SERVICE");
            assertThat(request.adjustmentCodes()).isEmpty();
            assertThat(request.at()).isNull();
        }

        @Test
        @DisplayName("an omitted code list reads as empty, so no caller re-handles the null")
        void omittedCodesReadAsEmpty() {
            assertThat(new LaborRateQuoteRequest(SHOP_A, null, null, null).adjustmentCodesOrEmpty())
                    .isEmpty();
        }

        @Test
        @DisplayName("a supplied code list is handed back as it was given")
        void suppliedCodesArePassedThrough() {
            assertThat(new LaborRateQuoteRequest(SHOP_A, null, List.of("CORROSION"), null).adjustmentCodesOrEmpty())
                    .containsExactly("CORROSION");
        }
    }

    @Nested
    @DisplayName("the grant-surface pass-through (ADR-0026 D4)")
    class GrantSurface {

        @Test
        @DisplayName("the granted interface answers with exactly what resolution decided")
        void shopLaborRateServiceDelegates() {
            when(rateRepository.findCandidates(SHOP_A, "TIRE_SERVICE", NOW))
                    .thenReturn(List.of(rate(SHOP_A, ServiceOperationCategory.TIRE_SERVICE, "105.00")));
            ShopLaborRateServiceImpl grantSurface = new ShopLaborRateServiceImpl(service);

            LaborRateQuoteResponse response =
                    grantSurface.resolveLaborRate(new LaborRateQuoteRequest(SHOP_A, "TIRE_SERVICE", List.of(), NOW));

            assertThat(response.status()).isEqualTo(Status.RESOLVED);
            assertThat(response.scope()).isEqualTo(Scope.LOCATION_CATEGORY);
            assertThat(response.hourlyRate()).isEqualByComparingTo("105.0000");
        }

        @Test
        @DisplayName("a miss travels through the grant surface as a status, never as an exception")
        void grantSurfacePassesTheMissThrough() {
            ShopLaborRateServiceImpl grantSurface = new ShopLaborRateServiceImpl(service);

            LaborRateQuoteResponse response =
                    grantSurface.resolveLaborRate(LaborRateQuoteRequest.of(SHOP_A, "TIRE_SERVICE"));

            assertThat(response.status()).isEqualTo(Status.NO_RATE_AVAILABLE);
            assertThat(response.hourlyRate()).isNull();
        }
    }
}
