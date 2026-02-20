package com.positivity.price.internal.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.positivity.price.internal.entity.CustomerTierPricingRule;
import com.positivity.price.internal.entity.LocationPriceOverride;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Temporal pricing repository behavior")
class TemporalPricingRepositoryTest {

    @Autowired
    private LocationPriceOverrideRepository locationPriceOverrideRepository;

    @Autowired
    private CustomerTierPricingRuleRepository customerTierPricingRuleRepository;

    @Test
    @DisplayName("location override supports future schedule and resolves active window")
    void locationOverrideSupportsFutureScheduleAndResolvesActiveWindow() {
        UUID productId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        LocationPriceOverride current = new LocationPriceOverride();
        current.setProductId(productId);
        current.setLocationId(locationId);
        current.setOverridePrice(new BigDecimal("95.0000"));
        current.setCurrency("USD");
        current.setEffectiveFrom(Instant.parse("2025-01-01T00:00:00Z"));
        current.setEffectiveTo(Instant.parse("2025-06-01T00:00:00Z"));
        locationPriceOverrideRepository.saveAndFlush(current);

        LocationPriceOverride future = new LocationPriceOverride();
        future.setProductId(productId);
        future.setLocationId(locationId);
        future.setOverridePrice(new BigDecimal("99.0000"));
        future.setCurrency("USD");
        future.setEffectiveFrom(Instant.parse("2025-06-01T00:00:00Z"));
        future.setEffectiveTo(null);
        locationPriceOverrideRepository.saveAndFlush(future);

        LocationPriceOverride activeBeforeCutover = locationPriceOverrideRepository.findActiveAt(
                productId,
                locationId,
                Instant.parse("2025-05-01T00:00:00Z")).orElseThrow();

        LocationPriceOverride activeAfterCutover = locationPriceOverrideRepository.findActiveAt(
                productId,
                locationId,
                Instant.parse("2025-06-15T00:00:00Z")).orElseThrow();

        assertEquals(new BigDecimal("95.0000"), activeBeforeCutover.getOverridePrice());
        assertEquals(new BigDecimal("99.0000"), activeAfterCutover.getOverridePrice());
    }

    @Test
    @DisplayName("location override overlap detection follows half-open interval semantics")
    void locationOverrideOverlapDetectionFollowsHalfOpenIntervalSemantics() {
        UUID productId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        LocationPriceOverride existing = new LocationPriceOverride();
        existing.setProductId(productId);
        existing.setLocationId(locationId);
        existing.setOverridePrice(new BigDecimal("90.0000"));
        existing.setCurrency("USD");
        existing.setEffectiveFrom(Instant.parse("2025-01-01T00:00:00Z"));
        existing.setEffectiveTo(Instant.parse("2025-02-01T00:00:00Z"));
        locationPriceOverrideRepository.saveAndFlush(existing);

        assertTrue(locationPriceOverrideRepository.existsOverlappingEffectiveWindow(
                productId,
                locationId,
                Instant.parse("2025-01-15T00:00:00Z"),
                Instant.parse("2025-01-20T00:00:00Z")));

        assertFalse(locationPriceOverrideRepository.existsOverlappingEffectiveWindow(
                productId,
                locationId,
                Instant.parse("2025-02-01T00:00:00Z"),
                Instant.parse("2025-03-01T00:00:00Z")));
    }

    @Test
    @DisplayName("customer tier rule supports future schedule and overlap detection")
    void customerTierRuleSupportsFutureScheduleAndOverlapDetection() {
        UUID productId = UUID.randomUUID();
        UUID customerTierId = UUID.randomUUID();

        CustomerTierPricingRule current = new CustomerTierPricingRule();
        current.setProductId(productId);
        current.setCustomerTierId(customerTierId);
        current.setDiscountRate(new BigDecimal("0.1000"));
        current.setEffectiveFrom(Instant.parse("2025-01-01T00:00:00Z"));
        current.setEffectiveTo(Instant.parse("2025-04-01T00:00:00Z"));
        customerTierPricingRuleRepository.saveAndFlush(current);

        CustomerTierPricingRule future = new CustomerTierPricingRule();
        future.setProductId(productId);
        future.setCustomerTierId(customerTierId);
        future.setDiscountRate(new BigDecimal("0.1500"));
        future.setEffectiveFrom(Instant.parse("2025-04-01T00:00:00Z"));
        future.setEffectiveTo(null);
        customerTierPricingRuleRepository.saveAndFlush(future);

        CustomerTierPricingRule activeBeforeCutover = customerTierPricingRuleRepository.findActiveAt(
                productId,
                customerTierId,
                Instant.parse("2025-03-15T00:00:00Z")).orElseThrow();
        CustomerTierPricingRule activeAfterCutover = customerTierPricingRuleRepository.findActiveAt(
                productId,
                customerTierId,
                Instant.parse("2025-05-01T00:00:00Z")).orElseThrow();

        assertEquals(new BigDecimal("0.1000"), activeBeforeCutover.getDiscountRate());
        assertEquals(new BigDecimal("0.1500"), activeAfterCutover.getDiscountRate());
        assertTrue(customerTierPricingRuleRepository.existsOverlappingEffectiveWindow(
                productId,
                customerTierId,
                Instant.parse("2025-03-01T00:00:00Z"),
                Instant.parse("2025-03-20T00:00:00Z")));
    }
}
