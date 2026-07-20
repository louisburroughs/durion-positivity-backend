package com.positivity.invoice.internal.settlement;

import com.positivity.domainevents.payment.SettlementProviderConfigV1;
import com.positivity.domainevents.payment.SettlementReportedV1;
import com.positivity.invoice.internal.exception.SettlementSourceException;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Placeholder {@link SettlementSourcePort} used until a real processor settlement feed is integrated
 * (story F1b, issue #962). Mirrors {@link
 * com.positivity.invoice.internal.payment.UnavailablePaymentGatewayAdapter}: every operation throws
 * {@link SettlementSourceException} so the missing integration fails loudly rather than silently
 * emitting nothing.
 */
public class UnavailableSettlementSourceAdapter implements SettlementSourcePort {

    private static final Logger log = LoggerFactory.getLogger(UnavailableSettlementSourceAdapter.class);

    public UnavailableSettlementSourceAdapter() {
        log.warn("No SettlementSourcePort implementation configured. Settlement reconciliation feed is unavailable "
                + "until a real processor settlement adapter is provided.");
    }

    @Override
    public @NonNull List<SettlementReportedV1> fetchSettlements(@Nullable String cursor) {
        throw unavailable();
    }

    @Override
    public @NonNull SettlementProviderConfigV1 currentConfig() {
        throw unavailable();
    }

    private SettlementSourceException unavailable() {
        return new SettlementSourceException("No SettlementSourcePort implementation is configured for pos-invoice. "
                + "Provide a processor settlement adapter to enable settlement reconciliation.");
    }
}
