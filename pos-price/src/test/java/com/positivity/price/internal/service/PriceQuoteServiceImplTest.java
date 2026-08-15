package com.positivity.price.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.price.internal.dto.PriceQuoteRequest;
import com.positivity.price.internal.dto.PriceQuoteResponse;
import com.positivity.price.internal.dto.PricingBreakdownEntry;
import com.positivity.price.internal.entity.CustomerTierPricingRule;
import com.positivity.price.internal.entity.LocationPriceOverride;
import com.positivity.price.internal.entity.ProductBasePrice;
import com.positivity.price.internal.exception.BasePriceUnavailableException;
import com.positivity.price.internal.repository.CustomerTierPricingRuleRepository;
import com.positivity.price.internal.repository.LocationPriceOverrideRepository;
import com.positivity.price.internal.repository.ProductBasePriceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

/**
 * Contextual price quoting (#51): the base price is the MSRP, a location override replaces it,
 * and a customer-tier rule discounts whatever survived — each step recorded in the breakdown.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PriceQuoteServiceImpl")
class PriceQuoteServiceImplTest {

    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fff01");
    private static final UUID LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fff02");
    private static final UUID TIER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fff03");
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    @Mock
    private ProductBasePriceRepository productPriceRepository;

    @Mock
    private LocationPriceOverrideRepository locationOverrideRepository;

    @Mock
    private CustomerTierPricingRuleRepository customerTierPricingRuleRepository;

    private PriceQuoteServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PriceQuoteServiceImpl(
                productPriceRepository,
                locationOverrideRepository,
                customerTierPricingRuleRepository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                "usd");
        when(locationOverrideRepository.findActiveAt(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(customerTierPricingRuleRepository.findActiveAt(any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    private static PriceQuoteRequest request(int quantity) {
        PriceQuoteRequest request = new PriceQuoteRequest();
        request.setProductId(PRODUCT_ID);
        request.setQuantity(quantity);
        request.setLocationId(LOCATION_ID);
        request.setCustomerTierId(TIER_ID);
        return request;
    }

    private void stubBasePrice(String msrp, String currency) {
        ProductBasePrice basePrice = new ProductBasePrice();
        basePrice.setProductId(PRODUCT_ID);
        basePrice.setMsrp(new BigDecimal(msrp));
        basePrice.setCurrency(currency);
        when(productPriceRepository.findActiveAt(eq(PRODUCT_ID), any(), any())).thenReturn(Optional.of(basePrice));
    }

    private void stubLocationOverride(String overridePrice) {
        LocationPriceOverride override = new LocationPriceOverride();
        override.setProductId(PRODUCT_ID);
        override.setLocationId(LOCATION_ID);
        override.setOverridePrice(new BigDecimal(overridePrice));
        override.setCurrency("USD");
        when(locationOverrideRepository.findActiveAt(eq(PRODUCT_ID), eq(LOCATION_ID), any(), any()))
                .thenReturn(Optional.of(override));
    }

    private void stubTierRule(String discountRate) {
        CustomerTierPricingRule rule = new CustomerTierPricingRule();
        rule.setProductId(PRODUCT_ID);
        rule.setCustomerTierId(TIER_ID);
        rule.setDiscountRate(new BigDecimal(discountRate));
        when(customerTierPricingRuleRepository.findActiveAt(eq(PRODUCT_ID), eq(TIER_ID), any()))
                .thenReturn(Optional.of(rule));
    }

    @Test
    void quotesTheMsrpWhenNoOverrideOrTierRuleApplies() {
        stubBasePrice("100.00", "USD");

        PriceQuoteResponse response = service.calculatePrice(request(2));

        assertThat(response.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(response.getQuantity()).isEqualTo(2);
        assertThat(response.getMsrp().getAmount()).isEqualByComparingTo("100.00");
        assertThat(response.getUnitPrice().getAmount()).isEqualByComparingTo("100.00");
        assertThat(response.getExtendedPrice().getAmount()).isEqualByComparingTo("200.00");
        assertThat(response.getPriceSource()).isEqualTo("MSRP_FALLBACK");
        assertThat(response.getWarnings()).isEmpty();
        assertThat(response.getPricingBreakdown())
                .extracting(PricingBreakdownEntry::getRuleName)
                .containsExactly("BASE_PRICE");
    }

    @Test
    void letsALocationOverrideReplaceTheMsrp() {
        stubBasePrice("100.00", "USD");
        stubLocationOverride("90.00");

        PriceQuoteResponse response = service.calculatePrice(request(1));

        assertThat(response.getUnitPrice().getAmount()).isEqualByComparingTo("90.00");
        assertThat(response.getPriceSource()).isEqualTo("CALCULATED");
        assertThat(response.getPricingBreakdown())
                .extracting(PricingBreakdownEntry::getRuleName)
                .containsExactly("BASE_PRICE", "LOCATION_OVERRIDE");
        assertThat(response.getPricingBreakdown().get(1).getAdjustment().getAmount())
                .isEqualByComparingTo("-10.00");
        assertThat(response.getPricingBreakdown().get(1).getResultingValue().getAmount())
                .isEqualByComparingTo("90.00");
        assertThat(response.getPricingBreakdown().get(1).getRuleType()).isEqualTo("LOCATION_OVERRIDE");
    }

    @Test
    void appliesTheTierDiscountToTheMsrpWhenThereIsNoOverride() {
        stubBasePrice("100.00", "USD");
        stubTierRule("0.10");

        PriceQuoteResponse response = service.calculatePrice(request(1));

        assertThat(response.getUnitPrice().getAmount()).isEqualByComparingTo("90.00");
        assertThat(response.getPriceSource()).isEqualTo("CALCULATED");
        assertThat(response.getPricingBreakdown())
                .extracting(PricingBreakdownEntry::getRuleName)
                .containsExactly("BASE_PRICE", "CUSTOMER_TIER_DISCOUNT");
    }

    @Test
    void appliesTheTierDiscountOnTopOfALocationOverride() {
        stubBasePrice("100.00", "USD");
        stubLocationOverride("80.00");
        stubTierRule("0.25");

        PriceQuoteResponse response = service.calculatePrice(request(3));

        // 80 - 25% = 60, times three lines.
        assertThat(response.getUnitPrice().getAmount()).isEqualByComparingTo("60.00");
        assertThat(response.getExtendedPrice().getAmount()).isEqualByComparingTo("180.00");
        assertThat(response.getPricingBreakdown())
                .extracting(PricingBreakdownEntry::getRuleName)
                .containsExactly("BASE_PRICE", "LOCATION_OVERRIDE", "CUSTOMER_TIER_DISCOUNT");
    }

    @Test
    void roundsTheUnitPriceToTwoPlacesHalfEven() {
        stubBasePrice("100.00", "USD");
        stubTierRule("0.125");

        // 100 − 12.5% = 87.50 exactly; a third of a cent would round to the even neighbour.
        assertThat(service.calculatePrice(request(1)).getUnitPrice().getAmount())
                .isEqualByComparingTo("87.50");
    }

    @Test
    void quotesInTheRequestedCurrencyUppercased() {
        stubBasePrice("100.00", "CAD");
        PriceQuoteRequest request = request(1);
        request.setCurrency("cad");

        PriceQuoteResponse response = service.calculatePrice(request);

        assertThat(response.getMsrp().getCurrency()).isEqualTo("CAD");
        verify(productPriceRepository).findActiveAt(PRODUCT_ID, "CAD", NOW);
    }

    @Test
    void fallsBackToTheConfiguredCurrencyWhenTheRequestNamesNone() {
        stubBasePrice("100.00", "USD");

        service.calculatePrice(request(1));

        // The configured default is lower-cased in configuration and normalized on construction.
        verify(productPriceRepository).findActiveAt(PRODUCT_ID, "USD", NOW);
    }

    @Test
    void quotesAsOfTheRequestedTimestampRatherThanNow() {
        stubBasePrice("100.00", "USD");
        Instant asOf = Instant.parse("2026-02-01T00:00:00Z");
        PriceQuoteRequest request = request(1);
        request.setEffectiveTimestamp(asOf);

        service.calculatePrice(request);

        verify(productPriceRepository).findActiveAt(PRODUCT_ID, "USD", asOf);
        verify(locationOverrideRepository).findActiveAt(PRODUCT_ID, LOCATION_ID, "USD", asOf);
        verify(customerTierPricingRuleRepository).findActiveAt(PRODUCT_ID, TIER_ID, asOf);
    }

    @Test
    void failsWhenNoBasePriceIsActiveForTheProduct() {
        when(productPriceRepository.findActiveAt(any(), any(), any())).thenReturn(Optional.empty());
        PriceQuoteRequest request = request(1);

        assertThatThrownBy(() -> service.calculatePrice(request)).isInstanceOf(BasePriceUnavailableException.class);
    }
}
