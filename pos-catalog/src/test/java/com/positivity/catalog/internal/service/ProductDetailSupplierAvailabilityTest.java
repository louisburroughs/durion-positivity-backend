package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.client.InventoryClient;
import com.positivity.catalog.internal.client.PricingClient;
import com.positivity.catalog.internal.client.SupplierStockClient;
import com.positivity.catalog.internal.dto.ProductDetailView;
import com.positivity.catalog.internal.dto.ProductDetailView.DataStatus;
import com.positivity.catalog.internal.dto.ProductDetailView.SupplierStockStatus;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.repository.ProductReplacementRepository;
import com.positivity.catalog.internal.repository.ProductRepository;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Product Detail supplier availability component (#1225)")
class ProductDetailSupplierAvailabilityTest {

    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");
    private static final Instant VENDOR_ASOF = Instant.parse("2026-08-14T11:59:00Z");
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final String PROFILE_ID = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c";

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductReplacementRepository productReplacementRepository;

    @Mock
    private PricingClient pricingClient;

    @Mock
    private InventoryClient inventoryClient;

    @Mock
    private SupplierStockClient supplierStockClient;

    private ProductDetailServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductDetailServiceImpl(
                Clock.fixed(NOW, ZoneOffset.UTC),
                productRepository,
                productReplacementRepository,
                pricingClient,
                inventoryClient,
                supplierStockClient,
                new ObjectMapper());
        ReflectionTestUtils.setField(service, "supplierVendorProfileId", PROFILE_ID);

        ProductEntity product = new ProductEntity();
        product.setId(PRODUCT_ID);
        product.setSku("SKU-1001");
        product.setLongDescription("Front brake rotor");
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productReplacementRepository.findAll()).thenReturn(List.of());
        when(pricingClient.fetchPrice(any(), any(), any())).thenReturn(Optional.empty());
        when(inventoryClient.fetchAvailability(anyString(), any())).thenReturn(Optional.empty());
    }

    private void vendorAnswers(String lineStatus, Integer quantity, LocalDate earliest) {
        when(supplierStockClient.inquireStock(any(), any(), any(), any()))
                .thenReturn(Optional.of(new SupplierStockClient.StockInquiryClientResponse(
                        "OK",
                        List.of(new SupplierStockClient.StockInquiryClientLine(lineStatus, quantity, earliest)),
                        VENDOR_ASOF)));
    }

    private ProductDetailView.SupplierAvailabilityInfo component() {
        return service.getProductDetail(PRODUCT_ID, LOCATION_ID).getSupplierAvailability();
    }

    @Test
    void showsWhatTheVendorSaidWhenItAnswers() {
        vendorAnswers("AVAILABLE", 8, LocalDate.of(2026, 8, 20));

        ProductDetailView.SupplierAvailabilityInfo info = component();

        assertThat(info.getStatus()).isEqualTo(DataStatus.OK);
        assertThat(info.getVendorStatus()).isEqualTo(SupplierStockStatus.AVAILABLE);
        assertThat(info.getAvailableQuantity()).isEqualTo(8);
        assertThat(info.getEarliestDeliveryDate()).isEqualTo(LocalDate.of(2026, 8, 20));
    }

    @Test
    void datesTheComponentByWhenTheVendorSaidItNotByWhenThePageRendered() {
        vendorAnswers("AVAILABLE", 8, null);

        // A cached supplier answer carries the age of the fact. Restamping it here would make a
        // minute-old availability look like it was just confirmed.
        assertThat(component().getAsOf()).isEqualTo(VENDOR_ASOF);
    }

    @Test
    void keepsAVendorStatedZeroAsAFact() {
        vendorAnswers("UNAVAILABLE", 0, null);

        ProductDetailView.SupplierAvailabilityInfo info = component();

        assertThat(info.getStatus()).isEqualTo(DataStatus.OK);
        assertThat(info.getVendorStatus()).isEqualTo(SupplierStockStatus.UNAVAILABLE);
        assertThat(info.getAvailableQuantity()).isZero();
    }

    @Test
    void neverTurnsSilenceIntoZero() {
        vendorAnswers("NOT_ANSWERED", null, null);

        ProductDetailView.SupplierAvailabilityInfo info = component();

        // The whole chain exists to keep these apart: a page must say it does not know rather than
        // say the vendor has none.
        assertThat(info.getAvailableQuantity()).isNull();
        assertThat(info.getStatus()).isEqualTo(DataStatus.UNAVAILABLE);
    }

    @Test
    void treatsAnArticleTheVendorDoesNotCarryAsADefiniteAnswer() {
        vendorAnswers("NOT_LISTED", null, null);

        ProductDetailView.SupplierAvailabilityInfo info = component();

        // "We don't stock that" is information, not a failure to answer, so the data status is OK
        // even though there is no quantity.
        assertThat(info.getStatus()).isEqualTo(DataStatus.OK);
        assertThat(info.getVendorStatus()).isEqualTo(SupplierStockStatus.NOT_LISTED);
        assertThat(info.getAvailableQuantity()).isNull();
    }

    @Test
    void degradesWithoutQuantitiesWhenTheSupplierCouldNotAnswer() {
        when(supplierStockClient.inquireStock(any(), any(), any(), any())).thenReturn(Optional.empty());

        ProductDetailView.SupplierAvailabilityInfo info = component();

        assertThat(info.getStatus()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(info.getAvailableQuantity()).isNull();
        assertThat(info.getVendorStatus()).isNull();
    }

    @Test
    void omitsTheComponentEntirelyWhenTheDeploymentHasNoSupplier() {
        ReflectionTestUtils.setField(service, "supplierVendorProfileId", "");

        assertThat(service.getProductDetail(PRODUCT_ID, LOCATION_ID).getSupplierAvailability())
                .isNull();
        // A component that is always present and always degraded teaches a reader to skip it.
        verifyNoInteractions(supplierStockClient);
    }

    @Test
    void omitsTheComponentWhenTheConfiguredProfileIsNotAUuid() {
        ReflectionTestUtils.setField(service, "supplierVendorProfileId", "not-a-uuid");

        assertThat(service.getProductDetail(PRODUCT_ID, LOCATION_ID).getSupplierAvailability())
                .isNull();
        verify(supplierStockClient, never()).inquireStock(any(), any(), any(), any());
    }

    @Test
    void asksAboutTheProductAtTheLocationTheCallerNamed() {
        vendorAnswers("AVAILABLE", 4, null);

        service.getProductDetail(PRODUCT_ID, LOCATION_ID);

        // Availability is consignee-specific; asking without the caller's location would answer for
        // somewhere else.
        verify(supplierStockClient).inquireStock(UUID.fromString(PROFILE_ID), LOCATION_ID, null, "SKU-1001");
    }

    @Test
    void aSupplierFailureNeverStopsTheRestOfThePage() {
        when(supplierStockClient.inquireStock(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("supplier service exploded"));

        // The client is the layer that swallows; if something still escapes it, the composition must
        // not turn a vendor problem into a 500 on a product page.
        assertThat(service.getProductDetail(PRODUCT_ID, LOCATION_ID)).isNotNull();
    }
}
