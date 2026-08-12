package com.positivity.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for {@link EventTypeRegistration} and its four threshold presets.
 *
 * <p>
 * Every module's event-type registry is built from these four factory methods,
 * so the latency thresholds they stamp on a registration are the repo-wide
 * definition of "how fast is this operation supposed to be". A preset that
 * silently changed value, or that stopped applying its thresholds, would
 * re-baseline the SLO of every event in every module at once.
 */
@DisplayName("EventTypeRegistration — threshold presets and defaults")
class EventTypeRegistrationTest {

    private static Stream<Arguments> presets() {
        return Stream.of(
                Arguments.of(
                        "fastRead",
                        EventTypeRegistration.fastRead("X_FAST", "fast").build(),
                        EventTypeRegistration.FAST_READ_P50,
                        EventTypeRegistration.FAST_READ_P95,
                        EventTypeRegistration.FAST_READ_P99),
                Arguments.of(
                        "search",
                        EventTypeRegistration.search("X_SEARCH", "search").build(),
                        EventTypeRegistration.SEARCH_P50,
                        EventTypeRegistration.SEARCH_P95,
                        EventTypeRegistration.SEARCH_P99),
                Arguments.of(
                        "write",
                        EventTypeRegistration.write("X_WRITE", "write").build(),
                        EventTypeRegistration.WRITE_P50,
                        EventTypeRegistration.WRITE_P95,
                        EventTypeRegistration.WRITE_P99),
                Arguments.of(
                        "approval",
                        EventTypeRegistration.approval("X_APPROVAL", "approval").build(),
                        EventTypeRegistration.APPROVAL_P50,
                        EventTypeRegistration.APPROVAL_P95,
                        EventTypeRegistration.APPROVAL_P99));
    }

    @ParameterizedTest(name = "{0}() stamps its declared thresholds")
    @MethodSource("presets")
    void preset_stampsItsThresholds(
            String presetName, EventTypeRegistration registration, long p50, long p95, long p99) {
        assertThat(registration.getP50Micros()).isEqualTo(p50);
        assertThat(registration.getP95Micros()).isEqualTo(p95);
        assertThat(registration.getP99Micros()).isEqualTo(p99);
    }

    @ParameterizedTest(name = "{0}() carries the supplied type code and description")
    @MethodSource("presets")
    void preset_carriesTypeCodeAndDescription(
            String presetName, EventTypeRegistration registration, long p50, long p95, long p99) {
        assertThat(registration.getTypeCode()).isNotBlank();
        assertThat(registration.getDescription()).isNotBlank();
    }

    @ParameterizedTest(name = "{0}() defaults to apiVersion 1 and active")
    @MethodSource("presets")
    void preset_appliesBuilderDefaults(
            String presetName, EventTypeRegistration registration, long p50, long p95, long p99) {
        assertThat(registration.getApiVersion()).isEqualTo("1");
        assertThat(registration.isActive()).isTrue();
    }

    @ParameterizedTest(name = "{0}() thresholds are strictly increasing")
    @MethodSource("presets")
    void preset_thresholdsAreStrictlyIncreasing(
            String presetName, EventTypeRegistration registration, long p50, long p95, long p99) {
        assertThat(p50).isPositive().isLessThan(p95);
        assertThat(p95).isLessThan(p99);
    }

    @Test
    @DisplayName("the four presets are mutually distinct — no two share a threshold triple")
    void presets_areMutuallyDistinct() {
        assertThat(presets()
                        .map(args -> (EventTypeRegistration) args.get()[1])
                        .map(r -> r.getP50Micros() + ":" + r.getP95Micros() + ":" + r.getP99Micros())
                        .toList())
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("presets are ordered fastRead < search < write < approval at every percentile")
    void presets_areOrderedByCost() {
        assertThat(EventTypeRegistration.FAST_READ_P50).isLessThan(EventTypeRegistration.SEARCH_P50);
        assertThat(EventTypeRegistration.SEARCH_P50).isLessThan(EventTypeRegistration.WRITE_P50);
        assertThat(EventTypeRegistration.WRITE_P50).isLessThan(EventTypeRegistration.APPROVAL_P50);

        assertThat(EventTypeRegistration.FAST_READ_P95).isLessThan(EventTypeRegistration.SEARCH_P95);
        assertThat(EventTypeRegistration.SEARCH_P95).isLessThan(EventTypeRegistration.WRITE_P95);
        assertThat(EventTypeRegistration.WRITE_P95).isLessThan(EventTypeRegistration.APPROVAL_P95);

        assertThat(EventTypeRegistration.FAST_READ_P99).isLessThan(EventTypeRegistration.SEARCH_P99);
        assertThat(EventTypeRegistration.SEARCH_P99).isLessThan(EventTypeRegistration.WRITE_P99);
        assertThat(EventTypeRegistration.WRITE_P99).isLessThan(EventTypeRegistration.APPROVAL_P99);
    }

    @Test
    @DisplayName("a preset builder can be overridden before build()")
    void preset_builderCanBeOverridden() {
        EventTypeRegistration overridden = EventTypeRegistration.write("X_WRITE", "write")
                .apiVersion("2")
                .active(false)
                .p99Micros(9_000_000L)
                .build();

        assertThat(overridden.getApiVersion()).isEqualTo("2");
        assertThat(overridden.isActive()).isFalse();
        assertThat(overridden.getP99Micros()).isEqualTo(9_000_000L);
        // Untouched thresholds keep the preset's values.
        assertThat(overridden.getP50Micros()).isEqualTo(EventTypeRegistration.WRITE_P50);
    }
}
