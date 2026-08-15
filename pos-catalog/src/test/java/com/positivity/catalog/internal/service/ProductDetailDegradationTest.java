package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.client.InventoryClient;
import com.positivity.catalog.internal.client.PricingClient;
import com.positivity.catalog.internal.client.SupplierStockClient;
import com.positivity.catalog.internal.dto.ProductDetailView;
import com.positivity.catalog.internal.dto.ProductDetailView.DataConfidence;
import com.positivity.catalog.internal.dto.ProductDetailView.DataStatus;
import com.positivity.catalog.internal.dto.ProductDetailView.LeadTimeSource;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.repository.ProductReplacementRepository;
import com.positivity.catalog.internal.repository.ProductRepository;
import java.math.BigDecimal;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

/**
 * Branch-coverage tests for {@link ProductDetailServiceImpl}, whose whole design
 * is graceful degradation.
 *
 * <p>
 * This view stitches together the catalog's own row with live pricing from
 * pos-price and live availability from pos-inventory. Either remote call may
 * fail, and when one does the endpoint deliberately still answers — with the
 * parts it could get and a {@code confidence} saying how much of it is real.
 * That makes the failure branches the important ones: they are the normal
 * operating mode during any partial outage, and they are invisible from a happy
 * path test because the response still looks complete.
 *
 * <p>
 * The signal that separates a good answer from a degraded one is the pair
 * {@code status} / {@code confidence}. A caller decides from those whether to
 * show a price to a customer, so reporting HIGH over stale or absent data is
 * worse than returning nothing at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProductDetailServiceImpl — degradation and confidence")
class ProductDetailDegradationTest {

    private static final Instant NOW = Instant.parse("2026-08-12T08:00:00Z");
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

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
    private ProductEntity product;

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

        product = new ProductEntity();
        product.setId(PRODUCT_ID);
        product.setSku("SKU-1001");
        product.setLongDescription("Front brake rotor");
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productReplacementRepository.findAll()).thenReturn(List.of());

        pricingWorks();
        availabilityWorks();
        leadTimeAbsent();
    }

    private void pricingWorks() {
        when(pricingClient.fetchPrice(any(), any(), any()))
                .thenReturn(Optional.of(new PricingClient.PriceQuoteClientResponse(
                        new BigDecimal("129.99"), "USD", new BigDecimal("99.99"), "PRICE_BOOK")));
    }

    private void availabilityWorks() {
        when(inventoryClient.fetchAvailability(any(), any()))
                .thenReturn(Optional.of(new InventoryClient.AvailabilityClientResponse(12, 2, 10, "EA")));
    }

    private void leadTimeAbsent() {
        when(inventoryClient.fetchLeadTime(any(), any())).thenReturn(Optional.empty());
    }

    private ProductDetailView detail() {
        return service.getProductDetail(PRODUCT_ID, LOCATION_ID);
    }

    // ─── the confidence matrix ───────────────────────────────────────────────

    @Test
    @DisplayName("both remote calls succeeding is HIGH confidence")
    void bothRemotesUpIsHigh() {
        ProductDetailView view = detail();

        assertThat(view.getConfidence()).isEqualTo(DataConfidence.HIGH);
        assertThat(view.getPricing().getStatus()).isEqualTo(DataStatus.OK);
        assertThat(view.getAvailability().getStatus()).isEqualTo(DataStatus.OK);
        assertThat(view.getPricing().getMsrp()).isEqualTo(129.99);
        assertThat(view.getPricing().getStorePrice()).isEqualTo(99.99);
        assertThat(view.getAvailability().getOnHandQuantity()).isEqualTo(12);
    }

    @ParameterizedTest(name = "pricing up={0}, availability up={1} -> {2}")
    @CsvSource({
        "true,  true,  HIGH",
        "true,  false, MEDIUM",
        "false, true,  MEDIUM",
        "false, false, LOW",
    })
    @DisplayName("overall confidence reports how much of the view is real")
    void confidenceMatrix(boolean pricingUp, boolean availabilityUp, DataConfidence expected) {
        if (!pricingUp) {
            when(pricingClient.fetchPrice(any(), any(), any())).thenThrow(new RuntimeException("pos-price down"));
        }
        if (!availabilityUp) {
            when(inventoryClient.fetchAvailability(any(), any())).thenThrow(new RuntimeException("pos-inventory down"));
        }

        // This is what a caller branches on before showing a number to a customer. The two
        // MEDIUM cases are the ones worth having: they are asymmetric in what is missing but
        // identical in what they report, so a caller cannot tell price from stock — which is
        // why the per-section status below is also part of the contract.
        assertThat(detail().getConfidence()).isEqualTo(expected);
    }

    @Test
    @DisplayName("a pricing outage leaves the rest of the view intact")
    void pricingOutageDegradesOnlyPricing() {
        when(pricingClient.fetchPrice(any(), any(), any())).thenThrow(new RuntimeException("connect timeout"));

        ProductDetailView view = detail();

        // The endpoint still answers. Failing the whole request because pos-price is down would
        // take the parts catalogue offline with it.
        assertThat(view).isNotNull();
        assertThat(view.getPricing().getStatus()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(view.getPricing().getConfidence()).isEqualTo(DataConfidence.LOW);
        // No number is invented: a missing price must be absent, not zero.
        assertThat(view.getPricing().getMsrp()).isNull();
        assertThat(view.getPricing().getStorePrice()).isNull();
        assertThat(view.getAvailability().getStatus()).isEqualTo(DataStatus.OK);
    }

    @Test
    @DisplayName("pos-price answering with no quote is treated exactly like an outage")
    void emptyQuoteIsAlsoUnavailable() {
        when(pricingClient.fetchPrice(any(), any(), any())).thenReturn(Optional.empty());

        // A successful call that carries no quote is not a price of zero and not an error to
        // propagate — it is the same "we do not know" as a timeout, and must report as such.
        assertThat(detail().getPricing().getStatus()).isEqualTo(DataStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("a quote with null amounts does not fabricate zeroes")
    void nullAmountsStayNull() {
        when(pricingClient.fetchPrice(any(), any(), any()))
                .thenReturn(Optional.of(new PricingClient.PriceQuoteClientResponse(null, "USD", null, "PRICE_BOOK")));

        ProductDetailView view = detail();

        // The BigDecimal-to-double conversion is null-guarded on both fields. Without the
        // guards this is a NullPointerException; with a naive default it is a free product.
        assertThat(view.getPricing().getStatus()).isEqualTo(DataStatus.OK);
        assertThat(view.getPricing().getMsrp()).isNull();
        assertThat(view.getPricing().getStorePrice()).isNull();
    }

    @Test
    @DisplayName("an availability outage still returns a catalog lead time")
    void availabilityOutageFallsBackToCatalogLeadTime() {
        when(inventoryClient.fetchAvailability(any(), any())).thenThrow(new RuntimeException("connect timeout"));

        ProductDetailView view = detail();

        // Stock is unknown but the catalog still knows roughly how long the part takes to
        // arrive, so the caller gets something to show rather than an empty panel.
        assertThat(view.getAvailability().getStatus()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(view.getAvailability().getLeadTime()).isNotNull();
        assertThat(view.getAvailability().getLeadTime().getSource()).isEqualTo(LeadTimeSource.CATALOG);
    }

    @Test
    @DisplayName("pos-inventory answering with no availability is treated like an outage")
    void emptyAvailabilityIsAlsoUnavailable() {
        when(inventoryClient.fetchAvailability(any(), any())).thenReturn(Optional.empty());

        ProductDetailView view = detail();

        assertThat(view.getAvailability().getStatus()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(view.getAvailability().getLeadTime().getSource()).isEqualTo(LeadTimeSource.CATALOG);
    }

    // ─── the two-tier lead time ──────────────────────────────────────────────

    @Test
    @DisplayName("a dynamic lead time is preferred over the catalog hint")
    void dynamicLeadTimeWins() {
        when(inventoryClient.fetchLeadTime(any(), any()))
                .thenReturn(Optional.of(new InventoryClient.LeadTimeClientResponse(
                        2, 5, "2-5 business days", "INVENTORY", "HIGH", NOW.minusSeconds(60))));

        var leadTime = detail().getAvailability().getLeadTime();

        assertThat(leadTime.getSource()).isEqualTo(LeadTimeSource.INVENTORY);
        assertThat(leadTime.getMinDays()).isEqualTo(2);
        assertThat(leadTime.getMaxDays()).isEqualTo(5);
        assertThat(leadTime.getConfidence()).isEqualTo(DataConfidence.HIGH);
        // The remote's own timestamp is kept rather than being restamped with request time —
        // a lead time is only meaningful alongside when it was measured.
        assertThat(leadTime.getAsOf()).isEqualTo(NOW.minusSeconds(60));
    }

    @Test
    @DisplayName("a dynamic lead time with no timestamp is stamped with the request time")
    void missingAsOfFallsBackToRequestTime() {
        when(inventoryClient.fetchLeadTime(any(), any()))
                .thenReturn(Optional.of(new InventoryClient.LeadTimeClientResponse(
                        2, 5, "2-5 business days", "INVENTORY", "HIGH", null)));

        assertThat(detail().getAvailability().getLeadTime().getAsOf()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a lead-time lookup that throws falls back to the catalog rather than failing")
    void leadTimeFailureFallsBackToCatalog() {
        when(inventoryClient.fetchLeadTime(any(), any())).thenThrow(new RuntimeException("timeout"));

        // Availability itself succeeded, so the section stays OK — only the lead time degrades.
        // Letting this exception escape would turn a working stock read into an outage.
        ProductDetailView view = detail();
        assertThat(view.getAvailability().getStatus()).isEqualTo(DataStatus.OK);
        assertThat(view.getAvailability().getLeadTime().getSource()).isEqualTo(LeadTimeSource.CATALOG);
    }

    @ParameterizedTest(name = "source=[{0}] falls back to INVENTORY")
    @CsvSource(
            value = {"NULL", "''", "'   '", "NOT_A_SOURCE"},
            nullValues = "NULL")
    @DisplayName("an unrecognised lead-time source degrades to INVENTORY rather than throwing")
    void unparseableSourceFallsBack(String source) {
        when(inventoryClient.fetchLeadTime(any(), any()))
                .thenReturn(
                        Optional.of(new InventoryClient.LeadTimeClientResponse(2, 5, "2-5 days", source, "HIGH", NOW)));

        // These values come off the wire from another service. A new enum constant added
        // upstream must not take this endpoint down.
        assertThat(detail().getAvailability().getLeadTime().getSource()).isEqualTo(LeadTimeSource.INVENTORY);
    }

    @ParameterizedTest(name = "confidence=[{0}] falls back to MEDIUM")
    @CsvSource(
            value = {"NULL", "''", "'   '", "VERY_SURE"},
            nullValues = "NULL")
    @DisplayName("an unrecognised confidence degrades to MEDIUM rather than throwing")
    void unparseableConfidenceFallsBack(String confidence) {
        when(inventoryClient.fetchLeadTime(any(), any()))
                .thenReturn(Optional.of(
                        new InventoryClient.LeadTimeClientResponse(2, 5, "2-5 days", "INVENTORY", confidence, NOW)));

        // MEDIUM, not HIGH: an unreadable confidence is not a reason to trust the value more.
        assertThat(detail().getAvailability().getLeadTime().getConfidence()).isEqualTo(DataConfidence.MEDIUM);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HIGH", "MEDIUM", "LOW"})
    @DisplayName("a recognised confidence is passed through unchanged")
    void recognisedConfidenceIsPassedThrough(String confidence) {
        when(inventoryClient.fetchLeadTime(any(), any()))
                .thenReturn(Optional.of(
                        new InventoryClient.LeadTimeClientResponse(2, 5, "2-5 days", "INVENTORY", confidence, NOW)));

        assertThat(detail().getAvailability().getLeadTime().getConfidence())
                .isEqualTo(DataConfidence.valueOf(confidence));
    }

    @Test
    @DisplayName("a catalog lead time with no hint at all reports LOW confidence")
    void catalogLeadTimeWithoutHintIsLowConfidence() {
        // Nothing on the product row and nothing from inventory: the answer is a shrug, and it
        // must say so rather than presenting a default range as if it were known.
        assertThat(detail().getAvailability().getLeadTime().getConfidence()).isEqualTo(DataConfidence.LOW);
    }

    // ─── the rest of the view ────────────────────────────────────────────────

    @Test
    @DisplayName("an unknown product returns null so the controller can 404")
    void unknownProductReturnsNull() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThat(service.getProductDetail(PRODUCT_ID, LOCATION_ID)).isNull();
    }

    @Test
    @DisplayName("specifications are built only from the attributes that are set")
    void specificationsSkipAbsentAttributes() {
        product.setMaterial("Cast iron");
        product.setColor(null);
        product.setWarranty("24 months");
        product.setManufacturerName(null);

        // Four independent null checks feeding one list. Emitting a row for an unset attribute
        // would show a customer a blank "Colour:" line on every product that has no colour.
        assertThat(detail().getSpecifications())
                .extracting(spec -> spec.getName())
                .containsExactlyInAnyOrder("Material", "Warranty");
    }

    @Test
    @DisplayName("a product with no attributes at all yields no specifications")
    void noAttributesYieldsEmptySpecifications() {
        assertThat(detail().getSpecifications()).isEmpty();
    }

    @Test
    @DisplayName("the view is stamped with the clock, not with wall time")
    void generatedAtComesFromTheClock() {
        // Every asOf on the degraded sections is derived from this one timestamp, so the whole
        // response describes a single moment rather than several.
        assertThat(detail().getGeneratedAt()).isEqualTo(NOW);
    }
}
