package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the alpha base-price fixture agrees with the catalog pack it prices.
 *
 * <p>The pack names its products by SKU, because product ids are generated when the catalog loads.
 * That makes the two files a pair: a SKU renamed on one side and not the other resolves to nothing,
 * and the price row fails at reseed time rather than at build time. Reading both here moves that
 * forward.
 *
 * <p>It also pins the two formats the price endpoint is strict about and whose own schema examples
 * get wrong: {@code effectiveFrom} is parsed as a full instant, not a date, and the currency is a
 * three-letter code.
 */
@SuppressWarnings({"java:S100", "java:S1192"})
class AlphaFixtureBasePricesTest {

    private static final Path FIXTURE_ROOT = Path.of(System.getProperty("user.dir"))
            .resolve("../scripts/fixtures/seed/alpha")
            .normalize();

    private static final Path BASE_PRICES = FIXTURE_ROOT.resolve("price/base-prices.csv");
    private static final Path PRODUCTS = FIXTURE_ROOT.resolve("catalog/products.csv");

    @Test
    @DisplayName("every priced SKU is a product the catalog pack creates")
    void everyPricedSkuIsInTheCatalog() throws IOException {
        Set<String> catalogSkus =
                readRows(PRODUCTS).stream().map(row -> row.get("sku")).collect(Collectors.toSet());

        List<String> unknown = readRows(BASE_PRICES).stream()
                .map(row -> row.get("sku"))
                .filter(sku -> !catalogSkus.contains(sku))
                .toList();

        assertThat(unknown)
                .as("price rows naming SKUs that catalog/products.csv does not create: %s", unknown)
                .isEmpty();
    }

    @Test
    @DisplayName("no SKU is priced twice")
    void noSkuIsPricedTwice() throws IOException {
        // The endpoint closes the previous open window at a new effectiveFrom, so two rows for one
        // SKU at the same instant would leave whichever landed second in effect, arbitrarily.
        List<String> skus =
                readRows(BASE_PRICES).stream().map(row -> row.get("sku")).toList();

        assertThat(skus).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("effectiveFrom is a full instant, not a date")
    void effectiveFromIsAnInstant() throws IOException {
        // BasePriceBulkIngestController calls Instant.parse; the record's own @Schema example shows
        // a bare date, which would fail every row.
        for (Map<String, String> row : readRows(BASE_PRICES)) {
            String value = row.get("effectiveFrom");
            try {
                Instant.parse(value);
            } catch (DateTimeParseException e) {
                throw new AssertionError(
                        "effectiveFrom '%s' for %s is not an ISO-8601 instant".formatted(value, row.get("sku")), e);
            }
        }
    }

    @Test
    @DisplayName("every row carries a positive amount and a three-letter currency")
    void amountsAndCurrenciesAreWellFormed() throws IOException {
        for (Map<String, String> row : readRows(BASE_PRICES)) {
            String sku = row.get("sku");
            assertThat(row.get("currency"))
                    .as("%s: currency must be a three-letter ISO-4217 code", sku)
                    .hasSize(3);
            assertThat(new BigDecimal(row.get("msrp")))
                    .as("%s: msrp must be positive", sku)
                    .isGreaterThan(BigDecimal.ZERO);
        }
    }

    private static List<Map<String, String>> readRows(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines).as("fixture %s is empty", file).isNotEmpty();

        List<String> headers = split(lines.getFirst());
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            List<String> values = split(line);
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                row.put(headers.get(i), i < values.size() ? values.get(i) : "");
            }
            rows.add(row);
        }
        return rows;
    }

    private static List<String> split(String line) {
        return java.util.Arrays.stream(line.split(",", -1)).map(String::trim).toList();
    }
}
