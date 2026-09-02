package com.positivity.supplier.internal.stockinquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierStockInquiry;
import com.positivity.supplier.internal.domain.model.SupplierStockInquiryResult;
import com.positivity.supplier.internal.entity.ExtProductCodeReplica;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.exception.SupplierConflictException;
import com.positivity.supplier.internal.exception.SupplierNotFoundException;
import com.positivity.supplier.internal.exception.SupplierValidationException;
import com.positivity.supplier.internal.repository.ExtProductCodeReplicaRepository;
import com.positivity.supplier.internal.repository.SupplierEndpointBindingRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import com.positivity.supplier.internal.stockinquiry.service.model.StockAvailabilityView;
import com.positivity.supplier.service.model.StockInquiryResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
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
 * The product-keyed availability fan-out (#1637 decisions 1-3): identity resolution from the local
 * replica, concurrent fan-out under a deadline, honest per-vendor {@code fetchedAt}/{@code asOf},
 * and staleness judged from {@code asOf} against the echoed threshold.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Product-keyed availability fan-out (#1637 decision 1)")
class SupplierStockAvailabilityServiceImplTest {

    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final UUID LOCATION = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01");
    private static final UUID VENDOR_A = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4aa1");
    private static final UUID VENDOR_B = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4aa2");
    private static final UUID VENDOR_C = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4aa3");
    private static final String EAN = "3528709999083";
    private static final String SKU = "MICH-PS5-22545R17";
    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration DEADLINE = Duration.ofSeconds(5);
    private static final Duration STALENESS = Duration.ofMinutes(15);

    @Mock
    private ExtProductCodeReplicaRepository replicaRepository;

    @Mock
    private SupplierEndpointBindingRepository bindingRepository;

    @Mock
    private SupplierProfileRepository profileRepository;

    @Mock
    private StockInquiryRunner runner;

    private StockInquiryCache cache;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        cache = new StockInquiryCache(true, Duration.ofSeconds(60));
        executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    private SupplierStockAvailabilityServiceImpl service(Duration deadline) {
        return new SupplierStockAvailabilityServiceImpl(
                replicaRepository,
                bindingRepository,
                profileRepository,
                runner,
                cache,
                CLOCK,
                executor,
                deadline,
                STALENESS);
    }

    private void givenProductWithCode(String codeType, String code) {
        when(replicaRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(ExtProductCodeReplica.builder()
                        .productId(PRODUCT_ID)
                        .codeType(codeType)
                        .code(code)
                        .sku(SKU)
                        .aggregateVersion(1L)
                        .build()));
    }

    private static SupplierProfileEntity profile(UUID id, String ref, String displayName, boolean enabled) {
        SupplierProfileEntity profile = new SupplierProfileEntity();
        profile.setVendorProfileId(id);
        profile.setSupplierRef(ref);
        profile.setDisplayName(displayName);
        profile.setEnabled(enabled);
        return profile;
    }

    private static SupplierEndpointBindingEntity binding(UUID vendorProfileId) {
        return SupplierEndpointBindingEntity.builder()
                .vendorProfileId(vendorProfileId)
                .capability(SupplierCapability.STOCK_INQUIRY)
                .enabled(true)
                .build();
    }

    private void givenVendors(SupplierProfileEntity... profiles) {
        List<SupplierEndpointBindingEntity> bindings = List.of(profiles).stream()
                .map(p -> binding(p.getVendorProfileId()))
                .toList();
        when(bindingRepository.findByCapabilityAndEnabledTrue(SupplierCapability.STOCK_INQUIRY))
                .thenReturn(bindings);
        when(profileRepository.findAllById(any())).thenReturn(List.of(profiles));
    }

    private static SupplierStockInquiryResult available(int quantity, Instant asOf) {
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
                asOf);
    }

    private void answerFor(String ref, SupplierStockInquiryResult result) {
        when(runner.inquireAvailability(eq(new SupplierRef(ref)), eq(LOCATION), any()))
                .thenReturn(result);
    }

    @Nested
    @DisplayName("Identity resolution (replica-only, ADR-0044 R1/R3)")
    class IdentityResolution {

        @Test
        void resolvesAProductIdToItsEanAndAsksTheVendorAboutIt() {
            givenProductWithCode("EAN", EAN);
            givenVendors(profile(VENDOR_A, "michelin-eu", "Michelin Europe", true));
            answerFor("michelin-eu", available(8, null));

            StockAvailabilityView view = service(DEADLINE).checkAvailability(PRODUCT_ID, null, LOCATION, 4);

            assertThat(view.productId()).isEqualTo(PRODUCT_ID);
            ArgumentCaptor<SupplierStockInquiry> inquiry = ArgumentCaptor.forClass(SupplierStockInquiry.class);
            verify(runner).inquireAvailability(any(), eq(LOCATION), inquiry.capture());
            assertThat(inquiry.getValue().lines().getFirst().articleEan()).isEqualTo(EAN);
            assertThat(inquiry.getValue().lines().getFirst().requestedQuantity())
                    .isEqualTo(4);
        }

        @Test
        void aUpcCodeIsJustAsQueryable() {
            // The replica holds one typed code per product; UPC is the GTIN-family fallback when a
            // product carries no EAN, and it travels in the same article-number slot.
            givenProductWithCode("UPC", "036000291452");
            givenVendors(profile(VENDOR_A, "michelin-eu", "Michelin Europe", true));
            answerFor("michelin-eu", available(2, null));

            service(DEADLINE).checkAvailability(PRODUCT_ID, null, LOCATION, 1);

            ArgumentCaptor<SupplierStockInquiry> inquiry = ArgumentCaptor.forClass(SupplierStockInquiry.class);
            verify(runner).inquireAvailability(any(), eq(LOCATION), inquiry.capture());
            assertThat(inquiry.getValue().lines().getFirst().articleEan()).isEqualTo("036000291452");
        }

        @Test
        void resolvesASkuThroughTheReplicaWithoutAskingTheCatalog() {
            when(replicaRepository.findBySku(SKU))
                    .thenReturn(List.of(ExtProductCodeReplica.builder()
                            .productId(PRODUCT_ID)
                            .codeType("EAN")
                            .code(EAN)
                            .sku(SKU)
                            .aggregateVersion(1L)
                            .build()));
            givenVendors(profile(VENDOR_A, "michelin-eu", "Michelin Europe", true));
            answerFor("michelin-eu", available(8, null));

            StockAvailabilityView view = service(DEADLINE).checkAvailability(null, SKU, LOCATION, 1);

            // The resolved product identity is echoed even though the caller named only the SKU.
            assertThat(view.productId()).isEqualTo(PRODUCT_ID);
        }

        @Test
        void reportsAnUnknownProductAsProductCodesNotFound() {
            when(replicaRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service(DEADLINE).checkAvailability(PRODUCT_ID, null, LOCATION, 1))
                    .isInstanceOf(SupplierNotFoundException.class)
                    .extracting(e -> ((SupplierNotFoundException) e).getCode())
                    .isEqualTo(SupplierNotFoundException.PRODUCT_CODES_NOT_FOUND);
            verifyNoInteractions(runner);
        }

        @Test
        void reportsAProductWithoutAnyCodeAsProductCodesNotFound() {
            givenProductWithCode(null, null);

            assertThatThrownBy(() -> service(DEADLINE).checkAvailability(PRODUCT_ID, null, LOCATION, 1))
                    .isInstanceOf(SupplierNotFoundException.class)
                    .extracting(e -> ((SupplierNotFoundException) e).getCode())
                    .isEqualTo(SupplierNotFoundException.PRODUCT_CODES_NOT_FOUND);
        }

        @Test
        void reportsAnUnknownSkuAsProductCodesNotFound() {
            when(replicaRepository.findBySku(SKU)).thenReturn(List.of());

            assertThatThrownBy(() -> service(DEADLINE).checkAvailability(null, SKU, LOCATION, 1))
                    .isInstanceOf(SupplierNotFoundException.class)
                    .extracting(e -> ((SupplierNotFoundException) e).getCode())
                    .isEqualTo(SupplierNotFoundException.PRODUCT_CODES_NOT_FOUND);
        }

        @Test
        void refusesAnAmbiguousSkuRatherThanGuessing() {
            ExtProductCodeReplica row = ExtProductCodeReplica.builder()
                    .productId(PRODUCT_ID)
                    .codeType("EAN")
                    .code(EAN)
                    .sku(SKU)
                    .aggregateVersion(1L)
                    .build();
            when(replicaRepository.findBySku(SKU)).thenReturn(List.of(row, row));

            assertThatThrownBy(() -> service(DEADLINE).checkAvailability(null, SKU, LOCATION, 1))
                    .isInstanceOf(SupplierConflictException.class);
            verifyNoInteractions(runner);
        }

        @Test
        void rejectsARequestNamingBothIdentities() {
            assertThatThrownBy(() -> service(DEADLINE).checkAvailability(PRODUCT_ID, SKU, LOCATION, 1))
                    .isInstanceOf(SupplierValidationException.class)
                    .satisfies(e -> {
                        SupplierValidationException ex = (SupplierValidationException) e;
                        assertThat(ex.getCode()).isEqualTo(SupplierValidationException.AVAILABILITY_IDENTITY_INVALID);
                        // Both parameters are named so a form can attach the message to each input.
                        assertThat(ex.getFieldErrors()).hasSize(2);
                    });
        }

        @Test
        void rejectsARequestNamingNeitherIdentity() {
            assertThatThrownBy(() -> service(DEADLINE).checkAvailability(null, null, LOCATION, 1))
                    .isInstanceOf(SupplierValidationException.class);
            assertThatThrownBy(() -> service(DEADLINE).checkAvailability(null, "  ", LOCATION, 1))
                    .isInstanceOf(SupplierValidationException.class);
        }
    }

    @Nested
    @DisplayName("Fan-out assembly")
    class FanOutAssembly {

        @Test
        void assemblesMixedVendorOutcomesIntoOnePartialAnswer() {
            givenProductWithCode("EAN", EAN);
            givenVendors(
                    profile(VENDOR_A, "michelin-eu", "A Michelin", true),
                    profile(VENDOR_B, "conti-dach", "B Conti", true),
                    profile(VENDOR_C, "pirelli-it", "C Pirelli", true));
            answerFor("michelin-eu", available(8, null));
            answerFor(
                    "conti-dach",
                    new SupplierStockInquiryResult(
                            SupplierStockInquiryResult.Status.SUPPLIER_UNAVAILABLE, List.of(), null));
            answerFor(
                    "pirelli-it",
                    new SupplierStockInquiryResult(SupplierStockInquiryResult.Status.NOT_LISTED, List.of(), null));

            StockAvailabilityView view = service(DEADLINE).checkAvailability(PRODUCT_ID, null, LOCATION, 4);

            assertThat(view.vendors()).hasSize(3);
            assertThat(view.stalenessThreshold()).isEqualTo("PT15M");

            StockAvailabilityView.VendorAvailability michelin = view.vendors().getFirst();
            assertThat(michelin.vendorDisplayName()).isEqualTo("A Michelin");
            assertThat(michelin.status()).isEqualTo(StockInquiryResponse.Status.OK);
            assertThat(michelin.fetchedAt()).isEqualTo(NOW);
            assertThat(michelin.asOf()).isEqualTo(NOW);
            assertThat(michelin.stale()).isFalse();
            assertThat(michelin.lines().getFirst().availableQuantity()).isEqualTo(8);

            StockAvailabilityView.VendorAvailability conti = view.vendors().get(1);
            assertThat(conti.status()).isEqualTo(StockInquiryResponse.Status.SUPPLIER_UNAVAILABLE);
            assertThat(conti.fetchedAt()).isNull();
            assertThat(conti.asOf()).isNull();
            assertThat(conti.stale()).isNull();
            assertThat(conti.lines()).isEmpty();

            assertThat(view.vendors().get(2).status()).isEqualTo(StockInquiryResponse.Status.NOT_LISTED);
        }

        @Test
        void noConfiguredVendorIsAValidAnswerNotAnError() {
            givenProductWithCode("EAN", EAN);
            when(bindingRepository.findByCapabilityAndEnabledTrue(SupplierCapability.STOCK_INQUIRY))
                    .thenReturn(List.of());

            StockAvailabilityView view = service(DEADLINE).checkAvailability(PRODUCT_ID, null, LOCATION, 1);

            assertThat(view.vendors()).isEmpty();
            verifyNoInteractions(runner);
        }

        @Test
        void neverAsksAVendorWhoseProfileIsDisabledEvenIfItsBindingIsNot() {
            givenProductWithCode("EAN", EAN);
            givenVendors(
                    profile(VENDOR_A, "michelin-eu", "Michelin", true),
                    profile(VENDOR_B, "conti-dach", "Conti", false));
            answerFor("michelin-eu", available(8, null));

            StockAvailabilityView view = service(DEADLINE).checkAvailability(PRODUCT_ID, null, LOCATION, 1);

            assertThat(view.vendors()).hasSize(1);
            assertThat(view.vendors().getFirst().vendorProfileId()).isEqualTo(VENDOR_A);
            verify(runner, never()).inquireAvailability(eq(new SupplierRef("conti-dach")), any(), any());
        }

        @Test
        void reportsAVendorStillSilentAtTheDeadlineAsUnavailableAlongsideTheOnesThatAnswered() {
            givenProductWithCode("EAN", EAN);
            givenVendors(
                    profile(VENDOR_A, "michelin-eu", "A Michelin", true),
                    profile(VENDOR_B, "conti-dach", "B Conti", true));
            answerFor("michelin-eu", available(8, null));
            when(runner.inquireAvailability(eq(new SupplierRef("conti-dach")), eq(LOCATION), any()))
                    .thenAnswer(invocation -> {
                        Thread.sleep(5_000);
                        return available(1, null);
                    });

            StockAvailabilityView view =
                    service(Duration.ofMillis(300)).checkAvailability(PRODUCT_ID, null, LOCATION, 1);

            assertThat(view.vendors().getFirst().status()).isEqualTo(StockInquiryResponse.Status.OK);
            StockAvailabilityView.VendorAvailability slow = view.vendors().get(1);
            assertThat(slow.status()).isEqualTo(StockInquiryResponse.Status.SUPPLIER_UNAVAILABLE);
            assertThat(slow.fetchedAt()).isNull();
            assertThat(slow.lines()).isEmpty();
        }
    }

    @Nested
    @DisplayName("fetchedAt / asOf / staleness (#1637 decisions 1 and 3)")
    class Freshness {

        @Test
        void aCachedAnswerKeepsItsOriginalFetchInstantNotTheCacheHitsInstant() {
            givenProductWithCode("EAN", EAN);
            givenVendors(profile(VENDOR_A, "michelin-eu", "Michelin", true));
            Instant originalFetch = NOW.minusSeconds(30);
            cache.put(
                    new StockInquiryCache.Key(VENDOR_A, LOCATION, ArticleKeys.of(EAN, null)),
                    new SupplierStockInquiryResult.Line(
                            EAN, null, SupplierStockInquiryResult.LineStatus.AVAILABLE, 8, null, null, null),
                    originalFetch,
                    originalFetch);

            StockAvailabilityView view = service(DEADLINE).checkAvailability(PRODUCT_ID, null, LOCATION, 1);

            StockAvailabilityView.VendorAvailability vendor = view.vendors().getFirst();
            assertThat(vendor.fetchedAt()).isEqualTo(originalFetch);
            assertThat(vendor.asOf()).isEqualTo(originalFetch);
            verifyNoInteractions(runner);
        }

        @Test
        void aFreshAnswerIsCachedWithItsFetchInstantForTheNextPage() {
            givenProductWithCode("EAN", EAN);
            givenVendors(profile(VENDOR_A, "michelin-eu", "Michelin", true));
            answerFor("michelin-eu", available(8, null));

            service(DEADLINE).checkAvailability(PRODUCT_ID, null, LOCATION, 1);

            StockInquiryCache.Answer cached =
                    cache.get(new StockInquiryCache.Key(VENDOR_A, LOCATION, ArticleKeys.of(EAN, null)));
            assertThat(cached).isNotNull();
            assertThat(cached.fetchedAt()).isEqualTo(NOW);
        }

        @Test
        void aVendorStatedObservationInstantIsKeptAsAsOfNotOverwrittenByOurClock() {
            givenProductWithCode("EAN", EAN);
            givenVendors(profile(VENDOR_A, "michelin-eu", "Michelin", true));
            Instant vendorStated = NOW.minus(Duration.ofMinutes(5));
            answerFor("michelin-eu", available(8, vendorStated));

            StockAvailabilityView view = service(DEADLINE).checkAvailability(PRODUCT_ID, null, LOCATION, 1);

            StockAvailabilityView.VendorAvailability vendor = view.vendors().getFirst();
            // Two facts, separately: when we fetched, and what the vendor said it observed.
            assertThat(vendor.fetchedAt()).isEqualTo(NOW);
            assertThat(vendor.asOf()).isEqualTo(vendorStated);
            assertThat(vendor.stale()).isFalse();
        }

        @Test
        void flagsAnAnswerWhoseAsOfIsOlderThanTheThresholdAsStale() {
            givenProductWithCode("EAN", EAN);
            givenVendors(profile(VENDOR_A, "michelin-eu", "Michelin", true));
            answerFor("michelin-eu", available(8, NOW.minus(STALENESS).minusSeconds(1)));

            StockAvailabilityView view = service(DEADLINE).checkAvailability(PRODUCT_ID, null, LOCATION, 1);

            assertThat(view.vendors().getFirst().stale()).isTrue();
        }

        @Test
        void anAsOfExactlyAtTheThresholdIsNotYetStale() {
            givenProductWithCode("EAN", EAN);
            givenVendors(profile(VENDOR_A, "michelin-eu", "Michelin", true));
            answerFor("michelin-eu", available(8, NOW.minus(STALENESS)));

            StockAvailabilityView view = service(DEADLINE).checkAvailability(PRODUCT_ID, null, LOCATION, 1);

            assertThat(view.vendors().getFirst().stale()).isFalse();
        }
    }
}
