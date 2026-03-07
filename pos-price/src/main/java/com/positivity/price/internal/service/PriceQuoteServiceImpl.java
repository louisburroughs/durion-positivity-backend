package com.positivity.price.internal.service;

import java.time.Clock;

import com.positivity.price.internal.dto.MoneyAmount;
import com.positivity.price.internal.dto.PriceQuoteRequest;
import com.positivity.price.internal.dto.PriceQuoteResponse;
import com.positivity.price.internal.dto.PricingBreakdownEntry;
import com.positivity.price.internal.entity.CustomerTierPricingRule;
import com.positivity.price.internal.entity.LocationPriceOverride;
import com.positivity.price.internal.entity.ProductBasePrice;
import com.positivity.price.internal.exception.ProductNotFoundException;
import com.positivity.price.internal.repository.CustomerTierPricingRuleRepository;
import com.positivity.price.internal.repository.LocationPriceOverrideRepository;
import com.positivity.price.internal.repository.ProductBasePriceRepository;
import com.positivity.price.service.PriceQuoteService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default price quote service implementation for contextual pricing.
 *
 * Issue: #51
 */
@Service
public class PriceQuoteServiceImpl implements PriceQuoteService {
    private final Clock clock;


    private final ProductBasePriceRepository productPriceRepository;
    private final LocationPriceOverrideRepository locationOverrideRepository;
    private final CustomerTierPricingRuleRepository customerTierPricingRuleRepository;

    public PriceQuoteServiceImpl(
            ProductBasePriceRepository productPriceRepository,
            LocationPriceOverrideRepository locationOverrideRepository,
            CustomerTierPricingRuleRepository customerTierPricingRuleRepository,
            Clock clock) {
        this.clock = clock;
        this.productPriceRepository = productPriceRepository;
        this.locationOverrideRepository = locationOverrideRepository;
        this.customerTierPricingRuleRepository = customerTierPricingRuleRepository;
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public PriceQuoteResponse calculatePrice(@NonNull PriceQuoteRequest request) {
        Instant effectiveAt = request.getEffectiveTimestamp() != null ? request.getEffectiveTimestamp() : Instant.now(clock);

        ProductBasePrice basePrice = productPriceRepository.findActiveAt(request.getProductId(), effectiveAt)
                .orElseThrow(() -> new ProductNotFoundException(request.getProductId()));

        List<PricingBreakdownEntry> breakdownEntries = new ArrayList<>();
        String currency = basePrice.getCurrency();
        BigDecimal currentPrice = basePrice.getMsrp();
        String priceSource = "MSRP_FALLBACK";

        breakdownEntries.add(createBreakdownEntry(
                "BASE_PRICE",
                "BASE_PRICE",
                BigDecimal.ZERO,
                currentPrice,
                currency));

        Optional<LocationPriceOverride> locationOverride = locationOverrideRepository.findActiveAt(
                request.getProductId(),
                request.getLocationId(),
                effectiveAt);

        if (locationOverride.isPresent()) {
            BigDecimal adjustment = locationOverride.get().getOverridePrice().subtract(currentPrice);
            currentPrice = locationOverride.get().getOverridePrice();
            priceSource = "CALCULATED";
            breakdownEntries.add(createBreakdownEntry(
                    "LOCATION_OVERRIDE",
                    "LOCATION_OVERRIDE",
                    adjustment,
                    currentPrice,
                    currency));
        }

        Optional<CustomerTierPricingRule> tierRule = customerTierPricingRuleRepository.findActiveAt(
                request.getProductId(),
                request.getCustomerTierId(),
                effectiveAt);

        if (tierRule.isPresent()) {
            BigDecimal discountAmount = currentPrice.multiply(tierRule.get().getDiscountRate());
            BigDecimal adjustment = discountAmount.negate();
            currentPrice = currentPrice.subtract(discountAmount);
            priceSource = "CALCULATED";
            breakdownEntries.add(createBreakdownEntry(
                    "CUSTOMER_TIER_DISCOUNT",
                    "CUSTOMER_TIER",
                    adjustment,
                    currentPrice,
                    currency));
        }

        BigDecimal unitPrice = currentPrice.setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal extendedPrice = unitPrice.multiply(BigDecimal.valueOf(request.getQuantity()));

        PriceQuoteResponse response = new PriceQuoteResponse();
        response.setProductId(request.getProductId());
        response.setQuantity(request.getQuantity());
        response.setMsrp(new MoneyAmount(basePrice.getMsrp(), currency));
        response.setUnitPrice(new MoneyAmount(unitPrice, currency));
        response.setExtendedPrice(new MoneyAmount(extendedPrice, currency));
        response.setPriceSource(priceSource);
        response.setPricingBreakdown(breakdownEntries);
        response.setWarnings(new ArrayList<>());
        return response;
    }

    private PricingBreakdownEntry createBreakdownEntry(
            String ruleName,
            String ruleType,
            BigDecimal adjustment,
            BigDecimal resultingValue,
            String currency) {
        PricingBreakdownEntry entry = new PricingBreakdownEntry();
        entry.setRuleName(ruleName);
        entry.setRuleType(ruleType);
        entry.setAdjustment(new MoneyAmount(adjustment, currency));
        entry.setResultingValue(new MoneyAmount(resultingValue, currency));
        return entry;
    }
}
