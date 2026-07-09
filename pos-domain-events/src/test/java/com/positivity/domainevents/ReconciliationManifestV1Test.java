package com.positivity.domainevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReconciliationManifestV1Test {

    private static final Instant START = Instant.parse("2026-07-08T10:00:00Z");
    private static final Instant END = Instant.parse("2026-07-08T11:00:00Z");

    @Test
    void checksumIsOrderIndependentAndDeterministic() {
        List<String> ids = List.of(
                "01980a58-0000-7000-8000-000000000001",
                "01980a58-0000-7000-8000-000000000002",
                "01980a58-0000-7000-8000-000000000003");
        List<String> shuffled = List.of(ids.get(2), ids.get(0), ids.get(1));

        assertThat(ReconciliationManifestV1.checksumOf(ids))
                .isEqualTo(ReconciliationManifestV1.checksumOf(shuffled))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void checksumDistinguishesDifferentIdSets() {
        String a = ReconciliationManifestV1.checksumOf(List.of("01980a58-0000-7000-8000-000000000001"));
        String b = ReconciliationManifestV1.checksumOf(List.of("01980a58-0000-7000-8000-000000000002"));
        String empty = ReconciliationManifestV1.checksumOf(List.of());

        assertThat(a).isNotEqualTo(b).isNotEqualTo(empty);
        // Joining with a separator keeps ["ab","c"] distinct from ["a","bc"].
        assertThat(ReconciliationManifestV1.checksumOf(List.of("ab", "c")))
                .isNotEqualTo(ReconciliationManifestV1.checksumOf(List.of("a", "bc")));
    }

    @Test
    void matchesComparesCountAndChecksum() {
        String checksum = ReconciliationManifestV1.checksumOf(List.of("x"));
        ReconciliationManifestV1 manifest = new ReconciliationManifestV1(START, END, 1, checksum, Map.of("t", 1L));

        assertThat(manifest.matches(1, checksum)).isTrue();
        assertThat(manifest.matches(2, checksum)).isFalse();
        assertThat(manifest.matches(1, "0".repeat(64))).isFalse();
    }

    @Test
    void eventTypeForBuildsDottedType() {
        assertThat(ReconciliationManifestV1.eventTypeFor("workorder")).isEqualTo("workorder.reconciliation.manifest");
    }

    @Test
    void rejectsInvalidWindowsAndValues() {
        String checksum = ReconciliationManifestV1.checksumOf(List.of());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReconciliationManifestV1(END, START, 0, checksum, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReconciliationManifestV1(START, START, 0, checksum, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReconciliationManifestV1(START, END, -1, checksum, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReconciliationManifestV1(START, END, 0, " ", null));
    }
}
