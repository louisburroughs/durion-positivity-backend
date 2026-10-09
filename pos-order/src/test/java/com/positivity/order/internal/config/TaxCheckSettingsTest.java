package com.positivity.order.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

/** CAP:550 S32d item 6 (ADR-0017 §1): Retry-After is never shorter than the tax check's own timeouts. */
@DisplayName("TaxCheckSettings (CAP:550 S32d)")
class TaxCheckSettingsTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withUserConfiguration(TaxCheckClientConfig.class);

    @Test
    @DisplayName("the defaults are connect 1 s, read 3 s and Retry-After 5 s")
    void defaults() {
        context.run(started -> {
            assertThat(started).hasNotFailed();
            TaxCheckSettings settings = started.getBean(TaxCheckSettings.class);
            assertThat(settings.connectTimeout().toMillis()).isEqualTo(1000);
            assertThat(settings.readTimeout().toMillis()).isEqualTo(3000);
            assertThat(settings.retryAfterSeconds()).isEqualTo(5);
            assertThat(started).hasBean("taxCheckRestClient");
        });
    }

    @Test
    @DisplayName("startup fails, naming the property, when Retry-After is below connect + read rounded up")
    void startupFailsWhenRetryAfterIsTooShort() {
        context.withPropertyValues(
                        "pos.order.tax-check.connect-timeout-ms=1000",
                        "pos.order.tax-check.read-timeout-ms=3001",
                        "pos.order.tax-check.retry-after-seconds=4")
                .run(started -> assertThat(started)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining(TaxCheckSettings.RETRY_AFTER_PROPERTY)
                        .hasMessageContaining("at least 5"));
    }

    @Test
    @DisplayName("Retry-After equal to connect + read in whole seconds starts")
    void equalIsEnough() {
        assertThat(new TaxCheckSettings(java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(3), 4)
                        .retryAfterSeconds())
                .isEqualTo(4);
        assertThatThrownBy(() -> new TaxCheckSettings(java.time.Duration.ZERO, java.time.Duration.ofSeconds(3), 5))
                .isInstanceOf(IllegalStateException.class);
    }
}
