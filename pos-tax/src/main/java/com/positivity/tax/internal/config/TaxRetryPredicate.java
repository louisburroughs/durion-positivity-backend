package com.positivity.tax.internal.config;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.function.Predicate;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Retry-eligibility predicate for external tax provider calls (story T6, ADR-0021).
 * <p>
 * Retry ONLY on transient failures — {@code 5xx} responses, connect/read timeouts and
 * other I/O errors. NEVER retry {@code 4xx}: per ADR-0021 a 400 from the tax service is a
 * deterministic data error (e.g. invalid address) that will fail identically on every
 * attempt, so retrying only wastes latency and hammers the provider.
 * <p>
 * Because {@code ExternalTaxServiceClient} wraps provider errors in
 * {@code TaxCalculationException}, the predicate walks the cause chain to find the
 * originating HTTP/transport exception.
 */
public final class TaxRetryPredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            if (cause instanceof HttpServerErrorException) {
                // 5xx — transient, retry.
                return true;
            }
            if (cause instanceof RestClientResponseException responseException) {
                // Any other status-carrying response (incl. 4xx): retry only 5xx.
                return responseException.getStatusCode().is5xxServerError();
            }
            if (cause instanceof ResourceAccessException
                    || cause instanceof SocketTimeoutException
                    || cause instanceof IOException) {
                // Connect/read timeout or transport I/O error — transient, retry.
                return true;
            }
            cause = cause.getCause();
        }
        // Unknown/non-HTTP failure (e.g. a deterministic mapping error): do not retry.
        return false;
    }
}
