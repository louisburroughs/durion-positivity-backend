package com.positivity.bulkloader.internal.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings("java:S100")
class JsonRecordFileParserTest {

    private final JsonRecordFileParser parser = new JsonRecordFileParser();

    @Test
    void supports_jsonOnly() {
        assertThat(parser.supports("catalog.json")).isTrue();
        assertThat(parser.supports("catalog.yaml")).isFalse();
    }

    @Test
    void open_rootArray_readsRecords() {
        String json = """
                [
                  {"supplier_sku": "MIC-000001", "product_name": "Steel Wheel", "list_price": 242.81},
                  {"supplier_sku": "NAT-000004", "product_name": "Valve Stem", "list_price": 96.26}
                ]
                """;

        try (RecordSource source = parser.open(stream(json), "catalog.json")) {
            assertThat(source.headers()).containsExactly("supplier_sku", "product_name", "list_price");
            Map<String, String> first = source.nextRecord();
            assertThat(first).containsEntry("supplier_sku", "MIC-000001").containsEntry("list_price", "242.81");
            assertThat(source.nextRecord()).isNotNull();
            assertThat(source.nextRecord()).isNull();
        }
    }

    @Test
    void open_nestedRecordArray_discoversRecordsAndFlattensObjects() {
        String json = """
                {
                  "vendor": "Michelin North America",
                  "generated": "2026-03-04",
                  "catalog": {
                    "items": [
                      {
                        "sku": "MIC-000001",
                        "name": "Steel Wheel",
                        "pricing": {"list": 242.81, "dealer": 174.89},
                        "tags": ["wheels", "steel"]
                      },
                      {
                        "sku": "NAT-000004",
                        "name": "Valve Stem",
                        "pricing": {"list": 96.26, "dealer": 62.07},
                        "tags": ["tpms"]
                      }
                    ]
                  }
                }
                """;

        try (RecordSource source = parser.open(stream(json), "catalog.json")) {
            assertThat(source.headers()).containsExactly("sku", "name", "pricing.list", "pricing.dealer", "tags");
            Map<String, String> first = source.nextRecord();
            assertThat(first)
                    .containsEntry("sku", "MIC-000001")
                    .containsEntry("pricing.list", "242.81")
                    .containsEntry("tags", "wheels; steel");
        }
    }

    @Test
    void open_noRecordList_treatsRootObjectAsSingleRecord() {
        String json = """
                {"sku": "MIC-000001", "name": "Steel Wheel"}
                """;

        try (RecordSource source = parser.open(stream(json), "catalog.json")) {
            assertThat(source.nextRecord()).containsEntry("sku", "MIC-000001");
            assertThat(source.nextRecord()).isNull();
        }
    }

    @Test
    void open_malformedJson_throws() {
        assertThatThrownBy(() -> parser.open(stream("{not json"), "catalog.json"))
                .isInstanceOf(RecordFileParseException.class);
    }

    private InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
