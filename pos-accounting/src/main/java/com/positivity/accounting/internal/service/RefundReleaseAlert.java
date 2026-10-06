package com.positivity.accounting.internal.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Raises a refund that could not take all of its money off a payment's unapplied remainder (CAP:550,
 * #2556): a WARN and the counter {@code accounting.refund.unreleased}, tagged by reason.
 *
 * <ul>
 *   <li>{@code payment_not_recorded} — the refund arrived before the payment was recorded (the refund
 *       fact was processed before its settlement fact). Nothing is lost: the refund row is stored, and the
 *       settlement that records the payment takes it off then. A count that keeps rising, or a payment
 *       that never arrives (held for its currency, party-less), is for a person.
 *   <li>{@code exceeds_remainder} — the refund is more than the payment still has unapplied. The applied
 *       part stays applied and the invoice still shows paid; a person reverses the application.
 * </ul>
 */
@Slf4j
@Component
public class RefundReleaseAlert {

    static final String COUNTER_NAME = "accounting.refund.unreleased";

    static final String REASON_PAYMENT_NOT_RECORDED = "payment_not_recorded";

    static final String REASON_EXCEEDS_REMAINDER = "exceeds_remainder";

    private final @Nullable Counter paymentNotRecorded;

    private final @Nullable Counter exceedsRemainder;

    public RefundReleaseAlert(ObjectProvider<MeterRegistry> meterRegistry) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.paymentNotRecorded = registry == null ? null : counter(registry, REASON_PAYMENT_NOT_RECORDED);
        this.exceedsRemainder = registry == null ? null : counter(registry, REASON_EXCEEDS_REMAINDER);
    }

    private static Counter counter(MeterRegistry registry, String reason) {
        return Counter.builder(COUNTER_NAME)
                .description("Refunds that could not take all of their money off a payment's unapplied remainder"
                        + " (#2556)")
                .tag("reason", reason)
                .register(registry);
    }

    /**
     * The refunded payment is not recorded yet: its settlement fact releases the refund when it arrives.
     *
     * @param paymentId the refunded payment ({@code paymentIntentId})
     * @param refunded  the refunded amount
     * @param refundId  the refund
     */
    public void paymentNotRecorded(@NonNull UUID paymentId, @NonNull BigDecimal refunded, @NonNull UUID refundId) {
        if (paymentNotRecorded != null) {
            paymentNotRecorded.increment();
        }
        log.warn(
                "Refund before its payment | refundId={} | paymentId={} | refunded={} | the payment is not recorded"
                        + " yet; its settlement takes the refund off the unapplied remainder when it arrives",
                refundId,
                paymentId,
                refunded);
    }

    /**
     * The refund is more than the payment's unapplied remainder: only the remainder was released.
     *
     * @param paymentId the refunded payment
     * @param refunded  what was refunded
     * @param released  what was taken off the unapplied remainder
     * @param source    the refund, or the refunds a settlement released, for the log
     */
    public void exceedsRemainder(
            @NonNull UUID paymentId,
            @NonNull BigDecimal refunded,
            @NonNull BigDecimal released,
            @NonNull String source) {
        if (exceedsRemainder != null) {
            exceedsRemainder.increment();
        }
        log.warn(
                "Refund exceeds the unapplied remainder | paymentId={} | refunded={} | released={} | over={} | {}"
                        + " | the applied part stays applied and the invoice still shows paid; reverse the"
                        + " application by hand",
                paymentId,
                refunded,
                released,
                refunded.subtract(released),
                source);
    }
}
