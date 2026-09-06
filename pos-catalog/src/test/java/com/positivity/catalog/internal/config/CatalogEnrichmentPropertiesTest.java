package com.positivity.catalog.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CatalogEnrichmentProperties — fail-fast threshold validation (#1645)")
class CatalogEnrichmentPropertiesTest {

    @Nested
    @DisplayName("accepted configurations")
    class Accepted {

        @Test
        @DisplayName("both thresholds null falls back to the ADR-0060 defaults")
        void defaultsAreValid() {
            CatalogEnrichmentProperties properties = new CatalogEnrichmentProperties(null, null, null);
            assertThat(properties.autoThresholdOrDefault())
                    .isEqualTo(CatalogEnrichmentProperties.DEFAULT_AUTO_THRESHOLD);
            assertThat(properties.reviewThresholdOrDefault())
                    .isEqualTo(CatalogEnrichmentProperties.DEFAULT_REVIEW_THRESHOLD);
        }

        @Test
        @DisplayName("an explicit auto-threshold equal to review-threshold is allowed")
        void equalThresholdsAreValid() {
            CatalogEnrichmentProperties properties = new CatalogEnrichmentProperties(0.5, 0.5, null);
            assertThat(properties.autoThresholdOrDefault()).isEqualTo(0.5);
            assertThat(properties.reviewThresholdOrDefault()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("boundary values 0.0 and 1.0 are allowed")
        void boundaryValuesAreValid() {
            CatalogEnrichmentProperties properties = new CatalogEnrichmentProperties(1.0, 0.0, null);
            assertThat(properties.autoThresholdOrDefault()).isEqualTo(1.0);
            assertThat(properties.reviewThresholdOrDefault()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("rejected configurations")
    class Rejected {

        @Test
        @DisplayName("auto-threshold above 1.0 is rejected")
        void autoThresholdAboveOneIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CatalogEnrichmentProperties(1.5, 0.5, null))
                    .withMessageContaining("auto-threshold");
        }

        @Test
        @DisplayName("auto-threshold below 0.0 is rejected")
        void autoThresholdBelowZeroIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CatalogEnrichmentProperties(-0.1, 0.5, null))
                    .withMessageContaining("auto-threshold");
        }

        @Test
        @DisplayName("review-threshold above 1.0 is rejected")
        void reviewThresholdAboveOneIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CatalogEnrichmentProperties(0.9, 1.1, null))
                    .withMessageContaining("review-threshold");
        }

        @Test
        @DisplayName("review-threshold below 0.0 is rejected")
        void reviewThresholdBelowZeroIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CatalogEnrichmentProperties(0.9, -0.1, null))
                    .withMessageContaining("review-threshold");
        }

        @Test
        @DisplayName("an explicit auto-threshold below an explicit review-threshold is rejected")
        void autoBelowReviewIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CatalogEnrichmentProperties(0.4, 0.6, null))
                    .withMessageContaining("auto-threshold")
                    .withMessageContaining("review-threshold");
        }

        @Test
        @DisplayName("overriding only review-threshold above the untouched auto default is rejected")
        void reviewThresholdAboveDefaultAutoIsRejected() {
            // auto-threshold defaults to 0.80 (DEFAULT_AUTO_THRESHOLD); 0.90 alone would silently
            // invert the tiers if this were not validated against the effective value.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CatalogEnrichmentProperties(null, 0.90, null))
                    .withMessageContaining("auto-threshold")
                    .withMessageContaining("review-threshold");
        }

        @Test
        @DisplayName("brand aliases are unaffected by threshold validation")
        void brandAliasesDoNotAffectValidation() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CatalogEnrichmentProperties(0.1, 0.9, Map.of("michelin", "Michelin")));
        }
    }
}
