package com.positivity.customer.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP:550 S7 (#2505) rule 1: no request can set {@code houseAccount}. {@code CustomerDTO} carries
 * the field on responses, and the same DTO is the body of {@code POST /v1/crm} and
 * {@code PUT /v1/crm/{id}} — so a caller can send it. It must be ignored: only the provisioner
 * ever writes the marker.
 */
@Transactional
@DisplayName("houseAccount cannot be set through the customer API (CAP:550 S7)")
class HouseAccountNotSettableContractBehaviorIT extends BaseContractIntegrationTest {

    private static final String AUTHORITIES = "crm:party:create,crm:party:edit,crm:party:view";

    @Autowired
    private CommercialPartyRepository parties;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("POST /v1/crm with houseAccount = CASH_SALE stores an ordinary party")
    void createIgnoresHouseAccount() throws Exception {
        UUID partyId = createCommercialCustomer("CUST-HA-CREATE-1");

        CommercialParty stored = parties.findById(partyId).orElseThrow();
        assertThat(stored.getHouseAccount()).isNull();
        assertThat(parties.existsByPartyIdAndHouseAccountIsNotNull(partyId)).isFalse();
    }

    @Test
    @DisplayName("PUT /v1/crm/{id} with houseAccount = CASH_SALE leaves an ordinary party ordinary")
    void updateIgnoresHouseAccount() throws Exception {
        UUID partyId = createCommercialCustomer("CUST-HA-UPDATE-1");

        mockMvc.perform(put("/v1/crm/{id}", partyId)
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", AUTHORITIES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Renamed Fleet","lastName":"Renamed Fleet LLC",
                                 "customerType":"COMMERCIAL","houseAccount":"CASH_SALE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.houseAccount").doesNotExist());

        parties.flush();
        assertThat(parties.findById(partyId).orElseThrow().getHouseAccount()).isNull();
        assertThat(parties.existsByPartyIdAndHouseAccountIsNotNull(partyId)).isFalse();
    }

    private UUID createCommercialCustomer(String customerNumber) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/crm")
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", AUTHORITIES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerNumber":"%s","firstName":"Would-be House","lastName":"Would-be House LLC",
                                 "customerType":"COMMERCIAL","houseAccount":"CASH_SALE"}
                                """.formatted(customerNumber)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.houseAccount").doesNotExist())
                .andReturn();
        parties.flush();
        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .path("id")
                .stringValue());
    }
}
