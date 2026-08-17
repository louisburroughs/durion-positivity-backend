package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.TreadDesignEntity;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TreadDesignMatcher — token-overlap scoring (CAP-324 #1352)")
class TreadDesignMatcherTest {

    private final TreadDesignMatcher matcher = new TreadDesignMatcher();

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

    @Test
    @DisplayName("word-order and punctuation differences still match")
    void toleratesWordOrderAndPunctuation() {
        TreadDesignEntity design = design("Michelin", "Pilot Sport 4S", null, null);
        ProductEntity product = product("Michelin", "Michelin Pilot Sport 4 S 245/40R18");

        assertThat(matcher.matches(design, product)).isTrue();
    }

    @Test
    @DisplayName("an unrelated product does not match on a shared generic word alone")
    void doesNotMatchOnAGenericWordAlone() {
        TreadDesignEntity design = design("Michelin", "Pilot Sport 4S", null, null);
        ProductEntity product = product("Continental", "Continental Tire 225/45R17");

        assertThat(matcher.matches(design, product)).isFalse();
    }

    @Test
    @DisplayName("neither side having usable text scores zero rather than throwing")
    void blankTextScoresZero() {
        TreadDesignEntity design = design(null, null, null, null);
        ProductEntity product = product(null, null);

        assertThat(matcher.score(design, product)).isZero();
        assertThat(matcher.matches(design, product)).isFalse();
    }

    @Test
    @DisplayName("a design may legitimately match several sizes of the same product line")
    void matchingProductsReturnsEveryCandidateAboveThreshold() {
        TreadDesignEntity design = design("Michelin", "Pilot Sport 4S", null, null);
        ProductEntity sizeA = product("Michelin", "Michelin Pilot Sport 4S 245/40R18");
        ProductEntity sizeB = product("Michelin", "Michelin Pilot Sport 4S 255/35R19");
        ProductEntity unrelated = product("Continental", "Continental ExtremeContact");

        List<ProductEntity> matches = matcher.matchingProducts(design, List.of(sizeA, sizeB, unrelated));

        assertThat(matches).containsExactlyInAnyOrder(sizeA, sizeB);
    }
}
