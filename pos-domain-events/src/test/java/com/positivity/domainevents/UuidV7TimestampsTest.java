package com.positivity.domainevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.positivity.shared.id.UUIDv7Generator;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7TimestampsTest {

    @Test
    void extractsGenerationInstantFromFreshUuidV7() {
        Instant before = Instant.now().minusSeconds(1);
        UUID id = UUIDv7Generator.generate();
        Instant after = Instant.now().plusSeconds(1);

        Instant embedded = UuidV7Timestamps.instantOf(id);
        assertThat(embedded).isAfterOrEqualTo(before).isBeforeOrEqualTo(after);
    }

    @Test
    void rejectsNonV7Uuids() {
        assertThatIllegalArgumentException().isThrownBy(() -> UuidV7Timestamps.instantOf(UUID.randomUUID()));
    }

    @Test
    void windowMembershipIsStartInclusiveEndExclusive() {
        Instant start = Instant.parse("2026-07-08T10:00:00Z");
        Instant end = Instant.parse("2026-07-08T11:00:00Z");
        UUID atStart = UUID.fromString(UuidV7Timestamps.minStringAt(start).replace("-0000-0000-", "-7000-8000-"));
        UUID atEnd = UUID.fromString(UuidV7Timestamps.minStringAt(end).replace("-0000-0000-", "-7000-8000-"));

        assertThat(UuidV7Timestamps.isInWindow(atStart, start, end)).isTrue();
        assertThat(UuidV7Timestamps.isInWindow(atEnd, start, end)).isFalse();
    }

    @Test
    void minStringBoundsSortAroundRealUuidsOfTheWindow() {
        Instant start = Instant.parse("2026-07-08T10:00:00Z");
        Instant end = Instant.parse("2026-07-08T11:00:00Z");
        String lower = UuidV7Timestamps.minStringAt(start);
        String upper = UuidV7Timestamps.minStringAt(end);

        // A real UUIDv7 generated now (far after the window) sorts above the upper bound...
        String current = UUIDv7Generator.generate().toString();
        assertThat(current).isGreaterThan(upper);
        // ...and a synthetic in-window id sorts between the bounds.
        String inWindow = lower.replace("-0000-0000-", "-7abc-8def-");
        assertThat(inWindow).isGreaterThanOrEqualTo(lower).isLessThan(upper);
        assertThat(UuidV7Timestamps.instantOf(UUID.fromString(inWindow))).isEqualTo(start);
    }
}
