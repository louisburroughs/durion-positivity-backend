package com.positivity.customer.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.customer.internal.entity.PersonParty;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PersonPartyRepositoryTest {

    @Autowired
    private PersonPartyRepository personPartyRepository;

    @BeforeEach
    void setUp() {
        personPartyRepository.deleteAll();
    }

    @Test
    @DisplayName("PersonParty save populates modifiedBy from AuditorAware without SQL error")
    void personPartySave_populatesModifiedBy_withoutSqlError() {
        PersonParty party = new PersonParty();
        party.setCustomerNumber("CUST-TEST-001");
        party.setPersonId(UUID.randomUUID());
        party.setLastName("Doe");
        party.setFirstName("John");

        PersonParty saved = personPartyRepository.saveAndFlush(party);

        assertThat(saved.getModifiedBy()).isNotNull();
    }
}
