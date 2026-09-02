package com.positivity.catalog.internal.spi;

/**
 * A labor-time provider call failed at the transport level — unreachable, timed out, non-2xx,
 * or an undeserializable body. Deliberately unchecked: adapters throw it, the ingest and
 * resolution services are the only catchers, and they convert it to typed degradation
 * (never to a client-facing 5xx for a vendor-side problem).
 */
public class ProviderCallException extends RuntimeException {

    public ProviderCallException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProviderCallException(String message) {
        super(message);
    }
}
