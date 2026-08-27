package com.positivity.domainevents.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract of the {@code catalog.product.updated} fact, focused on the subcategory pair added
 * additively within schema v2 (#1514, ADR-0044 §3) — the same shape the {@code productCode} pair
 * was added in.
 */
class ProductUpdatedV1Test {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    private static final UUID PRODUCT_ID = UUID.fromString("01980a58-0000-7000-8000-000000000101");
    private static final UUID CATEGORY_ID = UUID.fromString("01980a58-0000-7000-8000-000000000102");
    private static final UUID SUBCATEGORY_ID = UUID.fromString("01980a58-0000-7000-8000-000000000103");

    private static ProductUpdatedV1 fact(UUID subcategoryId, String subcategory) {
        return new ProductUpdatedV1(
                PRODUCT_ID,
                "BAT-24F",
                "Battery 24F",
                null,
                null,
                null,
                CATEGORY_ID,
                "Electrical System",
                null,
                null,
                true,
                null,
                null,
                "EA",
                "NONE",
                null,
                null,
                null,
                null,
                null,
                subcategoryId,
                subcategory);
    }

    @Test
    @DisplayName("the subcategory pair survives a JSON round trip under its contract names")
    void roundTripPreservesSubcategory() {
        ProductUpdatedV1 evt = fact(SUBCATEGORY_ID, "Batteries");

        String json = MAPPER.writeValueAsString(evt);
        JsonNode node = MAPPER.readTree(json);
        ProductUpdatedV1 back = MAPPER.readValue(json, ProductUpdatedV1.class);

        assertThat(node.path("subcategoryId").stringValue(null)).isEqualTo(SUBCATEGORY_ID.toString());
        assertThat(node.path("subcategory").stringValue(null)).isEqualTo("Batteries");
        assertThat(back).isEqualTo(evt);
        assertThat(back.subcategoryId()).isEqualTo(SUBCATEGORY_ID);
        assertThat(back.subcategory()).isEqualTo("Batteries");
        // The category pair it sits beside is untouched.
        assertThat(back.categoryId()).isEqualTo(CATEGORY_ID);
        assertThat(back.category()).isEqualTo("Electrical System");
    }

    @Test
    @DisplayName("the addition is additive within v2: the schema version does not move")
    void schemaVersionUnchanged() {
        assertThat(ProductUpdatedV1.EVENT_TYPE).isEqualTo("catalog.product.updated");
        assertThat(ProductUpdatedV1.SCHEMA_VERSION).isEqualTo(2);
    }

    @Test
    @DisplayName("a fact predating the field deserializes with a null subcategory pair")
    void absentSubcategoryIsNull() {
        String json = """
                {"productId":"%s","sku":"BAT-24F","active":true,
                 "categoryId":"%s","category":"Electrical System"}
                """.formatted(PRODUCT_ID, CATEGORY_ID);

        ProductUpdatedV1 back = MAPPER.readValue(json, ProductUpdatedV1.class);

        assertThat(back.subcategoryId()).isNull();
        assertThat(back.subcategory()).isNull();
        assertThat(back.categoryId()).isEqualTo(CATEGORY_ID);
    }

    @Test
    @DisplayName("a subcategory-less product is a legal fact, not a validation failure")
    void subcategoryIsOptional() {
        ProductUpdatedV1 evt = fact(null, null);

        assertThat(evt.subcategoryId()).isNull();
        assertThat(evt.subcategory()).isNull();
    }

    @Test
    @DisplayName("productId stays the one mandatory component")
    void rejectsNullProductId() {
        assertThatThrownBy(() -> new ProductUpdatedV1(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        true,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        SUBCATEGORY_ID,
                        "Batteries"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("productId");
    }
}
