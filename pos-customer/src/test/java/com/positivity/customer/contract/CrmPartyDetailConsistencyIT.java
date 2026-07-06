package com.positivity.customer.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.enums.AccountStatus;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regression contract for issue #818.
 *
 * Verifies that a PERSON party returned by the unified CRM list does not 404 on
 * party-scoped detail endpoints.
 */
@Transactional
class CrmPartyDetailConsistencyIT extends BaseContractIntegrationTest {

    @Autowired
    private PersonPartyRepository personPartyRepository;

    private PersonParty createPersonParty() {
        PersonParty personParty = new PersonParty();
        personParty.setPersonId(UUID.fromString("00000000-0000-0000-0000-000000000201"));
        personParty.setCustomerNumber("CUST-PARTY-818");
        personParty.setStatus(AccountStatus.ACTIVE);
        return personPartyRepository.saveAndFlush(personParty);
    }

    @Test
    @DisplayName("Issue #818: person-party detail endpoints should not 404")
    void personPartyDetailEndpoints_doNot404() throws Exception {
        PersonParty personParty = createPersonParty();
        UUID partyId = personParty.getPartyId();

        mockMvc.perform(get("/v1/crm/accounts/parties/{partyId}", partyId)
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", "crm:party:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partyId").value(partyId.toString()))
                .andExpect(jsonPath("$.partyType").value("PERSON"));

        mockMvc.perform(get("/v1/crm/parties/{partyId}/communicationPreferences", partyId)
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", "crm:contact_preference:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partyId").value(partyId.toString()));

        mockMvc.perform(get("/v1/crm/commercial-accounts/{partyId}/contacts", partyId)
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", "crm:relationship:read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commercialAccountId").value(partyId.toString()))
                .andExpect(jsonPath("$.contacts").isArray());

        mockMvc.perform(get("/v1/crm/snapshot/party/{partyId}", partyId)
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", "crm:party:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.partyId").value(partyId.toString()))
                .andExpect(jsonPath("$.billingRules").isNotEmpty());

        mockMvc.perform(get("/v1/crm/snapshot/party/{partyId}/billing-rules", partyId)
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", "crm:party:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poRequired").isBoolean());
    }
}
