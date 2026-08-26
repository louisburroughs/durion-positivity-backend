package com.positivity.bulkloader.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings("java:S100")
class CatalogLoaderStrategyTest {

    private final CatalogLoaderStrategy strategy = new CatalogLoaderStrategy();

    @Test
    void mapRow_mapsAllCanonicalFields() {
        CatalogProductRecord record = strategy.mapRow(Map.ofEntries(
                Map.entry("sku", "MIC-000001"),
                Map.entry("upc", "822676000001"),
                Map.entry("name", "Maxion Steel Wheel"),
                Map.entry("description", "Steel Wheel test item"),
                Map.entry("categoryName", "Wheels"),
                Map.entry("subcategoryName", "Steel Wheel"),
                Map.entry("price", "$242.81"),
                Map.entry("mpn", "3A3ZMF8MD"),
                Map.entry("unitOfMeasure", "EA"),
                Map.entry("manufacturerName", "Maxion"),
                Map.entry("manufacturerBrand", "Maxion Wheels"),
                Map.entry("countryOfOrigin", "BR"),
                Map.entry("type", "PART")));

        assertThat(record.getSku()).isEqualTo("MIC-000001");
        assertThat(record.getUpc()).isEqualTo("822676000001");
        assertThat(record.getName()).isEqualTo("Maxion Steel Wheel");
        assertThat(record.getDescription()).isEqualTo("Steel Wheel test item");
        assertThat(record.getCategoryName()).isEqualTo("Wheels");
        assertThat(record.getSubcategoryName()).isEqualTo("Steel Wheel");
        assertThat(record.getPrice()).isEqualByComparingTo(new BigDecimal("242.81"));
        assertThat(record.getMpn()).isEqualTo("3A3ZMF8MD");
        assertThat(record.getUnitOfMeasure()).isEqualTo("EA");
        assertThat(record.getManufacturerName()).isEqualTo("Maxion");
        assertThat(record.getManufacturerBrand()).isEqualTo("Maxion Wheels");
        assertThat(record.getCountryOfOrigin()).isEqualTo("BR");
        assertThat(record.getType()).isEqualTo("PART");
    }

    @Test
    void mapRow_blankName_fallsBackToDescription() {
        CatalogProductRecord record = strategy.mapRow(Map.of("sku", "A-1", "name", " ", "description", "Widget"));

        assertThat(record.getName()).isEqualTo("Widget");
    }

    @Test
    void validate_missingSkuAndName_reportsErrors() {
        CatalogProductRecord record = strategy.mapRow(Map.of("description", ""));

        assertThat(strategy.validate(record)).containsExactly("sku is required", "name is required");
    }
}
