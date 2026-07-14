package com.positivity.bulkloader.internal.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings("java:S100")
class DelimitedRecordFileParserTest {

    private final DelimitedRecordFileParser parser = new DelimitedRecordFileParser();

    @Test
    void supports_delimitedExtensions() {
        assertThat(parser.supports("catalog.csv")).isTrue();
        assertThat(parser.supports("catalog.TSV")).isTrue();
        assertThat(parser.supports("catalog.txt")).isTrue();
        assertThat(parser.supports("catalog.psv")).isTrue();
        assertThat(parser.supports("catalog.xlsx")).isFalse();
        assertThat(parser.supports("catalog")).isFalse();
    }

    @Test
    void open_vendorStyleHeaders_readsRecordsByHeaderName() {
        String csv = """
                vendor_id,supplier_sku,manufacturer_part_no,upc,category,subcategory,product_name,description,uom,list_price
                VEND-MIC-001,MIC-000001,3A3ZMF8MD,822676000001,Wheels,Steel Wheel,Maxion Steel Wheel 15x6,Steel Wheel test item,EA,242.81
                VEND-NAT-005,NAT-000004,IIUC039TE,896474000004,TPMS,Valve Stem,Dill Valve Stem,Programmable valve stem,KIT,96.26
                """;

        try (RecordSource source = parser.open(stream(csv), "vendor_catalog.csv")) {
            assertThat(source.headers())
                    .containsExactly(
                            "vendor_id",
                            "supplier_sku",
                            "manufacturer_part_no",
                            "upc",
                            "category",
                            "subcategory",
                            "product_name",
                            "description",
                            "uom",
                            "list_price");
            Map<String, String> first = source.nextRecord();
            assertThat(first)
                    .containsEntry("supplier_sku", "MIC-000001")
                    .containsEntry("product_name", "Maxion Steel Wheel 15x6")
                    .containsEntry("list_price", "242.81");
            Map<String, String> second = source.nextRecord();
            assertThat(second).containsEntry("supplier_sku", "NAT-000004");
            assertThat(source.nextRecord()).isNull();
        }
    }

    @Test
    void open_sniffsPipeDelimiter() {
        String psv = """
                sku|name|price
                ABC-1|Widget|9.99
                """;

        try (RecordSource source = parser.open(stream(psv), "catalog.psv")) {
            assertThat(source.headers()).containsExactly("sku", "name", "price");
            assertThat(source.nextRecord()).containsEntry("name", "Widget");
        }
    }

    @Test
    void open_sniffsTabDelimiter() {
        String tsv = "sku\tname\tprice\nABC-1\tWidget\t9.99\n";

        try (RecordSource source = parser.open(stream(tsv), "catalog.txt")) {
            assertThat(source.headers()).containsExactly("sku", "name", "price");
            assertThat(source.nextRecord()).containsEntry("price", "9.99");
        }
    }

    @Test
    void open_handlesQuotedValuesAndBlankRows() {
        String csv = """
                sku,name,description
                ABC-1,"Widget, deluxe","Includes ""spare"" parts"

                ABC-2,Gadget,Basic gadget
                """;

        try (RecordSource source = parser.open(stream(csv), "catalog.csv")) {
            List<Map<String, String>> records = drain(source);
            assertThat(records).hasSize(2);
            assertThat(records.get(0))
                    .containsEntry("name", "Widget, deluxe")
                    .containsEntry("description", "Includes \"spare\" parts");
        }
    }

    @Test
    void open_stripsBomFromFirstHeader() {
        String csv = "﻿sku,name\nABC-1,Widget\n";

        try (RecordSource source = parser.open(stream(csv), "catalog.csv")) {
            assertThat(source.headers()).containsExactly("sku", "name");
        }
    }

    @Test
    void open_emptyFile_throws() {
        assertThatThrownBy(() -> parser.open(stream(""), "catalog.csv"))
                .isInstanceOf(RecordFileParseException.class)
                .hasMessageContaining("no header");
    }

    private InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private List<Map<String, String>> drain(RecordSource source) {
        List<Map<String, String>> records = new ArrayList<>();
        Map<String, String> row;
        while ((row = source.nextRecord()) != null) {
            records.add(row);
        }
        return records;
    }
}
