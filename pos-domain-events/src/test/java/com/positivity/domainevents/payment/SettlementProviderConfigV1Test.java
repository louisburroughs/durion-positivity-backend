package com.positivity.domainevents.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.positivity.domainevents.payment.SettlementProviderConfigV1.FeeRepresentation;
import com.positivity.domainevents.payment.SettlementProviderConfigV1.MatchReferenceField;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class SettlementProviderConfigV1Test {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    private static final UUID EVENT_ID = UUID.fromString("01980a58-0000-7000-8000-000000000002");

    private static SettlementProviderConfigV1 config() {
        return new SettlementProviderConfigV1(
                EVENT_ID,
                SettlementProviderConfigV1.SCHEMA_VERSION,
                "STRIPE",
                "Stripe Payments",
                MatchReferenceField.PAYMENT_REFERENCE,
                new BigDecimal("0.01"),
                FeeRepresentation.SEPARATE_LINES,
                Map.of("account", "acct_1"));
    }

    @Test
    void serializationRoundTripPreservesAllFields() {
        SettlementProviderConfigV1 event = config();

        String json = MAPPER.writeValueAsString(event);
        SettlementProviderConfigV1 read = MAPPER.readValue(json, SettlementProviderConfigV1.class);

        assertThat(read).isEqualTo(event);
        assertThat(json)
                .contains("\"providerCode\":\"STRIPE\"")
                .contains("\"matchReferenceField\":\"PAYMENT_REFERENCE\"")
                .contains("\"feeRepresentation\":\"SEPARATE_LINES\"")
                .contains("\"schemaVersion\":1");
    }

    @Test
    void roundTripPreservesEachFeeRepresentationAndNullOptionals() {
        for (FeeRepresentation rep : FeeRepresentation.values()) {
            SettlementProviderConfigV1 event = new SettlementProviderConfigV1(
                    EVENT_ID, 1, "PROV", null, MatchReferenceField.PROVIDER_LINE_REF, BigDecimal.ZERO, rep, null);

            SettlementProviderConfigV1 read =
                    MAPPER.readValue(MAPPER.writeValueAsString(event), SettlementProviderConfigV1.class);

            assertThat(read).isEqualTo(event);
            assertThat(read.feeRepresentation()).isEqualTo(rep);
            assertThat(read.providerName()).isNull();
            assertThat(read.extension()).isNull();
        }
    }

    @Test
    void rejectsNegativeTolerance() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SettlementProviderConfigV1(
                        EVENT_ID,
                        1,
                        "STRIPE",
                        null,
                        MatchReferenceField.PAYMENT_REFERENCE,
                        new BigDecimal("-0.01"),
                        FeeRepresentation.HEADER_ONLY,
                        null))
                .withMessageContaining("amountTolerance");
    }

    @Test
    void rejectsBlankProviderCode() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SettlementProviderConfigV1(
                        EVENT_ID,
                        1,
                        " ",
                        null,
                        MatchReferenceField.PAYMENT_REFERENCE,
                        BigDecimal.ZERO,
                        FeeRepresentation.HEADER_ONLY,
                        null))
                .withMessageContaining("providerCode");
    }
}
