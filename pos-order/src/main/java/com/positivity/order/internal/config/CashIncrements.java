package com.positivity.order.internal.config;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The cash increment per currency (ADR-0067 OP-9, PC-7 (b); #2662): the smallest coin a drawer can hand back, to
 * which pos-order rounds a cash tender at the register. Bound from {@code pos.order.cash-increments.<CUR>}
 * ({@code POS_ORDER_CASH_INCREMENTS_CAD=0.05}); the shipped default lists {@code CAD: 0.05} and nothing else, so an
 * operator adds or corrects a currency without a release. A currency with no entry does not round.
 *
 * <p>The increment is a property of the currency, identical for every tenant paying in it, so it is deployment
 * configuration rather than a tenant setting (ADR-0071 is for choices a tenant makes). pos-invoice and
 * pos-accounting read the increment from the settled fact instead of keeping a second copy.
 *
 * <p>Startup fails, naming the key, on a key that is not an ISO 4217 code, a value that is not positive, one that
 * is not a whole multiple of the currency's minor unit, one equal to a single minor unit (that would be a
 * misconfiguration dressed as a no-op), or one that is not a coin (below one unit it must divide the unit; from
 * one unit up it must be a whole number of units).
 */
@Component
@Slf4j
public class CashIncrements {

    static final String PROPERTY_PREFIX = "pos.order.cash-increments";

    private final Map<String, BigDecimal> increments;

    @Autowired
    public CashIncrements(@NonNull Environment environment) {
        this(Binder.get(environment)
                .bind(PROPERTY_PREFIX, Bindable.mapOf(String.class, BigDecimal.class))
                .orElse(Map.of()));
    }

    public CashIncrements(@NonNull Map<String, BigDecimal> configured) {
        Map<String, BigDecimal> validated = new LinkedHashMap<>();
        configured.forEach((rawCode, increment) -> {
            String code = rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
            String key = PROPERTY_PREFIX + "." + rawCode;
            if (!FunctionalCurrency.isIsoCode(code)) {
                throw new IllegalStateException(key + " is not an ISO 4217 currency code");
            }
            if (increment == null || increment.signum() <= 0) {
                throw new IllegalStateException(key + " must be greater than zero, was " + increment);
            }
            BigDecimal minorUnit = BigDecimal.ONE.movePointLeft(
                    Math.max(0, Currency.getInstance(code).getDefaultFractionDigits()));
            if (increment.remainder(minorUnit).signum() != 0) {
                throw new IllegalStateException(
                        key + " = " + increment + " is not a whole multiple of the currency's minor unit " + minorUnit);
            }
            if (increment.compareTo(minorUnit) == 0) {
                throw new IllegalStateException(key + " = " + increment
                        + " equals the currency's minor unit; omit the currency instead of listing a no-op");
            }
            // A cash increment is a coin: below one unit it divides the unit (0.05, 0.10, 0.25, 0.50); from one
            // unit up it is a whole number of units (JPY 5). 0.07 is neither.
            boolean coin = increment.compareTo(BigDecimal.ONE) < 0
                    ? BigDecimal.ONE.remainder(increment).signum() == 0
                    : increment.stripTrailingZeros().scale() <= 0;
            if (!coin) {
                throw new IllegalStateException(key + " = " + increment
                        + " is not a coin: a cash increment below one unit must divide the unit (0.05, 0.10, 0.25,"
                        + " 0.50) and one of a unit or more must be a whole number of units");
            }
            validated.put(code, increment.stripTrailingZeros());
        });
        this.increments = Collections.unmodifiableMap(validated);
        log.info("Cash increments in force (ADR-0067 A8): {}", validated.isEmpty() ? "none" : validated);
    }

    /** The increment a drawer in {@code currencyCode} rounds cash to, empty when the currency does not round. */
    public Optional<BigDecimal> of(@NonNull String currencyCode) {
        return Optional.ofNullable(increments.get(currencyCode.trim().toUpperCase(Locale.ROOT)));
    }
}
