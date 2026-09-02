package com.positivity.referencemock.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChaosServiceTest {

    private final ChaosService chaosService = new ChaosService();

    @Test
    void delayIsCappedAtTenSeconds() {
        assertThat(chaosService.effectiveDelayMs(999_999L)).isEqualTo(ChaosService.MAX_DELAY_MS);
        assertThat(chaosService.effectiveDelayMs(Long.MAX_VALUE)).isEqualTo(ChaosService.MAX_DELAY_MS);
    }

    @Test
    void nullZeroAndNegativeDelaysMeanNoDelay() {
        assertThat(chaosService.effectiveDelayMs(null)).isZero();
        assertThat(chaosService.effectiveDelayMs(0L)).isZero();
        assertThat(chaosService.effectiveDelayMs(-5L)).isZero();
    }

    @Test
    void inRangeDelayPassesThroughUnchanged() {
        assertThat(chaosService.effectiveDelayMs(250L)).isEqualTo(250L);
    }

    @Test
    void failRateExtremesAreDeterministic() {
        assertThat(chaosService.shouldFail(null)).isFalse();
        assertThat(chaosService.shouldFail(0.0)).isFalse();
        assertThat(chaosService.shouldFail(-1.0)).isFalse();
        assertThat(chaosService.shouldFail(1.0)).isTrue();
        assertThat(chaosService.shouldFail(2.0)).isTrue();
    }
}
