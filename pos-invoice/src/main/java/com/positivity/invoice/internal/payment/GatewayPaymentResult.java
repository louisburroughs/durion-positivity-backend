package com.positivity.invoice.internal.payment;

import java.math.BigDecimal;

/**
 * Result returned from a payment gateway operation.
 *
 * <p>
 * Implementations are provided by gateway adapters; this interface is
 * the internal port contract for pos-invoice payment processing. It must
 * NOT depend on pos-accounting internals (ADR-0026).
 */
public interface GatewayPaymentResult {

    /** @return {@code true} if the gateway operation succeeded */
    boolean isSuccessful();

    /**
     * @return {@code true} if the gateway outcome is unknown (e.g., timeout)
     *         and a status inquiry should be performed before retrying
     */
    boolean isUnknown();

    /** @return amount processed, or {@code null} for void/unknown results */
    BigDecimal getAmount();

    /** @return opaque gateway transaction reference for status inquiry and void */
    String getGatewayReference();

    /** @return gateway provider identifier (e.g., "stripe") */
    String getGatewayProvider();

    /** @return raw JSON response from gateway for auditability */
    String getRawResponse();
}
