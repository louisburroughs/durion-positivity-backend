package com.positivity.customer.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.customer.PostgresSliceTestBase;
import com.positivity.customer.TestClockConfig;
import com.positivity.customer.internal.config.JpaAuditingConfig;
import com.positivity.customer.internal.entity.PersonParty;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import({JpaAuditingConfig.class, TestClockConfig.class})
class PersonPartyRepositoryTest extends PostgresSliceTestBase {

    @Autowired
    private PersonPartyRepository personPartyRepository;

    @Test
    @DisplayName("PersonParty save populates modifiedBy from AuditorAware without SQL error")
    void personPartySave_populatesModifiedBy_withoutSqlError() {
        PersonParty party = new PersonParty();
        party.setCustomerNumber("CUST-TEST-" + UUID.randomUUID());
        party.setPersonId(UUID.randomUUID());

        PersonParty saved = personPartyRepository.saveAndFlush(party);

        assertThat(saved.getModifiedBy()).isNotNull();
    }
}
