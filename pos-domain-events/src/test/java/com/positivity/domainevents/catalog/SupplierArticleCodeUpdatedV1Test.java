package com.positivity.domainevents.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class SupplierArticleCodeUpdatedV1Test {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    private static final UUID VENDOR_PROFILE_ID = UUID.fromString("01980a58-0000-7000-8000-000000000031");
    private static final UUID PRODUCT_ID = UUID.fromString("01980a58-0000-7000-8000-000000000032");

    @Test
    void roundTripPreservesFields() {
        SupplierArticleCodeUpdatedV1 evt =
                new SupplierArticleCodeUpdatedV1(VENDOR_PROFILE_ID, "michelin-eu", PRODUCT_ID, "999908");

        String json = MAPPER.writeValueAsString(evt);
        SupplierArticleCodeUpdatedV1 back = MAPPER.readValue(json, SupplierArticleCodeUpdatedV1.class);

        assertThat(back).isEqualTo(evt);
        assertThat(SupplierArticleCodeUpdatedV1.EVENT_TYPE).isEqualTo("catalog.supplier_article_code.updated");
        assertThat(SupplierArticleCodeUpdatedV1.SCHEMA_VERSION).isEqualTo(1);
    }

    @Test
    void rejectsNullFields() {
        assertThatThrownBy(() -> new SupplierArticleCodeUpdatedV1(null, "michelin-eu", PRODUCT_ID, "999908"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SupplierArticleCodeUpdatedV1(VENDOR_PROFILE_ID, null, PRODUCT_ID, "999908"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SupplierArticleCodeUpdatedV1(VENDOR_PROFILE_ID, "michelin-eu", null, "999908"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SupplierArticleCodeUpdatedV1(VENDOR_PROFILE_ID, "michelin-eu", PRODUCT_ID, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsBlankAliasOrCode() {
        assertThatThrownBy(() -> new SupplierArticleCodeUpdatedV1(VENDOR_PROFILE_ID, " ", PRODUCT_ID, "999908"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SupplierArticleCodeUpdatedV1(VENDOR_PROFILE_ID, "michelin-eu", PRODUCT_ID, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
