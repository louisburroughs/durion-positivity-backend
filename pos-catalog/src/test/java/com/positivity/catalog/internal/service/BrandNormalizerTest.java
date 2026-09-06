package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.catalog.internal.config.CatalogEnrichmentProperties;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("BrandNormalizer — normalisation and alias resolution (#1645)")
class BrandNormalizerTest {

    private static BrandNormalizer normalizer() {
        return normalizer(Map.of());
    }

    private static BrandNormalizer normalizer(Map<String, String> aliases) {
        return new BrandNormalizer(new CatalogEnrichmentProperties(null, null, aliases));
    }

    @Nested
    @DisplayName("normalisation")
    class Normalisation {

        @Test
        @DisplayName("case, spacing and punctuation are not differences between brands")
        void ignoresCaseSpacingAndPunctuation() {
            assertThat(normalizer().normalize("  MICHELIN  ")).isEqualTo("michelin");
            assertThat(normalizer().normalize("Bridge-stone")).isEqualTo("bridgestone");
            assertThat(normalizer().normalize("Good/Year")).isEqualTo("goodyear");
        }

        @Test
        @DisplayName("a trailing legal suffix is not part of the brand")
        void stripsLegalSuffixes() {
            assertThat(normalizer().normalize("Michelin Inc.")).isEqualTo("michelin");
            assertThat(normalizer().normalize("Michelin, Inc")).isEqualTo("michelin");
            assertThat(normalizer().normalize("Pirelli GmbH")).isEqualTo("pirelli");
            assertThat(normalizer().normalize("Nokian Oyj Co., Ltd.")).isEqualTo("nokianoyj");
        }

        @Test
        @DisplayName("a suffix that is only part of a word is left alone")
        void doesNotStripInsideAWord() {
            // "Continental" ends in no whole-word suffix, and "AG" here is the brand's first word.
            assertThat(normalizer().normalize("Continental")).isEqualTo("continental");
            assertThat(normalizer().normalize("AG Tyres")).isEqualTo("agtyres");
        }

        @Test
        @DisplayName("a brand that is nothing but a legal suffix keeps it rather than normalising to nothing")
        void doesNotStripTheLastWord() {
            assertThat(normalizer().normalize("Group")).isEqualTo("group");
        }

        @Test
        @DisplayName("no letters or digits is no brand")
        void blankIsNull() {
            assertThat(normalizer().normalize(null)).isNull();
            assertThat(normalizer().normalize("   ")).isNull();
            assertThat(normalizer().normalize("---")).isNull();
        }
    }

    @Nested
    @DisplayName("alias resolution")
    class Aliases {

        @Test
        @DisplayName("an alias is matched on the normalised spelling, not the raw one")
        void resolvesOnTheNormalisedKey() {
            BrandNormalizer withAlias = normalizer(Map.of("michelinnorthamerica", "Michelin"));

            assertThat(withAlias.normalize("Michelin North America")).isEqualTo("michelin");
            assertThat(withAlias.normalize("MICHELIN NORTH AMERICA, INC.")).isEqualTo("michelin");
        }

        @Test
        @DisplayName("the canonical value is normalised too, so it may be written naturally")
        void normalisesTheCanonicalValue() {
            // The key is the alias as normalisation leaves it: "Conti Group" has its legal suffix
            // stripped before the lookup, so the map is keyed on "conti".
            BrandNormalizer withAlias = normalizer(Map.of("conti", "Continental AG"));

            assertThat(withAlias.normalize("Conti Group")).isEqualTo("continental");
        }

        @Test
        @DisplayName("aliases are resolved once, not chained — a configuration cycle cannot hang the consumer")
        void doesNotChainAliases() {
            BrandNormalizer chained = normalizer(Map.of("a", "B", "b", "C"));

            assertThat(chained.normalize("A")).isEqualTo("b");
        }
    }

    @Nested
    @DisplayName("brand agreement")
    class Agreement {

        @Test
        @DisplayName("two spellings of one brand agree")
        void spellingsAgree() {
            assertThat(normalizer().sameBrand("Michelin", "michelin,  Inc.")).isTrue();
        }

        @Test
        @DisplayName("two different brands do not")
        void differentBrandsDisagree() {
            assertThat(normalizer().sameBrand("Michelin", "Continental")).isFalse();
        }

        @Test
        @DisplayName("an absent brand agrees with nothing, including another absent brand")
        void absentBrandsDoNotAgree() {
            assertThat(normalizer().sameBrand(null, "Michelin")).isFalse();
            assertThat(normalizer().sameBrand("Michelin", null)).isFalse();
            assertThat(normalizer().sameBrand(null, null)).isFalse();
        }

        @Test
        @DisplayName("an alias makes a vendor's spelling agree with the catalogue's")
        void aliasesMakeBrandsAgree() {
            BrandNormalizer withAlias = normalizer(Map.of("michelinnorthamerica", "Michelin"));

            assertThat(withAlias.sameBrand("Michelin North America, Inc.", "Michelin"))
                    .isTrue();
        }
    }
}
