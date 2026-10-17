package com.positivity.inventory.internal.replenishment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.order.PurchaseOrderRequestedV1;
import com.positivity.inventory.internal.dto.purchasesuggestion.ConvertPurchaseSuggestionsRequest;
import com.positivity.inventory.internal.dto.purchasesuggestion.ConvertPurchaseSuggestionsResponse;
import com.positivity.inventory.internal.entity.PurchaseSuggestion;
import com.positivity.inventory.internal.enums.FeedVendorType;
import com.positivity.inventory.internal.enums.PurchaseSuggestionStatus;
import com.positivity.inventory.internal.enums.SuggestedVendorType;
import com.positivity.inventory.internal.enums.VendorStatus;
import com.positivity.inventory.internal.exception.PurchaseSuggestionConversionException;
import com.positivity.inventory.internal.repository.ExtProductUomReplicaRepository;
import com.positivity.inventory.internal.repository.PurchaseSuggestionRepository;
import com.positivity.inventory.internal.service.ForecastSiteResolver;
import com.positivity.inventory.internal.service.LocationScopeService;
import com.positivity.inventory.internal.service.PurchaseOrderCommandPublisher;
import com.positivity.inventory.internal.service.VendorCopyReader;
import com.positivity.inventory.internal.service.VendorFeedMappingService;
import com.positivity.inventory.internal.service.VendorFeedMappingService.ResolvedFeedVendor;
import java.time.Clock;
import java.time.Instant;
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

/** CAP:550 S36 (#2525, PROPOSED 7): conversion resolves the vendor again through the mapping and the copy. */
@ExtendWith(MockitoExtension.class)
class PurchaseSuggestionConversionResolutionTest {

    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    private static final UUID SITE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID VENDOR = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID OTHER_VENDOR = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

    @Mock
    private PurchaseSuggestionRepository purchaseSuggestionRepository;

    @Mock
    private ExtProductUomReplicaRepository extProductUomReplicaRepository;

    @Mock
    private PurchaseOrderCommandPublisher purchaseOrderCommandPublisher;

    @Mock
    private ForecastSiteResolver forecastSiteResolver;

    @Mock
    private LocationScopeService locationScopeService;

    @Mock
    private VendorCopyReader vendorCopyReader;

    @Mock
    private VendorFeedMappingService vendorFeedMappingService;

    @Mock
    private PurchaseSuggestionOrderAttentionService orderAttentionService;

