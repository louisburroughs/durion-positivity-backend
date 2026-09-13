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
