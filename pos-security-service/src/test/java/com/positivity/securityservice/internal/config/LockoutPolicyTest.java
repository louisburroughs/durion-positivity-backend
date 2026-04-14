package com.positivity.securityservice.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LockoutPolicy} record compact constructor validation.
 *
 * <p>Verifies that the configuration record enforces its invariants at
 * construction time and that accessor methods return the configured values.
 *
 * @since AUTH-003
 */
@DisplayName("LockoutPolicyTest — AUTH-003 compact constructor validation")
class LockoutPolicyTest {

    private static final Duration VALID_WINDOW = Duration.ofMinutes(10);
    private static final Duration VALID_MAX_BACKOFF = Duration.ofMinutes(30);

    @Nested
    @DisplayName("Valid construction")
    class ValidConstruction {

        @Test
        @DisplayName("T_LP1 — valid parameters produce record with correct accessors")
        void t_lp1_validParamsProduceRecord() {
            LockoutPolicy policy = new LockoutPolicy(5, VALID_WINDOW, 2, VALID_MAX_BACKOFF);

            assertThat(policy.maxAttempts()).isEqualTo(5);
            assertThat(policy.window()).isEqualTo(VALID_WINDOW);
            assertThat(policy.backoffMultiplier()).isEqualTo(2);
            assertThat(policy.maxBackoffWindow()).isEqualTo(VALID_MAX_BACKOFF);
        }

        @Test
        @DisplayName("T_LP2 — minimum valid maxAttempts (1) is accepted")
        void t_lp2_minimumMaxAttemptsIsAccepted() {
            LockoutPolicy policy = new LockoutPolicy(1, VALID_WINDOW, 1, VALID_MAX_BACKOFF);
            assertThat(policy.maxAttempts()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("maxAttempts validation")
    class MaxAttemptsValidation {

        @Test
        @DisplayName("T_LP3 — maxAttempts=0 throws IllegalArgumentException")
        void t_lp3_maxAttemptsZeroThrows() {
            assertThatThrownBy(() -> new LockoutPolicy(0, VALID_WINDOW, 2, VALID_MAX_BACKOFF))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxAttempts must be >= 1");
        }

        @Test
        @DisplayName("T_LP4 — maxAttempts=-1 throws IllegalArgumentException")
        void t_lp4_maxAttemptsNegativeThrows() {
            assertThatThrownBy(() -> new LockoutPolicy(-1, VALID_WINDOW, 2, VALID_MAX_BACKOFF))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxAttempts must be >= 1");
        }
    }

    @Nested
    @DisplayName("window validation")
    class WindowValidation {

        @Test
        @DisplayName("T_LP5 — null window throws IllegalArgumentException")
        void t_lp5_nullWindowThrows() {
            assertThatThrownBy(() -> new LockoutPolicy(5, null, 2, VALID_MAX_BACKOFF))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("window must be positive");
        }

        @Test
        @DisplayName("T_LP6 — zero window throws IllegalArgumentException")
        void t_lp6_zeroWindowThrows() {
            assertThatThrownBy(() -> new LockoutPolicy(5, Duration.ZERO, 2, VALID_MAX_BACKOFF))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("window must be positive");
        }

        @Test
        @DisplayName("T_LP7 — negative window throws IllegalArgumentException")
        void t_lp7_negativeWindowThrows() {
            assertThatThrownBy(() -> new LockoutPolicy(5, Duration.ofMinutes(-1), 2, VALID_MAX_BACKOFF))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("window must be positive");
        }
    }

    @Nested
    @DisplayName("backoffMultiplier validation")
    class BackoffMultiplierValidation {

        @Test
        @DisplayName("T_LP8 — backoffMultiplier=0 throws IllegalArgumentException")
        void t_lp8_backoffMultiplierZeroThrows() {
            assertThatThrownBy(() -> new LockoutPolicy(5, VALID_WINDOW, 0, VALID_MAX_BACKOFF))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("backoffMultiplier must be >= 1");
        }

        @Test
        @DisplayName("T_LP9 — backoffMultiplier=-2 throws IllegalArgumentException")
        void t_lp9_backoffMultiplierNegativeThrows() {
            assertThatThrownBy(() -> new LockoutPolicy(5, VALID_WINDOW, -2, VALID_MAX_BACKOFF))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("backoffMultiplier must be >= 1");
        }
    }

    @Nested
    @DisplayName("maxBackoffWindow validation")
    class MaxBackoffWindowValidation {

        @Test
        @DisplayName("T_LP10 — null maxBackoffWindow throws IllegalArgumentException")
        void t_lp10_nullMaxBackoffWindowThrows() {
            assertThatThrownBy(() -> new LockoutPolicy(5, VALID_WINDOW, 2, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxBackoffWindow must be positive");
        }

        @Test
        @DisplayName("T_LP11 — zero maxBackoffWindow throws IllegalArgumentException")
        void t_lp11_zeroMaxBackoffWindowThrows() {
            assertThatThrownBy(() -> new LockoutPolicy(5, VALID_WINDOW, 2, Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxBackoffWindow must be positive");
        }

        @Test
        @DisplayName("T_LP12 — negative maxBackoffWindow throws IllegalArgumentException")
        void t_lp12_negativeMaxBackoffWindowThrows() {
            assertThatThrownBy(() -> new LockoutPolicy(5, VALID_WINDOW, 2, Duration.ofMinutes(-5)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxBackoffWindow must be positive");
        }
    }
}
