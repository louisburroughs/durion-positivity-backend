package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.enums.ApplicationSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Raises a walk-in excess left unapplied by an automatic path (CAP:550 S11, #2508; SPEC-accounting-
 * workspace §4.4 item 4, AW12): a WARN and the counter {@code accounting.walk_in.overpayment}. The excess stays
 * unapplied on the CASH payment, which the unpaid walk-in sales read lists until a person refunds it
 * through pos-invoice; no customer credit is ever created for the CASH account.
 */
@Slf4j
@Component
public class WalkInOverpaymentAlert {

    static final String COUNTER_NAME = "accounting.walk_in.overpayment";

    private final @Nullable Counter counter;

    public WalkInOverpaymentAlert(ObjectProvider<MeterRegistry> meterRegistry) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.counter = registry == null
                ? null
                : Counter.builder(COUNTER_NAME)
                        .description("CASH walk-in payments whose excess was left unapplied instead of kept as a"
                                + " customer credit (#2508)")
                        .register(registry);
    }

    /**
     * Record that {@code excess} of {@code payment} was left unapplied by {@code source}.
     *
     * @param payment the CASH payment, still {@code AVAILABLE}
     * @param excess  what it still has unapplied
     * @param source  the automatic path that left it
     */
    public void excessLeftUnapplied(
            @NonNull ReceivablePayment payment, @NonNull BigDecimal excess, @NonNull ApplicationSource source) {
        if (counter != null) {
            counter.increment();
        }
        log.warn(
                "Walk-in overpayment left unapplied | paymentId={} | unapplied={} {} | path={} | refund it through"
                        + " pos-invoice; the CASH account never keeps a customer credit",
                payment.getPaymentId(),
                excess,
                payment.getCurrency(),
                source);
    }
}
