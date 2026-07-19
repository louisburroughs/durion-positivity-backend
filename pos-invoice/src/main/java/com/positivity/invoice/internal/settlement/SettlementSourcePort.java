package com.positivity.invoice.internal.settlement;

import com.positivity.domainevents.payment.SettlementProviderConfigV1;
import com.positivity.domainevents.payment.SettlementReportedV1;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port for a payment processor's settlement (payout) feed (story F1b, issue #962).
 *
 * <p>Mirrors the {@link com.positivity.invoice.internal.payment.PaymentGatewayPort} pattern: a real
 * adapter translates one processor's payout model (Stripe payout/balance-transaction, Adyen, Square,
 * Braintree, ACH) into the normalized, processor-agnostic {@link SettlementReportedV1} /
 * {@link SettlementProviderConfigV1} contracts (decision D-5), which {@link SettlementEventPublisher}
 * then emits to pos-accounting via the transactional outbox. Because this module already owns the
 * Stripe-placeholder gateway and the {@code PaymentIntent} correlation ids, the adapter can stamp the
 * platform-side {@code paymentReference} that pos-accounting matches on (decision D-11).
 *
 * <p>No real processor settlement feed is integrated yet, so the default binding is
 * {@link UnavailableSettlementSourceAdapter} (a placeholder that throws), exactly like the payment
 * gateway.
 */
public interface SettlementSourcePort {

    /**
     * Fetch the settlements the processor reported since the given cursor, normalized to the
     * settlement contract. One {@link SettlementReportedV1} per provider settlement (per payout
     * currency, decision D-14).
     *
     * @param cursor opaque provider cursor from the previous poll, or {@code null} for the first poll
     * @return normalized settlement facts, oldest first; empty when there is nothing new
     */
    @NonNull
    List<SettlementReportedV1> fetchSettlements(@Nullable String cursor);

    /**
     * The provider's current settlement matching configuration (decision D-9), to be published on the
     * compacted config topic. Full current config for the provider — never a delta.
     */
    @NonNull
    SettlementProviderConfigV1 currentConfig();
}
