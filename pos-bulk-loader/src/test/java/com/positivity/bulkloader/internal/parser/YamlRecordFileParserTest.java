package com.positivity.bulkloader.internal.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings("java:S100")
class YamlRecordFileParserTest {

    private final YamlRecordFileParser parser = new YamlRecordFileParser();

    @Test
    void supports_yamlExtensions() {
        assertThat(parser.supports("catalog.yaml")).isTrue();
        assertThat(parser.supports("catalog.yml")).isTrue();
        assertThat(parser.supports("catalog.json")).isFalse();
    }

    @Test
    void open_nestedSequence_readsRecords() {
        String yaml = """
                vendor: National Tire Supply
                products:
                  - supplier_sku: NAT-000004
                    product_name: Dill Valve Stem
                    uom: KIT
                    list_price: 96.26
                  - supplier_sku: MIC-000001
                    product_name: Maxion Steel Wheel
                    uom: EA
                    list_price: 242.81
                """;

        try (RecordSource source = parser.open(stream(yaml), "catalog.yaml")) {
            assertThat(source.headers()).containsExactly("supplier_sku", "product_name", "uom", "list_price");
            Map<String, String> first = source.nextRecord();
            assertThat(first)
                    .containsEntry("supplier_sku", "NAT-000004")
                    .containsEntry("uom", "KIT")
                    .containsEntry("list_price", "96.26");
            assertThat(source.nextRecord()).isNotNull();
            assertThat(source.nextRecord()).isNull();
        }
    }

    @Test
    void open_rootSequence_readsRecords() {
        String yaml = """
                - sku: A-1
                  name: Widget
                - sku: A-2
                  name: Gadget
                """;

        try (RecordSource source = parser.open(stream(yaml), "catalog.yml")) {
            assertThat(source.nextRecord()).containsEntry("sku", "A-1");
            assertThat(source.nextRecord()).containsEntry("name", "Gadget");
            assertThat(source.nextRecord()).isNull();
        }
    }

    @Test
    void open_malformedYaml_throws() {
        assertThatThrownBy(() -> parser.open(stream("items: [unclosed"), "catalog.yaml"))
                .isInstanceOf(RecordFileParseException.class);
    }

    private InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
