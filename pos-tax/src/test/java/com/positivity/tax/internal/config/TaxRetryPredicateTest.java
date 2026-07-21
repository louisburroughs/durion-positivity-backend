package com.positivity.tax.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.tax.internal.exception.TaxCalculationException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Story T6 / ADR-0021: the retry predicate must retry only transient failures (5xx,
 * timeouts, connect errors) and never a deterministic 4xx.
 */
class TaxRetryPredicateTest {

    private final TaxRetryPredicate predicate = new TaxRetryPredicate();

    @Test
    @DisplayName("retries a 5xx server error")
    void retries5xx() {
        Throwable wrapped = new TaxCalculationException(
                "external failed",
                HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", null, null, null));
        assertThat(predicate.test(wrapped)).isTrue();
    }

    @Test
    @DisplayName("does NOT retry a 4xx client error (deterministic data error)")
    void doesNotRetry4xx() {
        Throwable wrapped = new TaxCalculationException(
                "bad address",
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", null, null, null));
        assertThat(predicate.test(wrapped)).isFalse();
    }

    @Test
    @DisplayName("does NOT retry a 4xx even when it is a 422")
    void doesNotRetry422() {
        Throwable wrapped = new TaxCalculationException(
                "unprocessable",
                HttpClientErrorException.create(HttpStatus.UNPROCESSABLE_ENTITY, "Unprocessable", null, null, null));
        assertThat(predicate.test(wrapped)).isFalse();
    }

    @Test
    @DisplayName("retries a connect/read timeout")
    void retriesTimeout() {
        Throwable wrapped = new TaxCalculationException(
                "timeout", new ResourceAccessException("read timed out", new SocketTimeoutException("Read timed out")));
        assertThat(predicate.test(wrapped)).isTrue();
    }

    @Test
    @DisplayName("retries a connect error")
    void retriesConnectError() {
        Throwable wrapped =
                new TaxCalculationException("connect", new ResourceAccessException("refused", new ConnectException()));
        assertThat(predicate.test(wrapped)).isTrue();
    }

    @Test
    @DisplayName("does NOT retry an unknown non-transport failure")
    void doesNotRetryUnknown() {
        assertThat(predicate.test(new TaxCalculationException("mapping bug", new IllegalStateException("boom"))))
                .isFalse();
    }
}