    private PurchaseSuggestionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PurchaseSuggestionServiceImpl(
                purchaseSuggestionRepository,
                extProductUomReplicaRepository,
                purchaseOrderCommandPublisher,
                forecastSiteResolver,
                Clock.fixed(NOW, ZoneOffset.UTC),
                locationScopeService,
                vendorCopyReader,
                vendorFeedMappingService,
                orderAttentionService);
        lenient().when(forecastSiteResolver.resolveForecastSite(any())).thenReturn(SITE);
        lenient().when(purchaseSuggestionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private PurchaseSuggestion accepted(SuggestedVendorType type, String ref) {
        PurchaseSuggestion s = PurchaseSuggestion.builder()
                .suggestionId(UUID.randomUUID())
                .policyId(UUID.randomUUID())
                .itemSKU("SKU-1")
                .locationId(SITE)
                .suggestedQuantity(5)
                .suggestedVendorType(type)
                .feedVendorRef(ref)
                .unitCostMinor(1250L)
                .unitCostCurrency("USD")
                .status(PurchaseSuggestionStatus.ACCEPTED)
                .build();
        when(purchaseSuggestionRepository.findById(s.getSuggestionId())).thenReturn(Optional.of(s));
        return s;
    }

    private static ConvertPurchaseSuggestionsRequest request(PurchaseSuggestion... suggestions) {
        ConvertPurchaseSuggestionsRequest request = new ConvertPurchaseSuggestionsRequest();
        request.setSuggestionIds(
                java.util.Arrays.stream(suggestions).map(PurchaseSuggestion::getSuggestionId).toList());
        return request;
    }

    private static ResolvedFeedVendor vendor(UUID id, VendorStatus status) {
        return new ResolvedFeedVendor(id, "V-1", "Vendor One", status);
    }

    @Test
    @DisplayName("AC 7: an unmapped feed vendor is 422 PURCHASE_SUGGESTION_VENDOR_UNMAPPED; nothing written")
    void unmapped() {
        PurchaseSuggestion s = accepted(SuggestedVendorType.DISTRIBUTOR, "DIST-001");
        when(vendorFeedMappingService.resolve(FeedVendorType.DISTRIBUTOR, "DIST-001")).thenReturn(Optional.empty());
        when(vendorFeedMappingService.isMapped(FeedVendorType.DISTRIBUTOR, "DIST-001")).thenReturn(false);

        assertThatThrownBy(() -> service.convertPurchaseSuggestions(request(s), "buyer"))
                .isInstanceOf(PurchaseSuggestionConversionException.class)
                .extracting(e -> ((PurchaseSuggestionConversionException) e).getErrorCode())
                .isEqualTo("PURCHASE_SUGGESTION_VENDOR_UNMAPPED");
        verify(purchaseOrderCommandPublisher, never()).request(any());
        verify(purchaseSuggestionRepository, never()).saveAll(any());
        assertThat(s.getStatus()).isEqualTo(PurchaseSuggestionStatus.ACCEPTED);
    }

    @Test
    @DisplayName("AC 7: a mapped vendor missing from the copy is 422 VENDOR_NOT_FOUND")
    void mappedButNotInCopy() {
        PurchaseSuggestion s = accepted(SuggestedVendorType.DISTRIBUTOR, "DIST-001");
        when(vendorFeedMappingService.resolve(FeedVendorType.DISTRIBUTOR, "DIST-001")).thenReturn(Optional.empty());
        when(vendorFeedMappingService.isMapped(FeedVendorType.DISTRIBUTOR, "DIST-001")).thenReturn(true);

        assertThatThrownBy(() -> service.convertPurchaseSuggestions(request(s), "buyer"))
                .extracting(e -> ((PurchaseSuggestionConversionException) e).getErrorCode())
                .isEqualTo("VENDOR_NOT_FOUND");
    }

    @Test
    @DisplayName("AC 7: an inactive vendor is 422 VENDOR_INACTIVE")
    void inactive() {
        PurchaseSuggestion s = accepted(SuggestedVendorType.DISTRIBUTOR, "DIST-001");
        when(vendorFeedMappingService.resolve(FeedVendorType.DISTRIBUTOR, "DIST-001"))
                .thenReturn(Optional.of(vendor(VENDOR, VendorStatus.INACTIVE)));

        assertThatThrownBy(() -> service.convertPurchaseSuggestions(request(s), "buyer"))
                .extracting(e -> ((PurchaseSuggestionConversionException) e).getErrorCode())
                .isEqualTo("VENDOR_INACTIVE");
    }

    @Test
    @DisplayName("AC 7: two feeds of one vendor make one order; two vendors are a mismatch")
    void mismatchComparesVendorIds() {
        PurchaseSuggestion m = accepted(SuggestedVendorType.MANUFACTURER, "00000000-0000-0000-0000-0000000000f1");
        PurchaseSuggestion d = accepted(SuggestedVendorType.DISTRIBUTOR, "DIST-001");
        when(vendorFeedMappingService.resolve(eq(FeedVendorType.MANUFACTURER), anyString()))
                .thenReturn(Optional.of(vendor(VENDOR, VendorStatus.ACTIVE)));
        when(vendorFeedMappingService.resolve(FeedVendorType.DISTRIBUTOR, "DIST-001"))
                .thenReturn(Optional.of(vendor(OTHER_VENDOR, VendorStatus.ACTIVE)));

        assertThatThrownBy(() -> service.convertPurchaseSuggestions(request(m, d), "buyer"))
                .extracting(e -> ((PurchaseSuggestionConversionException) e).getErrorCode())
                .isEqualTo("PURCHASE_SUGGESTION_VENDOR_MISMATCH");

        when(vendorFeedMappingService.resolve(FeedVendorType.DISTRIBUTOR, "DIST-001"))
                .thenReturn(Optional.of(vendor(VENDOR, VendorStatus.ACTIVE)));
        ConvertPurchaseSuggestionsResponse response = service.convertPurchaseSuggestions(request(m, d), "buyer");
        assertThat(response.getConvertedSuggestionIds()).hasSize(2);
    }

    @Test
    @DisplayName("AC 7: on success the request names the resolved vendor and each suggestion stores it with convertedAt")
    void successStoresVendorAndConvertedAt() {
        PurchaseSuggestion s = accepted(SuggestedVendorType.DISTRIBUTOR, "DIST-001");
        when(vendorFeedMappingService.resolve(FeedVendorType.DISTRIBUTOR, "DIST-001"))
                .thenReturn(Optional.of(vendor(VENDOR, VendorStatus.ACTIVE)));

        ConvertPurchaseSuggestionsResponse response = service.convertPurchaseSuggestions(request(s), "buyer");

        ArgumentCaptor<PurchaseOrderRequestedV1> command = ArgumentCaptor.forClass(PurchaseOrderRequestedV1.class);
        verify(purchaseOrderCommandPublisher).request(command.capture());
        assertThat(command.getValue().vendorId()).isEqualTo(VENDOR);
        assertThat(s.getStatus()).isEqualTo(PurchaseSuggestionStatus.CONVERTED);
        assertThat(s.getVendorId()).isEqualTo(VENDOR);
        assertThat(s.getConvertedAt()).isEqualTo(NOW);
        assertThat(response.getPurchaseOrderId()).isEqualTo(command.getValue().purchaseOrderId());
    }
}
