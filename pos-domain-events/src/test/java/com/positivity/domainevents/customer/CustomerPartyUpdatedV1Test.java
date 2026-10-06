package com.positivity.domainevents.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("CustomerPartyUpdatedV1 houseAccount (additive, schema version 1)")
class CustomerPartyUpdatedV1Test {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    private static final UUID PARTY_ID = UUID.fromString("01980a58-0000-7000-8000-0000000000a1");

    private static CustomerPartyUpdatedV1 fact(String houseAccount) {
        return new CustomerPartyUpdatedV1(
                PARTY_ID,
                "COMMERCIAL",
                "CASH",
                "Walk-in customer",
                "Walk-in customer",
                null,
                "ACTIVE",
                "STANDARD",
                true,
                null,
                null,
                houseAccount);
    }

    @Test
    @DisplayName("round-trips the CASH_SALE flag")
    void roundTripsHouseAccount() {
        CustomerPartyUpdatedV1 fact = fact(CustomerPartyUpdatedV1.HOUSE_ACCOUNT_CASH_SALE);

        String json = MAPPER.writeValueAsString(fact);
        CustomerPartyUpdatedV1 read = MAPPER.readValue(json, CustomerPartyUpdatedV1.class);

        assertThat(MAPPER.readTree(json).path("houseAccount").stringValue()).isEqualTo("CASH_SALE");
        assertThat(read).isEqualTo(fact);
        assertThat(read.houseAccount()).isEqualTo("CASH_SALE");
    }

    @Test
    @DisplayName("round-trips an ordinary party with a null flag")
    void roundTripsOrdinaryParty() {
        CustomerPartyUpdatedV1 fact = fact(null);

        CustomerPartyUpdatedV1 read = MAPPER.readValue(MAPPER.writeValueAsString(fact), CustomerPartyUpdatedV1.class);

        assertThat(read).isEqualTo(fact);
        assertThat(read.houseAccount()).isNull();
    }

    @Test
    @DisplayName("a payload published before the field existed still parses, with a null flag")
    void legacyPayloadWithoutTheFieldStillParses() {
        String legacyJson = """
                {"partyId":"01980a58-0000-7000-8000-0000000000a1","partyType":"COMMERCIAL",
                 "customerNumber":"CUST-0000000A","displayName":"Acme","legalName":"Acme Corporation",
                 "personId":null,"status":"ACTIVE","tier":"STANDARD","requirementsMet":true,
                 "creditHold":false,"parentPartyId":null}
                """;

        CustomerPartyUpdatedV1 read = MAPPER.readValue(legacyJson, CustomerPartyUpdatedV1.class);

        assertThat(read.partyId()).isEqualTo(PARTY_ID);
        assertThat(read.legalName()).isEqualTo("Acme Corporation");
        assertThat(read.houseAccount()).isNull();
    }

    @Test
    @DisplayName("the serialized envelope payload is a plain nullable string, not a nested object")
    void serializesAsPlainString() {
        JsonNode tree = MAPPER.valueToTree(fact(CustomerPartyUpdatedV1.HOUSE_ACCOUNT_CASH_SALE));

        assertThat(tree.path("houseAccount").isString()).isTrue();
        assertThat(MAPPER.valueToTree(fact(null)).path("houseAccount").isNull()).isTrue();
    }
}
