package com.positivity.tax.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The shared reader of pos-tax's refusal code (#2644): the one parser the three callers translate from. */
@DisplayName("TaxRefusals — the code of a pos-tax ApiError body")
class TaxRefusalsTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("reads the code of an ApiError body, whatever the spacing, digits included")
    void readsTheCode() {
        assertThat(TaxRefusals.codeOf(bytes("{\"code\":\"CURRENCY_NOT_SUPPORTED\",\"message\":\"CAD\"}")))
                .isEqualTo(TaxRefusals.CURRENCY_NOT_SUPPORTED);
        assertThat(TaxRefusals.codeOf(bytes("{ \"status\": 422, \"code\" : \"TAX_JURISDICTION_NOT_CONFIGURED\" }")))
                .isEqualTo(TaxRefusals.TAX_JURISDICTION_NOT_CONFIGURED);
        assertThat(TaxRefusals.codeOf(bytes("{\"code\":\"RULE_2_BROKEN\"}"))).isEqualTo("RULE_2_BROKEN");
    }

    @Test
    @DisplayName("a missing, empty or codeless body is null, never an exception")
    void noCode() {
        assertThat(TaxRefusals.codeOf(null)).isNull();
        assertThat(TaxRefusals.codeOf(new byte[0])).isNull();
        assertThat(TaxRefusals.codeOf(bytes("{\"message\":\"no code here\"}"))).isNull();
        assertThat(TaxRefusals.codeOf(bytes("{\"code\":\"lower_case\"}"))).isNull();
    }
}
