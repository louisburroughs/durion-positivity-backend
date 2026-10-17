package com.positivity.accounting.internal.service;

import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The settlement sweep's durations (#2642): {@code accounting.ap.settlement.*}. */
@Component
public class APPaymentSettlementSettings {

    private final Duration inFlightWarning;

    public APPaymentSettlementSettings(
            @Value("${accounting.ap.settlement.in-flight-warning:P7D}") Duration inFlightWarning) {
        this.inFlightWarning = inFlightWarning;
    }

    /** How long a charge may stay in flight before the read flags it {@code aged} (default seven days). */
    public @NonNull Duration inFlightWarning() {
        return inFlightWarning;
    }
}
