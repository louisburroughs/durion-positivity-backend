package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.catalog.internal.config.CatalogEnrichmentProperties;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.TreadDesignEntity;
import com.positivity.catalog.internal.enums.MatchTier;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TreadDesignMatcher — brand gate and confidence tiers (CAP-324 #1352, #1645)")
class TreadDesignMatcherTest {

    private static final double AUTO = CatalogEnrichmentProperties.DEFAULT_AUTO_THRESHOLD;
    private static final double REVIEW = CatalogEnrichmentProperties.DEFAULT_REVIEW_THRESHOLD;

    private static TreadDesignMatcher matcher() {
        return matcher(new CatalogEnrichmentProperties(null, null, null));
    }

    private static TreadDesignMatcher matcher(CatalogEnrichmentProperties properties) {
        return new TreadDesignMatcher(properties, new BrandNormalizer(properties));
    }

    private static TreadDesignEntity design(String brand, String treadDesign, String treadDesign2, String productName) {
        return TreadDesignEntity.builder()
                .brand(brand)
                .treadDesign(treadDesign)
                .treadDesign2(treadDesign2)
                .productName(productName)
                .build();
    }

    private static ProductEntity product(String manufacturerBrand, String name) {
        ProductEntity product = new ProductEntity();
        product.setManufacturerBrand(manufacturerBrand);
        product.setName(name);
        return product;
    }

    @Nested
    @DisplayName("text scoring")
    class Scoring {

        @Test
        @DisplayName("word-order and punctuation differences still score above the review floor")
        void toleratesWordOrderAndPunctuation() {
            TreadDesignEntity design = design("Michelin", "Pilot Sport 4S", null, null);
            ProductEntity product = product("Michelin", "Michelin Pilot Sport 4 S 245/40R18");

            assertThat(matcher().score(design, product)).isGreaterThanOrEqualTo(REVIEW);
        }

        @Test
        @DisplayName("an unrelated product does not reach the review floor on a shared generic word")
        void doesNotMatchOnAGenericWordAlone() {
            TreadDesignEntity design = design("Michelin", "Pilot Sport 4S", null, null);
            ProductEntity product = product("Continental", "Continental Tire 225/45R17");

            assertThat(matcher().score(design, product)).isLessThan(REVIEW);
        }

        @Test
        @DisplayName("neither side having usable text scores zero rather than throwing")
        void blankTextScoresZero() {
            TreadDesignEntity design = design(null, null, null, null);
            ProductEntity product = product(null, null);

            assertThat(matcher().score(design, product)).isZero();
            assertThat(matcher().evaluate(design, product).tier()).isEqualTo(MatchTier.NONE);
        }
    }

    @Nested
    @DisplayName("tier boundaries")
    class Tiers {

        @Test
        @DisplayName("exactly at the auto threshold is AUTO — the boundary is inclusive")
        void atAutoThresholdIsAuto() {
            assertThat(matcher().tierFor(AUTO)).isEqualTo(MatchTier.AUTO);
        }

        @Test
        @DisplayName("just below the auto threshold is REVIEW, not an attachment")
        void justBelowAutoIsReview() {
            assertThat(matcher().tierFor(AUTO - 0.0001)).isEqualTo(MatchTier.REVIEW);
        }

        @Test
        @DisplayName("exactly at the review floor is REVIEW — #1352's threshold still admits everything it did")
        void atReviewFloorIsReview() {
            assertThat(matcher().tierFor(REVIEW)).isEqualTo(MatchTier.REVIEW);
        }

        @Test
        @DisplayName("just below the review floor is not a candidate at all")
        void justBelowReviewIsNone() {
            assertThat(matcher().tierFor(REVIEW - 0.0001)).isEqualTo(MatchTier.NONE);
        }

        @Test
        @DisplayName("the defaults are 0.80 and 0.50 when a deployment configures nothing")
        void defaultsAreTheAdrValues() {
            assertThat(matcher().autoThreshold()).isEqualTo(0.80);
            assertThat(matcher().reviewThreshold()).isEqualTo(0.50);
        }

        @Test
        @DisplayName("a deployment's configured thresholds are what decide the tier")
        void configuredThresholdsWin() {
            TreadDesignMatcher tuned = matcher(new CatalogEnrichmentProperties(0.95, 0.90, null));

            assertThat(tuned.tierFor(0.92)).isEqualTo(MatchTier.REVIEW);
            assertThat(tuned.tierFor(0.96)).isEqualTo(MatchTier.AUTO);
            assertThat(tuned.tierFor(0.80)).isEqualTo(MatchTier.NONE);
        }
    }

    @Nested
    @DisplayName("the brand gate")
    class BrandGate {

        @Test
        @DisplayName("a brand disagreement is NONE at score zero however similar the text")
        void brandDisagreementEndsTheComparison() {
            // Identical design text on both sides; only the brand differs.
            TreadDesignEntity design = design("Michelin", "Pilot Sport 4S", null, "Pilot Sport 4S");
            ProductEntity product = product("Continental", "Pilot Sport 4S");

            TreadDesignMatcher.ScoredCandidate scored = matcher().evaluate(design, product);

            assertThat(scored.tier()).isEqualTo(MatchTier.NONE);
            assertThat(scored.score()).isZero();
        }

        @Test
        @DisplayName("an absent brand on either side is not agreement")
        void absentBrandIsNotAgreement() {
            TreadDesignEntity design = design(null, "Pilot Sport 4S", null, "Pilot Sport 4S");
            ProductEntity product = product("Michelin", "Michelin Pilot Sport 4S");

            assertThat(matcher().evaluate(design, product).tier()).isEqualTo(MatchTier.NONE);
        }

        @Test
        @DisplayName("a configured alias makes two spellings the same brand")
        void aliasResolvesTheGate() {
            CatalogEnrichmentProperties properties =
                    new CatalogEnrichmentProperties(0.10, 0.05, Map.of("michelinnorthamerica", "Michelin"));
            TreadDesignEntity design = design("Michelin North America, Inc.", "Pilot Sport 4S", null, null);
            ProductEntity product = product("Michelin", "Michelin Pilot Sport 4S 245/40R18");

            assertThat(matcher(properties).evaluate(design, product).tier()).isEqualTo(MatchTier.AUTO);
        }
    }

    @Nested
    @DisplayName("scoring a candidate set")
    class CandidateSets {

        @Test
        @DisplayName("a design may legitimately match several sizes of the same product line")
        void keepsEveryCandidateAboveTheFloor() {
            CatalogEnrichmentProperties properties = new CatalogEnrichmentProperties(0.10, 0.05, null);
            TreadDesignEntity design = design("Michelin", "Pilot Sport 4S", null, null);
            ProductEntity sizeA = product("Michelin", "Michelin Pilot Sport 4S 245/40R18");
            ProductEntity sizeB = product("Michelin", "Michelin Pilot Sport 4S 255/35R19");
            ProductEntity otherBrand = product("Continental", "Continental Pilot Sport 4S");

            List<TreadDesignMatcher.ScoredCandidate> scored =
                    matcher(properties).evaluateCandidates(design, List.of(sizeA, sizeB, otherBrand));

            assertThat(scored)
                    .extracting(TreadDesignMatcher.ScoredCandidate::product)
                    .containsExactlyInAnyOrder(sizeA, sizeB);
        }

        @Test
        @DisplayName("candidates come back best first, so a reviewer reads the strongest suggestion first")
        void ordersByScoreDescending() {
            CatalogEnrichmentProperties properties = new CatalogEnrichmentProperties(0.99, 0.05, null);
            TreadDesignEntity design = design("Michelin", "Pilot Sport 4S", null, null);
            ProductEntity closer = product("Michelin", "Michelin Pilot Sport 4S");
            ProductEntity looser = product("Michelin", "Michelin Pilot Sport 4S 255/35R19 XL Extra Load");

            List<TreadDesignMatcher.ScoredCandidate> scored =
                    matcher(properties).evaluateCandidates(design, List.of(looser, closer));

            assertThat(scored)
                    .extracting(TreadDesignMatcher.ScoredCandidate::product)
                    .containsExactly(closer, looser);
        }

        @Test
        @DisplayName("candidates below the floor are dropped rather than recorded as near misses")
        void dropsNoneTierCandidates() {
            TreadDesignEntity design = design("Michelin", "Pilot Sport 4S", null, null);
            ProductEntity unrelated = product("Michelin", "Michelin X-Ice Snow 205/55R16");

            assertThat(matcher().evaluateCandidates(design, List.of(unrelated))).isEmpty();
        }
    }
}
