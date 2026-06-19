package com.positivity.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.dto.CustomerDTO;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.enums.PartyType;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.service.CommercialPartyServiceImpl;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class CommercialPartyServiceImplSearchTest {

    @Mock
    private CommercialPartyRepository commercialRepository;

    private CommercialPartyServiceImpl service() {
        return new CommercialPartyServiceImpl(commercialRepository);
    }

    private CommercialParty party(UUID id, String legalName) {
        CommercialParty p = new CommercialParty();
        p.setPartyId(id);
        p.setPartyNumber("PARTY-" + id.toString().substring(0, 8));
        p.setCustomerNumber("CUST-" + id.toString().substring(0, 8));
        p.setLegalName(legalName);
        p.setDisplayName("Acme");
        p.setPartyType(PartyType.COMMERCIAL);
        p.setVehicleVins(new HashSet<>());
        return p;
    }

    @Test
    void searchCustomers_byName_returnsMatch_withPartyIdEqualToId() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000111");
        when(commercialRepository.findByLegalNameContaining("acme")).thenReturn(List.of(party(id, "Acme Corp")));

        Page<CustomerDTO> page = service().searchCustomers("acme", null, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        CustomerDTO dto = page.getContent().get(0);
        assertThat(dto.getId()).isEqualTo(id);
        assertThat(dto.getPartyId()).isEqualTo(id);
        assertThat(dto.getLastName()).isEqualTo("Acme Corp");
    }

    @Test
    void searchCustomers_emailOnly_returnsEmptyPage() {
        Page<CustomerDTO> page = service().searchCustomers(null, "x@acme.com", PageRequest.of(0, 20));
        assertThat(page.getTotalElements()).isZero();
        assertThat(page.getContent()).isEmpty();
    }

    @Test
    void searchCustomers_blankName_returnsEmptyPage() {
        Page<CustomerDTO> page = service().searchCustomers("  ", null, PageRequest.of(0, 20));
        assertThat(page.getContent()).isEmpty();
    }
}
