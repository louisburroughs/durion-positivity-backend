package com.positivity.bulkloader.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"java:S100", "java:S1192"})
class BasePriceLoaderStrategyTest {

    private final BasePriceLoaderStrategy strategy = new BasePriceLoaderStrategy();

    // ─── mapRow ──────────────────────────────────────────────────────────────

    @Test
    void mapRow_populatesAllFields() {
        Map<String, String> row = Map.of(
                "productId", "00000000-0000-0000-0000-000000000001",
                "msrp", "19.99",
                "currency", "USD",
                "effectiveFrom", "2025-01-01T00:00:00Z");

        BasePriceRecord result = strategy.mapRow(row);

        assertThat(result.getProductId()).isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(result.getMsrp()).isEqualTo("19.99");
        assertThat(result.getCurrency()).isEqualTo("USD");
        assertThat(result.getEffectiveFrom()).isEqualTo("2025-01-01T00:00:00Z");
    }

    // ─── validate ────────────────────────────────────────────────────────────

    @Test
    void validate_passesWhenAllRequiredFieldsPresent() {
        BasePriceRecord record = new BasePriceRecord();
        record.setProductId("00000000-0000-0000-0000-000000000002");
        record.setMsrp("9.99");
        record.setCurrency("EUR");
        record.setEffectiveFrom("2025-06-01T00:00:00Z");

        List<String> errors = strategy.validate(record);

        assertThat(errors).isEmpty();
    }

    @Test
    void validate_failsWhenProductIdBlank() {
        BasePriceRecord record = validRecord();
        record.setProductId("  ");

        List<String> errors = strategy.validate(record);

        assertThat(errors).anyMatch(e -> e.contains("productId"));
    }

    @Test
    void validate_failsWhenMsrpBlank() {
        BasePriceRecord record = validRecord();
        record.setMsrp(null);

        List<String> errors = strategy.validate(record);

        assertThat(errors).anyMatch(e -> e.contains("msrp"));
    }

    @Test
    void validate_failsWhenCurrencyBlank() {
        BasePriceRecord record = validRecord();
        record.setCurrency("");

        List<String> errors = strategy.validate(record);

        assertThat(errors).anyMatch(e -> e.contains("currency"));
    }

    @Test
    void validate_failsWhenEffectiveFromBlank() {
        BasePriceRecord record = validRecord();
        record.setEffectiveFrom("   ");

        List<String> errors = strategy.validate(record);

        assertThat(errors).anyMatch(e -> e.contains("effectiveFrom"));
    }

    @Test
    void validate_failsWhenCurrencyNotThreeChars() {
        BasePriceRecord record = validRecord();
        record.setCurrency("US");

        List<String> errors = strategy.validate(record);

        assertThat(errors).anyMatch(e -> e.contains("currency"));
    }

    @Test
    void validate_passesWhenCurrencyHasTrailingWhitespace() {
        BasePriceRecord record = validRecord();
        record.setCurrency("USD ");

        List<String> errors = strategy.validate(record);

        assertThat(errors).noneMatch(e -> e.contains("currency"));
    }

    // ─── getDomainType ────────────────────────────────────────────────────────

    @Test
    void getDomainType_returnsBasePrice() {
        assertThat(strategy.getDomainType()).isEqualTo(DomainType.BASE_PRICE);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private BasePriceRecord validRecord() {
        BasePriceRecord record = new BasePriceRecord();
        record.setProductId("00000000-0000-0000-0000-000000000099");
        record.setMsrp("49.99");
        record.setCurrency("USD");
        record.setEffectiveFrom("2025-01-01T00:00:00Z");
        return record;
    }
}
