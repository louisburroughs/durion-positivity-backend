package com.positivity.invoice.internal.exception;

/**
 * Thrown when a settlement source operation fails (story F1b, issue #962).
 *
 * <p>Mirrors {@link PaymentGatewayException}: raised by the {@code SettlementSourcePort}
 * implementation when no real processor settlement feed is configured, or when a configured feed
 * cannot be reached.
 */
public class SettlementSourceException extends RuntimeException {

    public SettlementSourceException(String message) {
        super(message);
    }

    public SettlementSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
