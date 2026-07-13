package com.positivity.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.dto.CustomerDTO;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.internal.service.PersonDirectoryService;
import com.positivity.customer.internal.service.PersonPartyServiceImpl;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class PersonPartyServiceImplSearchTest {

    @Mock
    private PersonPartyRepository personPartyRepository;

    @Mock
    private PersonDirectoryService personDirectoryService;

    private PersonPartyServiceImpl service() {
        return new PersonPartyServiceImpl(personPartyRepository, personDirectoryService);
    }

    private PersonParty person(UUID partyId, UUID personId) {
        PersonParty p = new PersonParty();
        p.setPartyId(partyId);
        p.setPersonId(personId);
        p.setCustomerNumber("CUST-" + partyId.toString().substring(0, 8));
        p.setVehicleVins(new HashSet<>());
        return p;
    }

    private PersonDirectoryService.PersonIdentity identity(UUID personId, String first, String last, String email) {
        return new PersonDirectoryService.PersonIdentity(
                personId, first, last, email, List.of(new PersonDirectoryService.ContactPoint("EMAIL", email, true)));
    }

    @Test
    void searchCustomers_byName_mapsIdentityToDto_withPartyId() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000221");
        UUID personId = UUID.fromString("00000000-0000-0000-0000-000000000222");
        when(personDirectoryService.searchPersons("doe"))
                .thenReturn(List.of(identity(personId, "John", "Doe", "jd@x.com")));
        when(personPartyRepository.findByPersonId(personId)).thenReturn(Optional.of(person(partyId, personId)));

        Page<CustomerDTO> page = service().searchCustomers("doe", null, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        CustomerDTO dto = page.getContent().get(0);
        assertThat(dto.getId()).isEqualTo(partyId);
        assertThat(dto.getPartyId()).isEqualTo(partyId);
        assertThat(dto.getFirstName()).isEqualTo("John");
        assertThat(dto.getLastName()).isEqualTo("Doe");
    }

    @Test
    void searchCustomers_emailFilter_excludesNonMatchingIdentities() {
        UUID personA = UUID.fromString("00000000-0000-0000-0000-0000000002a1");
        UUID personB = UUID.fromString("00000000-0000-0000-0000-0000000002b1");
        UUID partyB = UUID.fromString("00000000-0000-0000-0000-0000000002b2");
        when(personDirectoryService.searchPersons("jd@acme.com"))
                .thenReturn(List.of(
                        identity(personA, "Jane", "Smith", "jane@other.com"),
                        identity(personB, "John", "Doe", "jd@acme.com")));
        when(personPartyRepository.findByPersonId(personB)).thenReturn(Optional.of(person(partyB, personB)));

        Page<CustomerDTO> page = service().searchCustomers(null, "jd@acme.com", PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getPartyId()).isEqualTo(partyB);
    }

    @Test
    void searchCustomers_blank_returnsEmptyPage() {
        Page<CustomerDTO> page = service().searchCustomers(null, null, PageRequest.of(0, 20));
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }
}
