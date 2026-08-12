package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.positivity.order.internal.client.PricingQuote;
import com.positivity.order.internal.entity.ExtProduct;
import com.positivity.order.internal.repository.ExtProductRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link CatalogPricePricingAdapter} (parity story B1).
 *
 * <p>
 * The three {@link PricingQuote.Status} values mean different things to the
 * clerk, and conflating them is the failure this test guards against.
 * UNKNOWN_SKU says the catalog has no active product — a clear signal to fix the
 * SKU. UNAVAILABLE says the module cannot answer right now, either because the
 * product replica is cold or pos-price is unreachable, and the clerk falls back
 * to a permissioned manual price. Neither may silently become a price.
 *
 * <p>
 * A 404 from pos-price is deliberately mapped to UNKNOWN_SKU rather than
 * UNAVAILABLE: the product exists but is not priced, and a manual-price fallback
 * would hide a catalog gap behind a made-up number.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CatalogPricePricingAdapter — SKU resolution and pos-price quoting")
class CatalogPricePricingAdapterTest {

    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-000000000061");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000062");
    private static final UUID TIER_ID = UUID.fromString("00000000-0000-0000-0000-000000000063");
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");

    @Mock
    private ExtProductRepository extProductRepository;

    private MockRestServiceServer server;

    private CatalogPricePricingAdapter adapter(boolean eventFeedEnabled) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://price");
        server = MockRestServiceServer.bindTo(builder).build();
        return new CatalogPricePricingAdapter(
                extProductRepository, builder.build(), Clock.fixed(NOW, ZoneOffset.UTC), eventFeedEnabled, TIER_ID);
    }

    private static ExtProduct product(String name) {
        ExtProduct product = new ExtProduct();
        product.setProductId(PRODUCT_ID);
        product.setSku("BRK-100");
        product.setName(name);
        product.setActive(true);
        product.setTrackingLevel("SERIAL");
        return product;
    }

    private void replicaHas(ExtProduct product) {
        when(extProductRepository.count()).thenReturn(25L);
        when(extProductRepository.findFirstBySkuIgnoreCaseAndActiveTrue("BRK-100"))
                .thenReturn(Optional.ofNullable(product));
    }

    @Test
    @DisplayName("quotes a known SKU and carries the rule breakdown for line-level audit")
    void quotesKnownSku() {
        CatalogPricePricingAdapter adapter = adapter(true);
        replicaHas(product("Brake pad"));
        server.expect(requestTo("http://price/v1/price/quotes"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Authorities", "price:quote"))
                .andExpect(jsonPath("$.productId").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.locationId").value(LOCATION_ID.toString()))
                .andExpect(jsonPath("$.customerTierId").value(TIER_ID.toString()))
                .andExpect(jsonPath("$.effectiveTimestamp").value(NOW.toString()))
                .andRespond(withSuccess("""
                        {"unitPrice":{"amount":45.50,"currency":"USD"},
                         "pricingBreakdown":{"rules":["BASE"]}}""", MediaType.APPLICATION_JSON));

        PricingQuote quote = adapter.quoteForSku("BRK-100", 2, LOCATION_ID, UUID.randomUUID());

        assertThat(quote.status()).isEqualTo(PricingQuote.Status.PRICED);
        assertThat(quote.unitPrice()).isEqualByComparingTo("45.50");
        assertThat(quote.productId()).isEqualTo(PRODUCT_ID);
        assertThat(quote.description()).isEqualTo("Brake pad");
        assertThat(quote.trackingLevel()).isEqualTo("SERIAL");
        // pos-price exposes no snapshot id, so the breakdown JSON is the audit artifact.
        assertThat(quote.breakdownJson()).contains("BASE");
        server.verify();
    }

    @Test
    @DisplayName("substitutes a nil location when the cart has none, since the quote API requires one")
    void nilLocationIsSubstituted() {
        CatalogPricePricingAdapter adapter = adapter(true);
        replicaHas(product("Brake pad"));
        server.expect(requestTo("http://price/v1/price/quotes"))
                .andExpect(jsonPath("$.locationId").value(new UUID(0L, 0L).toString()))
                .andRespond(withSuccess("{\"unitPrice\":{\"amount\":10.00}}", MediaType.APPLICATION_JSON));

        assertThat(adapter.quoteForSku("BRK-100", 1, null, null).status()).isEqualTo(PricingQuote.Status.PRICED);
        server.verify();
    }

    @Test
    @DisplayName("falls back to the SKU when the replica row carries no product name")
    void namelessProductFallsBackToSku() {
        CatalogPricePricingAdapter adapter = adapter(true);
        replicaHas(product(null));
        server.expect(requestTo("http://price/v1/price/quotes"))
                .andRespond(withSuccess("{\"unitPrice\":{\"amount\":10.00}}", MediaType.APPLICATION_JSON));

        PricingQuote quote = adapter.quoteForSku("BRK-100", 1, LOCATION_ID, null);

        // The description reaches the receipt, so it must never be blank.
        assertThat(quote.description()).isEqualTo("BRK-100");
        assertThat(quote.breakdownJson()).isNull();
    }

    @Test
    @DisplayName("reports UNAVAILABLE while the event feed is off, without calling pos-price")
    void coldReplicaByFlag() {
        CatalogPricePricingAdapter adapter = adapter(false);
        when(extProductRepository.count()).thenReturn(25L);

        assertThat(adapter.quoteForSku("BRK-100", 1, LOCATION_ID, null).status())
                .isEqualTo(PricingQuote.Status.UNAVAILABLE);

        // Cold means "cannot distinguish unknown from unsynced", so no lookup and no quote call.
        verify(extProductRepository, never()).findFirstBySkuIgnoreCaseAndActiveTrue(any());
        server.verify();
    }

    @Test
    @DisplayName("reports UNAVAILABLE on an empty replica rather than calling the SKU unknown")
    void coldReplicaByEmptyTable() {
        CatalogPricePricingAdapter adapter = adapter(true);
        when(extProductRepository.count()).thenReturn(0L);

        assertThat(adapter.quoteForSku("BRK-100", 1, LOCATION_ID, null).status())
                .isEqualTo(PricingQuote.Status.UNAVAILABLE);
    }

    @Test
    @DisplayName("reports UNKNOWN_SKU when the replica has no active product for it")
    void unknownSku() {
        CatalogPricePricingAdapter adapter = adapter(true);
        replicaHas(null);

        assertThat(adapter.quoteForSku("BRK-100", 1, LOCATION_ID, null).status())
                .isEqualTo(PricingQuote.Status.UNKNOWN_SKU);
    }

    @Test
    @DisplayName("maps a 404 from pos-price to UNKNOWN_SKU so a catalog gap is visible")
    void notPricedIsUnknownSku() {
        CatalogPricePricingAdapter adapter = adapter(true);
        replicaHas(product("Brake pad"));
        server.expect(requestTo("http://price/v1/price/quotes")).andRespond(withResourceNotFound());

        // Not UNAVAILABLE: a manual-price fallback here would hide the gap behind a made-up number.
        assertThat(adapter.quoteForSku("BRK-100", 1, LOCATION_ID, null).status())
                .isEqualTo(PricingQuote.Status.UNKNOWN_SKU);
    }

    @Test
    @DisplayName("reports UNAVAILABLE when pos-price errors or answers without a unit price")
    void unpricedOrUnreachableIsUnavailable() {
        CatalogPricePricingAdapter adapter = adapter(true);
        replicaHas(product("Brake pad"));
        server.expect(requestTo("http://price/v1/price/quotes")).andRespond(withServerError());

        assertThat(adapter.quoteForSku("BRK-100", 1, LOCATION_ID, null).status())
                .isEqualTo(PricingQuote.Status.UNAVAILABLE);

        CatalogPricePricingAdapter second = adapter(true);
        replicaHas(product("Brake pad"));
        server.expect(requestTo("http://price/v1/price/quotes"))
                .andRespond(withSuccess("{\"currency\":\"USD\"}", MediaType.APPLICATION_JSON));

        assertThat(second.quoteForSku("BRK-100", 1, LOCATION_ID, null).status())
                .isEqualTo(PricingQuote.Status.UNAVAILABLE);
    }
}
