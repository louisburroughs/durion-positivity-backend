package com.positivity.bulkloader.internal.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings("java:S100")
class XmlRecordFileParserTest {

    private final XmlRecordFileParser parser = new XmlRecordFileParser();

    @Test
    void supports_xmlOnly() {
        assertThat(parser.supports("catalog.xml")).isTrue();
        assertThat(parser.supports("catalog.json")).isFalse();
    }

    @Test
    void open_repeatedElements_readsRecordsWithAttributesAndNestedElements() {
        String xml = """
                <catalog vendor="Michelin North America">
                  <generated>2026-03-04</generated>
                  <items>
                    <item id="1">
                      <supplier_sku>MIC-000001</supplier_sku>
                      <product_name>Maxion Steel Wheel</product_name>
                      <pricing><list>242.81</list><dealer>174.89</dealer></pricing>
                    </item>
                    <item id="2">
                      <supplier_sku>NAT-000004</supplier_sku>
                      <product_name>Dill Valve Stem</product_name>
                      <pricing><list>96.26</list><dealer>62.07</dealer></pricing>
                    </item>
                  </items>
                </catalog>
                """;

        try (RecordSource source = parser.open(stream(xml), "catalog.xml")) {
            assertThat(source.headers())
                    .containsExactly("id", "supplier_sku", "product_name", "pricing.list", "pricing.dealer");
            Map<String, String> first = source.nextRecord();
            assertThat(first)
                    .containsEntry("id", "1")
                    .containsEntry("supplier_sku", "MIC-000001")
                    .containsEntry("pricing.list", "242.81");
            assertThat(source.nextRecord()).containsEntry("supplier_sku", "NAT-000004");
            assertThat(source.nextRecord()).isNull();
        }
    }

    @Test
    void open_doctypeDeclaration_isRejected() {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE catalog [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <catalog><item><sku>&xxe;</sku></item></catalog>
                """;

        assertThatThrownBy(() -> parser.open(stream(xml), "catalog.xml")).isInstanceOf(RecordFileParseException.class);
    }

    @Test
    void open_malformedXml_throws() {
        assertThatThrownBy(() -> parser.open(stream("<catalog><item>"), "catalog.xml"))
                .isInstanceOf(RecordFileParseException.class);
    }

    private InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
