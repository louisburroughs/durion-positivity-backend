package com.positivity.extractionworker.internal.config;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class ProcessHardeningTest {

    @Test
    void hardeningIsBestEffortAndNeverThrows() {
        // True on Linux; false (and logged at INFO) elsewhere. Either way start-up goes on.
        assertThatCode(ProcessHardening::disableDumpable).doesNotThrowAnyException();
        assertThatCode(() -> new ProcessHardening().afterPropertiesSet()).doesNotThrowAnyException();
    }
}
