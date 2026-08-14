package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.dto.CustomerDTO;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * Unit tests for {@link CommercialPartyServiceImpl}.
 *
 * <p>
 * Commercial parties keep their names locally — there is no pos-people identity
 * behind an organization — so this service is the simpler sibling of
 * {@link PersonPartyServiceImpl}. What it does have is a
 * {@link CustomerDTO}-shaped API that carries organization names in
 * person-shaped fields: {@code lastName} holds the legal/registered name and
 * {@code firstName} holds the display name, symmetrically on both read and
 * write; see {@link Mapping#roundTripPreservesTheNameFields()}.
 *
 * <p>
 * Commercial parties hold no locally searchable email, so an email-only query
 * returns an empty page rather than scanning every row.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CommercialPartyServiceImpl — organization parties")
class CommercialPartyServiceImplTest {

    private static final UUID PARTY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    @Mock
    private CommercialPartyRepository commercialRepository;

    @Mock
    private CustomerFactPublisher customerFactPublisher;

    private CommercialPartyServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CommercialPartyServiceImpl(commercialRepository, customerFactPublisher);
    }

    private static CommercialParty party() {
        CommercialParty party = new CommercialParty();
        party.setPartyId(PARTY_ID);
        party.setCustomerNumber("C-2001");
        party.setLegalName("Fleet Co LLC");
        party.setDisplayName("Fleet Co");
        party.setPrimaryAddress("1 Depot Rd");
        party.getVehicleVins().add("VIN-1");
        return party;
    }

    @Nested
    @DisplayName("mapping")
    class Mapping {

        @Test
        @DisplayName("reads the legal name into lastName and the display name into firstName")
        void readMapping() {
            when(commercialRepository.findById(PARTY_ID)).thenReturn(Optional.of(party()));

            CustomerDTO dto = service.getCustomerById(PARTY_ID).orElseThrow();

            assertThat(dto.getLastName()).isEqualTo("Fleet Co LLC");
            assertThat(dto.getFirstName()).isEqualTo("Fleet Co");
            assertThat(dto.getCustomerType()).isEqualTo("COMMERCIAL");
            assertThat(dto.getPartyId()).isEqualTo(PARTY_ID);
            assertThat(dto.getVehicleVins()).containsExactly("VIN-1");
        }

        @Test
        @DisplayName("writes lastName to the legal name and firstName to the display name")
        void writeMapping() {
            when(commercialRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            service.createCustomer(CustomerDTO.builder()
                    .customerType("COMMERCIAL")
                    .lastName("Fleet Co LLC")
                    .firstName("Fleet Co")
                    .build());

            ArgumentCaptor<CommercialParty> captor = ArgumentCaptor.forClass(CommercialParty.class);
            verify(commercialRepository).save(captor.capture());
            assertThat(captor.getValue().getLegalName()).isEqualTo("Fleet Co LLC");
            assertThat(captor.getValue().getDisplayName()).isEqualTo("Fleet Co");
        }

        @Test
        @DisplayName("a create-then-read round trip preserves both name fields")
        void roundTripPreservesTheNameFields() {
            when(commercialRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            CustomerDTO created = service.createCustomer(CustomerDTO.builder()
                    .customerType("COMMERCIAL")
                    .lastName("Fleet Co LLC")
                    .firstName("Fleet Co")
                    .build());

            // toEntity now maps lastName -> legalName and firstName -> displayName, agreeing with
            // toDTO's legalName -> lastName / displayName -> firstName. updateEntityFromDTO follows
            // toEntity, so the same agreement holds on update (issue #1246).
            assertThat(created.getLastName()).isEqualTo("Fleet Co LLC");
            assertThat(created.getFirstName()).isEqualTo("Fleet Co");
        }
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        @DisplayName("lists commercial parties paged and unpaged through both accessor pairs")
        void listAccessors() {
            when(commercialRepository.findAll()).thenReturn(List.of(party(), party()));
            when(commercialRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(party()), PageRequest.of(0, 20), 31));

            assertThat(service.getAllCustomers()).hasSize(2);
            assertThat(service.getAllCommercialCustomers()).hasSize(2);
            assertThat(service.getAllCustomers(PageRequest.of(0, 20)).getTotalElements())
                    .isEqualTo(31);
            assertThat(service.getAllCommercialCustomers(PageRequest.of(0, 20)).getTotalElements())
                    .isEqualTo(31);
        }

        @Test
        @DisplayName("returns empty for an unknown party")
        void unknownPartyIsEmpty() {
            when(commercialRepository.findById(PARTY_ID)).thenReturn(Optional.empty());

            assertThat(service.getCustomerById(PARTY_ID)).isEmpty();
            assertThat(service.findPartyById(PARTY_ID)).isEmpty();
        }

        @Test
        @DisplayName("existsById delegates to the repository")
        void existsDelegates() {
            when(commercialRepository.existsById(PARTY_ID)).thenReturn(true);

            assertThat(service.existsById(PARTY_ID)).isTrue();
        }
    }

    @Nested
    @DisplayName("searchCustomers")
    class Search {

        @Test
        @DisplayName("returns an empty page for an email-only query, since no email is held locally")
        void emailOnlyQueryIsEmpty() {
            assertThat(service.searchCustomers(null, "fleet@example.com", PageRequest.of(0, 10))
                            .getContent())
                    .isEmpty();

            // Not a full scan filtered to nothing — the repository is never touched.
            verifyNoInteractions(commercialRepository);
        }

        @Test
        @DisplayName("searches by trimmed legal name")
        void searchesByLegalName() {
            when(commercialRepository.findByLegalNameContaining("Fleet")).thenReturn(List.of(party()));

            Page<CustomerDTO> page = service.searchCustomers("  Fleet  ", null, PageRequest.of(0, 10));

            assertThat(page.getTotalElements()).isEqualTo(1);
            assertThat(page.getContent().getFirst().getLastName()).isEqualTo("Fleet Co LLC");
        }

        @Test
        @DisplayName("pages the match list without running off the end")
        void pagesTheMatchList() {
            when(commercialRepository.findByLegalNameContaining("Fleet")).thenReturn(List.of(party(), party()));

            assertThat(service.searchCustomers("Fleet", null, PageRequest.of(1, 1))
                            .getContent())
                    .hasSize(1);
            assertThat(service.searchCustomers("Fleet", null, PageRequest.of(9, 10))
                            .getContent())
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("writes")
    class Writes {

        @Test
        @DisplayName("publishes a fact on create so downstream replicas see the new party")
        void createPublishes() {
            when(commercialRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            service.createCustomer(CustomerDTO.builder()
                    .customerType("COMMERCIAL")
                    .customerNumber("C-2001")
                    .lastName("Fleet Co LLC")
                    .firstName("Fleet Co")
                    .primaryAddress("1 Depot Rd")
                    .vehicleVins(List.of("VIN-1"))
                    .build());

            ArgumentCaptor<CommercialParty> captor = ArgumentCaptor.forClass(CommercialParty.class);
            verify(commercialRepository).save(captor.capture());
            assertThat(captor.getValue().getCustomerNumber()).isEqualTo("C-2001");
            assertThat(captor.getValue().getVehicleVins()).containsExactly("VIN-1");
            verify(customerFactPublisher).partyChanged(captor.getValue());
        }

        @Test
        @DisplayName("creates a commercial party whatever type string arrives, including an unknown one")
        void unknownTypeStillCreatesCommercial() {
            when(commercialRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            for (String type : new String[] {null, "", "commercial", "PERSON", "NONSENSE"}) {
                assertThat(service.createCustomer(CustomerDTO.builder()
                                .customerType(type)
                                .lastName("Fleet Co LLC")
                                .firstName("Fleet Co")
                                .build()))
                        .isNotNull();
            }

            // This service owns commercial parties only, and an unrecognized type string must not
            // throw at the API boundary.
            verify(commercialRepository, org.mockito.Mockito.times(5)).save(any(CommercialParty.class));
        }

        @Test
        @DisplayName("rejects a create with a blank firstName or lastName")
        void createRejectsBlankNames() {
            assertThatThrownBy(() -> service.createCustomer(CustomerDTO.builder()
                            .customerType("COMMERCIAL")
                            .firstName("Fleet Co")
                            .build()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.createCustomer(CustomerDTO.builder()
                            .customerType("COMMERCIAL")
                            .lastName("Fleet Co LLC")
                            .build()))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(commercialRepository);
        }

        @Test
        @DisplayName("updates only the fields the DTO supplies")
        void updateIgnoresNulls() {
            CommercialParty existing = party();
            when(commercialRepository.findById(PARTY_ID)).thenReturn(Optional.of(existing));
            when(commercialRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            service.updateCustomer(
                    PARTY_ID, CustomerDTO.builder().primaryAddress("2 Depot Rd").build());

            assertThat(existing.getPrimaryAddress()).isEqualTo("2 Depot Rd");
            assertThat(existing.getCustomerNumber()).isEqualTo("C-2001");
            assertThat(existing.getLegalName()).isEqualTo("Fleet Co LLC");
            verify(customerFactPublisher).partyChanged(existing);
        }

        @Test
        @DisplayName("an update that omits VINs leaves the ones the party had")
        void omittingVinsLeavesThemUnchanged() {
            CommercialParty existing = party();
            when(commercialRepository.findById(PARTY_ID)).thenReturn(Optional.of(existing));
            when(commercialRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            service.updateCustomer(
                    PARTY_ID, CustomerDTO.builder().primaryAddress("2 Depot Rd").build());

            // CustomerDTO's vehicleVins has no field initializer, so an absent field in the request
            // body deserializes as null, not an empty list (issue #1245). The `!= null` guard in
            // updateEntityFromDTO now actually skips the clear()/addAll() branch on a partial update.
            assertThat(existing.getVehicleVins()).containsExactly("VIN-1");
        }

        @Test
        @DisplayName("reports an update to an unknown party as empty without publishing")
        void updateUnknownPartyIsEmpty() {
            when(commercialRepository.findById(PARTY_ID)).thenReturn(Optional.empty());

            assertThat(service.updateCustomer(PARTY_ID, CustomerDTO.builder().build()))
                    .isEmpty();

            verifyNoInteractions(customerFactPublisher);
        }

        @Test
        @DisplayName("publishes a deletion fact so downstream replicas drop the party")
        void deletePublishes() {
            CommercialParty existing = party();
            when(commercialRepository.findById(PARTY_ID)).thenReturn(Optional.of(existing));

            assertThat(service.deleteCustomer(PARTY_ID)).isTrue();

            verify(commercialRepository).deleteById(PARTY_ID);
            verify(customerFactPublisher).partyDeleted(existing);
        }

        @Test
        @DisplayName("reports a delete of an unknown party as false without publishing")
        void deleteUnknownPartyIsFalse() {
            when(commercialRepository.findById(PARTY_ID)).thenReturn(Optional.empty());

            assertThat(service.deleteCustomer(PARTY_ID)).isFalse();

            verify(commercialRepository, never()).deleteById(any());
            verifyNoInteractions(customerFactPublisher);
        }
    }
}
