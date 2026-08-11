package com.positivity.documents.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.events.EventTypeRegistration;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the {@link DocumentEventTypes} event-type registry.
 *
 * <p>
 * Every module that emits events via {@code @EmitEvent} must publish a registry
 * of the event ids it emits, each carrying one of the four documented latency
 * threshold presets (fastRead, search, write, approval). {@code pos-event-receiver}
 * upserts these by type code at startup, so a duplicate or malformed id silently
 * overwrites another module's registration rather than failing loudly.
 *
 * <p>
 * These tests lock that contract in: unique ids, canonical id format, non-blank
 * descriptions, ordered thresholds, and a threshold triple that matches a
 * declared preset rather than an ad-hoc value.
 */
@DisplayName("DocumentEventTypes — event-type registry contract")
class DocumentEventTypesTest {

    /**
     * Event type codes are upper snake case: the receiver stores them verbatim as
     * the natural key and clients match on them literally.
     */
    private static final Pattern TYPE_CODE = Pattern.compile("^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$");

    /**
     * The four latency presets declared by {@link EventTypeRegistration}, as
     * {@code p50:p95:p99} triples. A registration whose thresholds match none of
     * these was built with hand-rolled values instead of a preset.
     */
    private static final Set<String> PRESET_THRESHOLDS = Set.of(
            triple(
                    EventTypeRegistration.FAST_READ_P50,
                    EventTypeRegistration.FAST_READ_P95,
                    EventTypeRegistration.FAST_READ_P99),
            triple(
                    EventTypeRegistration.SEARCH_P50,
                    EventTypeRegistration.SEARCH_P95,
                    EventTypeRegistration.SEARCH_P99),
            triple(EventTypeRegistration.WRITE_P50, EventTypeRegistration.WRITE_P95, EventTypeRegistration.WRITE_P99),
            triple(
                    EventTypeRegistration.APPROVAL_P50,
                    EventTypeRegistration.APPROVAL_P95,
                    EventTypeRegistration.APPROVAL_P99));

    private static String triple(long p50, long p95, long p99) {
        return p50 + ":" + p95 + ":" + p99;
    }

    private static List<EventTypeRegistration> registrations() {
        return DocumentEventTypes.all();
    }

    @Test
    @DisplayName("registry is non-empty")
    void registry_isNotEmpty() {
        assertThat(registrations()).isNotEmpty();
    }

    @Test
    @DisplayName("type codes are unique — a duplicate would overwrite a sibling registration on upsert")
    void typeCodes_areUnique() {
        assertThat(registrations())
                .extracting(EventTypeRegistration::getTypeCode)
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("type codes are UPPER_SNAKE_CASE")
    void typeCodes_matchCanonicalFormat() {
        assertThat(registrations())
                .allSatisfy(registration -> assertThat(registration.getTypeCode())
                        .as("event type code %s", registration.getTypeCode())
                        .matches(TYPE_CODE));
    }

    @Test
    @DisplayName("every registration carries a non-blank description")
    void descriptions_areNotBlank() {
        assertThat(registrations())
                .allSatisfy(registration -> assertThat(registration.getDescription())
                        .as("description for %s", registration.getTypeCode())
                        .isNotBlank());
    }

    @Test
    @DisplayName("thresholds are strictly increasing p50 < p95 < p99")
    void thresholds_areStrictlyIncreasing() {
        assertThat(registrations()).allSatisfy(registration -> {
            assertThat(registration.getP50Micros())
                    .as("p50 for %s", registration.getTypeCode())
                    .isPositive()
                    .isLessThan(registration.getP95Micros());
            assertThat(registration.getP95Micros())
                    .as("p95 for %s", registration.getTypeCode())
                    .isLessThan(registration.getP99Micros());
        });
    }

    @Test
    @DisplayName("every registration uses one of the four declared threshold presets")
    void thresholds_matchADeclaredPreset() {
        assertThat(registrations())
                .allSatisfy(registration -> assertThat(triple(
                                registration.getP50Micros(), registration.getP95Micros(), registration.getP99Micros()))
                        .as("threshold preset for %s", registration.getTypeCode())
                        .isIn(PRESET_THRESHOLDS));
    }
}
