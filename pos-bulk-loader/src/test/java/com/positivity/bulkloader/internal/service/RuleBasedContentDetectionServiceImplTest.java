package com.positivity.bulkloader.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.bulkloader.internal.dto.ContentDetectionResult;
import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings("java:S100")
class RuleBasedContentDetectionServiceImplTest {

    /** Full header row of the synthetic vendor parts catalog used as the alpha test file. */
    private static final List<String> VENDOR_FILE_HEADERS = List.of(
            "vendor_id",
            "vendor_name",
            "supplier_sku",
            "manufacturer_part_no",
            "upc",
            "category",
            "subcategory",
            "brand",
            "product_name",
            "description",
            "size_or_spec",
            "load_speed_rating",
            "position_or_fitment",
            "uom",
            "case_pack",
            "min_order_qty",
            "order_multiple",
            "warehouse",
            "stock_status",
            "lead_time_days",
            "list_price",
            "dealer_cost",
            "map_price",
            "core_charge",
            "fet",
            "eco_fee",
            "currency",
            "tax_code",
            "effective_date",
            "expiration_date",
            "discontinued",
            "supersedes_sku",
            "replaced_by_sku",
            "weight_lb",
            "length_in",
            "width_in",
            "height_in",
            "country_of_origin",
            "warranty_months",
            "hazmat",
            "notes");

    private final RuleBasedContentDetectionServiceImpl service = new RuleBasedContentDetectionServiceImpl();

    @Test
    void detect_vendorFileHeaders_identifiesCatalogProduct() {
        ContentDetectionResult result = service.detect(VENDOR_FILE_HEADERS, List.of());

        assertThat(result.getDetectedDomain()).isEqualTo(DomainType.CATALOG_PRODUCT);
        assertThat(result.getConfidence()).isPositive();
        assertThat(result.getSuggestedColumnMappings()).containsEntry("supplier_sku", "sku");
    }

    @Test
    void suggestMappings_vendorFileHeaders_mapsAllCatalogFields() {
        Map<String, String> mappings = service.suggestMappings(VENDOR_FILE_HEADERS, DomainType.CATALOG_PRODUCT);

        assertThat(mappings)
                .containsEntry("supplier_sku", "sku")
                .containsEntry("manufacturer_part_no", "mpn")
                .containsEntry("upc", "upc")
                .containsEntry("category", "categoryName")
                .containsEntry("subcategory", "subcategoryName")
                .containsEntry("product_name", "name")
                .containsEntry("description", "description")
                .containsEntry("uom", "unitOfMeasure")
                .containsEntry("list_price", "price");
        // Cost/fee columns that have no catalog target must not be mapped.
        assertThat(mappings).doesNotContainKeys("dealer_cost", "map_price", "core_charge", "vendor_id", "notes");
    }

    @Test
    void suggestMappings_legacyFixedFormatHeaders_stillMap() {
        List<String> legacyHeaders =
                List.of("sku", "upc", "name", "description", "categoryName", "subcategoryName", "price");

        Map<String, String> mappings = service.suggestMappings(legacyHeaders, DomainType.CATALOG_PRODUCT);

        assertThat(mappings)
                .containsEntry("sku", "sku")
                .containsEntry("upc", "upc")
                .containsEntry("name", "name")
                .containsEntry("description", "description")
                .containsEntry("categoryName", "categoryName")
                .containsEntry("subcategoryName", "subcategoryName")
                .containsEntry("price", "price");
    }

    @Test
    void suggestMappings_messyHeaderPunctuation_normalizes() {
        Map<String, String> mappings = service.suggestMappings(
                List.of("Supplier SKU", "Product Name", "List Price ($)", "Unit-of-Measure"),
                DomainType.CATALOG_PRODUCT);

        assertThat(mappings)
                .containsEntry("Supplier SKU", "sku")
                .containsEntry("Product Name", "name")
                .containsEntry("List Price ($)", "price")
                .containsEntry("Unit-of-Measure", "unitOfMeasure");
    }

    @Test
    void suggestMappings_duplicateTargets_firstHeaderWins() {
        Map<String, String> mappings =
                service.suggestMappings(List.of("sku", "supplier_sku", "part_number"), DomainType.CATALOG_PRODUCT);

        assertThat(mappings).containsExactly(Map.entry("sku", "sku"));
    }
}
