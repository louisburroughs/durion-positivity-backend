package com.positivity.tenant.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.tenant.internal.exception.DuplicateResourceException;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The normalization the display-name uniqueness constraint is built on, and the seed a registration
 * without a display name falls back to.
 */
class TenantDisplayNameAllocatorTest {

    private static final Predicate<String> NOTHING_TAKEN = key -> false;

    @Nested
    @DisplayName("normalize")
    class Normalize {

        @Test
        @DisplayName("case-folds, trims and collapses internal whitespace")
        void collapsesAndFolds() {
            assertThat(TenantDisplayNameAllocator.normalize("Acme  Tire & Auto "))
                    .isEqualTo("acme tire & auto");
            assertThat(TenantDisplayNameAllocator.normalize("acme tire & auto")).isEqualTo("acme tire & auto");
            assertThat(TenantDisplayNameAllocator.normalize("ACME\tTire\nAuto")).isEqualTo("acme tire auto");
        }

        @Test
        @DisplayName("case folding cannot push the key past the column")
        void caseFoldingStaysInsideTheColumn() {
            // U+0130 lowercases to two characters, so 200 of them fold to 400. The key column is
            // varchar(200) like the name, so the fold has to be bounded too.
            assertThat(TenantDisplayNameAllocator.normalize("\u0130".repeat(200)))
                    .hasSizeLessThanOrEqualTo(TenantDisplayNameAllocator.MAX_LENGTH);
        }

        @Test
        @DisplayName("applies NFKC, so compatibility forms are one name")
        void appliesNfkc() {
            // U+FF21.. are the fullwidth Latin letters; NFKC folds them to ASCII.
            assertThat(TenantDisplayNameAllocator.normalize("ＡＣＭＥ")).isEqualTo("acme");
            // U+00C5 (composed) and U+0041 U+030A (decomposed) must agree.
            assertThat(TenantDisplayNameAllocator.normalize("Ångstrom"))
                    .isEqualTo(TenantDisplayNameAllocator.normalize("Ångstrom"));
        }
    }

    @Nested
    @DisplayName("displayForm")
    class DisplayForm {

        @Test
        @DisplayName("keeps the operator's casing but tidies whitespace")
        void keepsCasing() {
            assertThat(TenantDisplayNameAllocator.displayForm("  Acme   Tire & Auto  "))
                    .isEqualTo("Acme Tire & Auto");
        }

        @Test
        @DisplayName("truncates to the column length")
        void truncates() {
            String long_ = "A".repeat(250);
            assertThat(TenantDisplayNameAllocator.displayForm(long_)).hasSize(TenantDisplayNameAllocator.MAX_LENGTH);
        }

        @Test
        @DisplayName("NFKC expansion cannot push the display form past the column")
        void nfkcExpansionStaysInsideTheColumn() {
            // U+FB03 is the ffi ligature: NFKC turns each one into three characters, so 200 of
            // them become 600. Unbounded, that overflows varchar(200) and fails the write.
            assertThat(TenantDisplayNameAllocator.displayForm("\uFB03".repeat(200)))
                    .hasSize(TenantDisplayNameAllocator.MAX_LENGTH);
        }

        @Test
        @DisplayName("never cuts a surrogate pair in half")
        void keepsSurrogatePairsIntact() {
            // U+1F3E2 (office building) is two chars; cutting at 200 would land mid-pair.
            String emoji = "\uD83C\uDFE2".repeat(150);

            String form = TenantDisplayNameAllocator.displayForm(emoji);

            assertThat(form.length()).isLessThanOrEqualTo(TenantDisplayNameAllocator.MAX_LENGTH);
            assertThat(Character.isHighSurrogate(form.charAt(form.length() - 1)))
                    .as("a lone high surrogate is not valid text and Postgres rejects it")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("allocate")
    class Allocate {

        @Test
        @DisplayName("an account's first tenant carries the bare legal name, with no suffix")
        void firstTenantIsUnsuffixed() {
            assertThat(TenantDisplayNameAllocator.allocate("Acme Tire & Auto LLC", NOTHING_TAKEN))
                    .isEqualTo("Acme Tire & Auto LLC");
        }

        @Test
        @DisplayName("the second tenant under one account is suffixed #2")
        void secondTenantGetsTwo() {
            Predicate<String> taken = Set.of("acme tire & auto llc")::contains;

            assertThat(TenantDisplayNameAllocator.allocate("Acme Tire & Auto LLC", taken))
                    .isEqualTo("Acme Tire & Auto LLC #2");
        }

        @Test
        @DisplayName("a taken suffix is skipped rather than reused")
        void skipsTakenSuffixes() {
            Predicate<String> taken = Set.of("acme tire & auto llc", "acme tire & auto llc #2")::contains;

            assertThat(TenantDisplayNameAllocator.allocate("Acme Tire & Auto LLC", taken))
                    .isEqualTo("Acme Tire & Auto LLC #3");
        }

        @Test
        @DisplayName("collision is judged on the normalized key, not the raw name")
        void comparesNormalized() {
            Predicate<String> taken = Set.of("acme tire & auto llc")::contains;

            assertThat(TenantDisplayNameAllocator.allocate("  ACME   Tire & Auto LLC ", taken))
                    .isEqualTo("ACME Tire & Auto LLC #2");
        }

        @Test
        @DisplayName("a suffixed name still fits the column")
        void suffixedNameFitsTheColumn() {
            String legalName = "A".repeat(250);
            // Only the unsuffixed name is taken, so the allocator must suffix a name already at
            // the column limit and trim it back to fit.
            Predicate<String> taken = "a".repeat(TenantDisplayNameAllocator.MAX_LENGTH)::equals;

            String allocated = TenantDisplayNameAllocator.allocate(legalName, taken);

            assertThat(allocated).hasSizeLessThanOrEqualTo(TenantDisplayNameAllocator.MAX_LENGTH);
            assertThat(allocated).endsWith(" #2");
        }

        @Test
        @DisplayName("gives up rather than looping when every suffix is taken")
        void givesUpAfterTheCeiling() {
            assertThatThrownBy(() -> TenantDisplayNameAllocator.allocate("Acme", key -> true))
                    .isInstanceOf(DuplicateResourceException.class)
                    .hasMessageContaining("Acme");
        }
    }
}
