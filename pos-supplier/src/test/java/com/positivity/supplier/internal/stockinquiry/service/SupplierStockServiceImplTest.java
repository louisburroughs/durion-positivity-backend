package com.positivity.supplier.internal.stockinquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierStockInquiry;
import com.positivity.supplier.internal.domain.model.SupplierStockInquiryResult;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import com.positivity.supplier.service.model.StockInquiryRequest;
import com.positivity.supplier.service.model.StockInquiryResponse;
import java.time.Clock;
import java.time.Duration;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Approved synchronous stock read (#1225, ADR-0044 amendment)")
class SupplierStockServiceImplTest {

    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final UUID LOCATION_A = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01");
    private static final UUID LOCATION_B = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02");
    private static final String EAN = "4019238001234";
    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private SupplierProfileRepository profileRepository;

    @Mock
    private StockInquiryRunner runner;

    private StockInquiryCache cache;
    private SupplierStockServiceImpl service;

    @BeforeEach
    void setUp() {
        cache = new StockInquiryCache(true, Duration.ofSeconds(60));
        service = new SupplierStockServiceImpl(profileRepository, runner, cache, CLOCK);

        SupplierProfileEntity profile = new SupplierProfileEntity();
        profile.setVendorProfileId(PROFILE_ID);
        profile.setSupplierRef("michelin-eu");
        profile.setEnabled(true);
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));
    }

    private static StockInquiryRequest request(UUID locationId) {
        return new StockInquiryRequest(
                UUID.randomUUID(), PROFILE_ID, locationId, List.of(new StockInquiryRequest.Line(EAN, null, 4)));
    }

    private static SupplierStockInquiryResult answered(int quantity) {
        return new SupplierStockInquiryResult(
                SupplierStockInquiryResult.Status.OK,
                List.of(new SupplierStockInquiryResult.Line(
                        EAN,
                        null,
                        SupplierStockInquiryResult.LineStatus.AVAILABLE,
                        quantity,
                        LocalDate.of(2026, 8, 20),
                        null,
                        null)),
                null);
    }

    private static SupplierStockInquiryResult failed(SupplierStockInquiryResult.Status status) {
        return new SupplierStockInquiryResult(status, List.of(), null);
    }

    @Test
    void returnsTheVendorsAnswerWithTheInquiryIdEchoedBack() {
        when(runner.inquireAvailability(any(), any(), any())).thenReturn(answered(8));
        StockInquiryRequest request = request(LOCATION_A);

        StockInquiryResponse response = service.inquireLiveStock(request);

        assertThat(response.inquiryId()).isEqualTo(request.inquiryId());
        assertThat(response.status()).isEqualTo(StockInquiryResponse.Status.OK);
        assertThat(response.lines()).hasSize(1);
        assertThat(response.lines().getFirst().availableQuantity()).isEqualTo(8);
        assertThat(response.lines().getFirst().earliestDeliveryDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(response.asOf()).isEqualTo(NOW);
    }

    @Test
    void asksTheVendorOnlyOnceForRepeatedViewsOfTheSameArticle() {
        when(runner.inquireAvailability(any(), any(), any())).thenReturn(answered(8));

        service.inquireLiveStock(request(LOCATION_A));
        StockInquiryResponse second = service.inquireLiveStock(request(LOCATION_A));

        verify(runner, times(1)).inquireAvailability(any(), any(), any());
        assertThat(second.lines().getFirst().availableQuantity()).isEqualTo(8);
    }

    @Test
    void neverServesOneLocationsAnswerToAnother() {
        when(runner.inquireAvailability(any(), any(), any())).thenReturn(answered(8));

        service.inquireLiveStock(request(LOCATION_A));
        service.inquireLiveStock(request(LOCATION_B));

        // Availability is consignee-specific. A shared entry would tell a customer that stock four
        // hundred kilometres away is available at the branch fitting the tyre.
        verify(runner).inquireAvailability(any(SupplierRef.class), eq(LOCATION_A), any(SupplierStockInquiry.class));
        verify(runner).inquireAvailability(any(SupplierRef.class), eq(LOCATION_B), any(SupplierStockInquiry.class));
    }

    @Test
    void asksTheVendorOnlyAboutArticlesItHasNoAnswerFor() {
        when(runner.inquireAvailability(any(), any(), any())).thenReturn(answered(8));
        service.inquireLiveStock(request(LOCATION_A));

        StockInquiryRequest twoArticles = new StockInquiryRequest(
                UUID.randomUUID(),
                PROFILE_ID,
                LOCATION_A,
                List.of(
                        new StockInquiryRequest.Line(EAN, null, 4),
                        new StockInquiryRequest.Line("9999999999999", null, 2)));
        when(runner.inquireAvailability(any(), any(), any()))
                .thenReturn(new SupplierStockInquiryResult(
                        SupplierStockInquiryResult.Status.OK,
                        List.of(new SupplierStockInquiryResult.Line(
                                "9999999999999",
                                null,
                                SupplierStockInquiryResult.LineStatus.UNAVAILABLE,
                                0,
                                null,
                                null,
                                null)),
                        null));

        StockInquiryResponse response = service.inquireLiveStock(twoArticles);

        ArgumentCaptor<SupplierStockInquiry> captor = ArgumentCaptor.forClass(SupplierStockInquiry.class);
        verify(runner, times(2)).inquireAvailability(any(), any(), captor.capture());
        assertThat(captor.getValue().lines()).hasSize(1);
        assertThat(captor.getValue().lines().getFirst().articleEan()).isEqualTo("9999999999999");
        // Both articles are answered: one from the cache, one from the vendor.
        assertThat(response.lines()).hasSize(2);
    }

    @Test
    void datesTheAnswerByTheOldestFactInIt() {
        when(runner.inquireAvailability(any(), any(), any())).thenReturn(answered(8));
        service.inquireLiveStock(request(LOCATION_A));

        // A later call served from that cached answer must not claim to be current: a caller asking
        // how fresh this is wants the worst case, not the newest line in the set.
        SupplierStockServiceImpl later = new SupplierStockServiceImpl(
                profileRepository, runner, cache, Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC));

        StockInquiryResponse response = later.inquireLiveStock(request(LOCATION_A));

        assertThat(response.asOf()).isEqualTo(NOW);
    }

    @Test
    void doesNotRememberAVendorThatCouldNotAnswer() {
        when(runner.inquireAvailability(any(), any(), any()))
                .thenReturn(failed(SupplierStockInquiryResult.Status.SUPPLIER_UNAVAILABLE));

        service.inquireLiveStock(request(LOCATION_A));
        service.inquireLiveStock(request(LOCATION_A));

        // Caching a failure would turn one bad moment into a minute of identical failures for every
        // customer viewing the page.
        verify(runner, times(2)).inquireAvailability(any(), any(), any());
    }

    @Test
    void doesNotRememberAnArticleTheVendorCouldNotDetermine() {
        when(runner.inquireAvailability(any(), any(), any()))
                .thenReturn(new SupplierStockInquiryResult(
                        SupplierStockInquiryResult.Status.OK,
                        List.of(new SupplierStockInquiryResult.Line(
                                EAN, null, SupplierStockInquiryResult.LineStatus.NOT_ANSWERED, null, null, null, null)),
                        null));

        service.inquireLiveStock(request(LOCATION_A));
        service.inquireLiveStock(request(LOCATION_A));

        verify(runner, times(2)).inquireAvailability(any(), any(), any());
    }

    @Test
    void doesNotRememberAnUnlistedArticleSoACatalogueFixShowsImmediately() {
        when(runner.inquireAvailability(any(), any(), any()))
                .thenReturn(new SupplierStockInquiryResult(
                        SupplierStockInquiryResult.Status.NOT_LISTED,
                        List.of(new SupplierStockInquiryResult.Line(
                                EAN, null, SupplierStockInquiryResult.LineStatus.NOT_LISTED, null, null, null, null)),
                        null));

        StockInquiryResponse first = service.inquireLiveStock(request(LOCATION_A));
        service.inquireLiveStock(request(LOCATION_A));

        assertThat(first.status()).isEqualTo(StockInquiryResponse.Status.NOT_LISTED);
        // NOT_LISTED usually means a mapping is wrong. An operator who fixes it should see the fix
        // on the next page load, not a minute later wondering whether it worked.
        verify(runner, times(2)).inquireAvailability(any(), any(), any());
    }

    @Test
    void passesEveryDegradedOutcomeThroughUntouched() {
        for (SupplierStockInquiryResult.Status status : List.of(
                SupplierStockInquiryResult.Status.SUPPLIER_UNAVAILABLE,
                SupplierStockInquiryResult.Status.CAPABILITY_NOT_CONFIGURED,
                SupplierStockInquiryResult.Status.CONFIGURATION_ERROR)) {
            when(runner.inquireAvailability(any(), any(), any())).thenReturn(failed(status));

            StockInquiryResponse response = service.inquireLiveStock(request(LOCATION_A));

            assertThat(response.status().name()).isEqualTo(status.name());
            assertThat(response.lines()).isEmpty();
            // No fact, so no date to put on one.
            assertThat(response.asOf()).isNull();
        }
    }

    @Test
    void reportsAnUnknownVendorProfileAsConfigurationErrorWithoutAsking() {
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.empty());

        StockInquiryResponse response = service.inquireLiveStock(request(LOCATION_A));

        assertThat(response.status()).isEqualTo(StockInquiryResponse.Status.CONFIGURATION_ERROR);
        verifyNoInteractions(runner);
    }

    @Test
    void makesNoCallAtAllWhenEveryArticleIsAlreadyAnswered() {
        when(runner.inquireAvailability(any(), any(), any())).thenReturn(answered(8));
        service.inquireLiveStock(request(LOCATION_A));

        service.inquireLiveStock(request(LOCATION_A));

        verify(runner, times(1)).inquireAvailability(any(), any(), any());
    }

    @Test
    void asksEveryTimeWhenCachingIsSwitchedOff() {
        StockInquiryCache disabled = new StockInquiryCache(false, Duration.ofSeconds(60));
        SupplierStockServiceImpl uncached = new SupplierStockServiceImpl(profileRepository, runner, disabled, CLOCK);
        when(runner.inquireAvailability(any(), any(), any())).thenReturn(answered(8));

        uncached.inquireLiveStock(request(LOCATION_A));
        uncached.inquireLiveStock(request(LOCATION_A));

        verify(runner, times(2)).inquireAvailability(any(), any(), any());
    }

    @Test
    void neverThrowsForAVendorSideFailure() {
        when(runner.inquireAvailability(any(), any(), any()))
                .thenReturn(failed(SupplierStockInquiryResult.Status.SUPPLIER_UNAVAILABLE));

        // The whole point of the degradation contract: a page renders without live stock rather than
        // failing because a supplier had a bad afternoon.
        assertThat(service.inquireLiveStock(request(LOCATION_A))).isNotNull();
        verify(runner, never()).inquireAvailability(eq(null), any(), any());
    }
}
