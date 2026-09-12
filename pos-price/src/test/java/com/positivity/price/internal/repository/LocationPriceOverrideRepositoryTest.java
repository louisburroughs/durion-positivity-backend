package com.positivity.price.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.price.PostgresSliceTestBase;
import com.positivity.price.internal.entity.LocationPriceOverride;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The location-override overlap guard ({@link
 * LocationPriceOverrideRepository#existsOverlappingEffectiveWindow}) against the real PostgreSQL
 * schema.
 *
 * <p>This is a database test rather than a mocked one because what broke this query was a property
 * of the database. It was one JPQL string of {@code (:param IS NULL OR …)} clauses, and one of
 * those placeholders was an {@link Instant}: PostgreSQL then rejected the statement at parse time,
 * for every call, with {@code could not determine data type of parameter $n} (issue #1891). The
 * H2-backed test of the same query stayed green throughout. See {@link EffectiveWindowOverlapSearch}
 * for why the placeholder is unresolvable and why the filter is a specification now.
 */
@DisplayName("Location price override overlap window guard on PostgreSQL")
class LocationPriceOverrideRepositoryTest extends PostgresSliceTestBase {

    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FEB = Instant.parse("2026-02-01T00:00:00Z");
    private static final Instant MAR = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant APR = Instant.parse("2026-04-01T00:00:00Z");

    private final UUID productId = UUID.randomUUID();
    private final UUID locationId = UUID.randomUUID();

    @Autowired
    private LocationPriceOverrideRepository overrides;

    private LocationPriceOverride override(Instant effectiveFrom, Instant effectiveTo) {
        LocationPriceOverride override = new LocationPriceOverride();
        override.setProductId(productId);
        override.setLocationId(locationId);
        override.setOverridePrice(new BigDecimal("95.0000"));
        override.setCurrency("USD");
        override.setEffectiveFrom(effectiveFrom);
        override.setEffectiveTo(effectiveTo);
        return overrides.saveAndFlush(override);
    }

    @Test
    @DisplayName("a window overlapping an existing override is detected")
    void overlappingWindowIsDetected() {
        override(JAN, MAR);

        assertThat(overrides.existsOverlappingEffectiveWindow(productId, locationId, FEB, APR))
                .isTrue();
    }

    @Test
    @DisplayName("the interval is half-open, so a window starting where one ends does not overlap")
    void adjacentWindowDoesNotOverlap() {
        override(JAN, FEB);

        assertThat(overrides.existsOverlappingEffectiveWindow(productId, locationId, FEB, MAR))
                .isFalse();
    }

    @Test
    @DisplayName("an open-ended candidate window overlaps everything from its start onwards")
    void openEndedCandidateWindowOverlapsLaterOverrides() {
        override(MAR, APR);

        // A null effectiveTo is the case the (:effectiveTo IS NULL OR …) clause existed to serve,
        // and the case whose untyped placeholder made PostgreSQL reject the whole statement.
        assertThat(overrides.existsOverlappingEffectiveWindow(productId, locationId, JAN, null))
                .isTrue();
    }

    @Test
    @DisplayName("an open-ended stored override is overlapped by any later window")
    void openEndedStoredOverrideIsOverlapped() {
        override(JAN, null);

        assertThat(overrides.existsOverlappingEffectiveWindow(productId, locationId, MAR, APR))
                .isTrue();
    }

    @Test
    @DisplayName("both windows open-ended still overlap")
    void bothWindowsOpenEnded() {
        override(JAN, null);

        assertThat(overrides.existsOverlappingEffectiveWindow(productId, locationId, MAR, null))
                .isTrue();
    }

    @Test
    @DisplayName("the override being edited is excluded from its own overlap check")
    void excludedOverrideDoesNotOverlapItself() {
        LocationPriceOverride existing = override(JAN, MAR);

        assertThat(overrides.existsOverlappingEffectiveWindow(productId, locationId, JAN, MAR, existing.getId()))
                .isFalse();
        assertThat(overrides.existsOverlappingEffectiveWindow(productId, locationId, JAN, MAR, null))
                .isTrue();
    }

    @Test
    @DisplayName("another product or location never overlaps")
    void otherProductsDoNotOverlap() {
        override(JAN, MAR);

        assertThat(overrides.existsOverlappingEffectiveWindow(UUID.randomUUID(), locationId, JAN, MAR))
                .isFalse();
        assertThat(overrides.existsOverlappingEffectiveWindow(productId, UUID.randomUUID(), JAN, MAR))
                .isFalse();
    }
}
