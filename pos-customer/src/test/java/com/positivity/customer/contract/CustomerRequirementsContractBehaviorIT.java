package com.positivity.customer.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.customer.internal.entity.BillingRulesEmbeddable;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.Contact;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.enums.AccountStatus;
import com.positivity.customer.internal.enums.PartyType;
import com.positivity.customer.internal.enums.PreferredContactMethod;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class CustomerRequirementsContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private CommercialPartyRepository commercialPartyRepository;

    @Autowired
    private PersonPartyRepository personPartyRepository;

    private CommercialParty createCommercialParty(AccountStatus status, Boolean creditHold) {
        CommercialParty party = new CommercialParty();
        party.setPartyType(PartyType.COMMERCIAL);
        party.setLegalName("Requirements Test Corp");
        party.setDisplayName("Requirements Corp");
        party.setPartyNumber("REQ-" + UUID.randomUUID());
        party.setCustomerNumber("CUST-REQ-" + UUID.randomUUID().toString().substring(0, 8));
        party.setStatus(status);

        if (creditHold != null) {
            BillingRulesEmbeddable billingRules = new BillingRulesEmbeddable();
            billingRules.setCreditHold(creditHold);
            party.setBillingRules(billingRules);
        }

        Contact contact = new Contact();
        contact.setPersonId(UUID.randomUUID());
        contact.setFirstName("Test");
        contact.setLastName("Contact");
        contact.setActive(true);
        contact.setCommercialParty(party);
        party.getContacts().add(contact);

        return commercialPartyRepository.saveAndFlush(party);
    }

    private PersonParty createPersonParty(AccountStatus status) {
        PersonParty party = new PersonParty();
        party.setPersonId(UUID.randomUUID());
        party.setCustomerNumber("CUST-PER-" + UUID.randomUUID().toString().substring(0, 8));
        party.setFirstName("Jane");
        party.setLastName("Doe");
        party.setPreferredContactMethod(PreferredContactMethod.NONE);
        party.setStatus(status);
        return personPartyRepository.saveAndFlush(party);
    }

    @Test
    @DisplayName("CR-001: returns true for active commercial party without credit hold")
    void requirementsMet_returnsTrue_forActiveCommercialPartyWithoutCreditHold() throws Exception {
        CommercialParty party = createCommercialParty(AccountStatus.ACTIVE, false);

        mockMvc.perform(get("/v1/customers/{id}/requirements-met", party.getPartyId())
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", PARTY_VIEW_AUTHORITY))
                .andExpect(status().isOk())
                .andExpect(content().string("true"));
    }

    @Test
    @DisplayName("CR-002: returns true for active person party")
    void requirementsMet_returnsTrue_forActivePersonParty() throws Exception {
        PersonParty party = createPersonParty(AccountStatus.ACTIVE);

        mockMvc.perform(get("/v1/customers/{id}/requirements-met", party.getPartyId())
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", PARTY_VIEW_AUTHORITY))
                .andExpect(status().isOk())
                .andExpect(content().string("true"));
    }

    @Test
    @DisplayName("CR-003: returns false for non-active statuses")
    void requirementsMet_returnsFalse_forInactiveOnHoldAndMerged() throws Exception {
        CommercialParty inactive = createCommercialParty(AccountStatus.INACTIVE, false);
        CommercialParty onHold = createCommercialParty(AccountStatus.ON_HOLD, false);
        CommercialParty merged = createCommercialParty(AccountStatus.MERGED, false);

        mockMvc.perform(get("/v1/customers/{id}/requirements-met", inactive.getPartyId())
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", PARTY_VIEW_AUTHORITY))
                .andExpect(status().isOk())
                .andExpect(content().string("false"));

        mockMvc.perform(get("/v1/customers/{id}/requirements-met", onHold.getPartyId())
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", PARTY_VIEW_AUTHORITY))
                .andExpect(status().isOk())
                .andExpect(content().string("false"));

        mockMvc.perform(get("/v1/customers/{id}/requirements-met", merged.getPartyId())
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", PARTY_VIEW_AUTHORITY))
                .andExpect(status().isOk())
                .andExpect(content().string("false"));
    }

    @Test
    @DisplayName("CR-004: returns false for active commercial party on credit hold")
    void requirementsMet_returnsFalse_forCommercialPartyOnCreditHold() throws Exception {
        CommercialParty party = createCommercialParty(AccountStatus.ACTIVE, true);

        mockMvc.perform(get("/v1/customers/{id}/requirements-met", party.getPartyId())
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", PARTY_VIEW_AUTHORITY))
                .andExpect(status().isOk())
                .andExpect(content().string("false"));
    }

    @Test
    @DisplayName("CR-005: returns 404 for unknown customer id")
    void requirementsMet_returns404_forUnknownCustomerId() throws Exception {
        mockMvc.perform(get("/v1/customers/{id}/requirements-met", UUID.randomUUID())
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", PARTY_VIEW_AUTHORITY))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("CR-006: returns 401 when unauthenticated")
    void requirementsMet_returns401_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/v1/customers/{id}/requirements-met", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
