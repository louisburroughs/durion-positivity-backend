package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.dto.CustomerDTO;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Unit tests for {@link PersonPartyServiceImpl}.
 *
 * <p>
 * Person names live in pos-people, not here (ADR-0015 I2, issue #684), and the
 * consequences of that split are what these tests pin:
 *
 * <ul>
 * <li><b>Sorting by a name property would fail against the local schema</b>, so
 * those sort orders are dropped and the query falls back to customerNumber. A
 * client that sends {@code sort=lastName} gets a working page, not a 500.</li>
 * <li><b>Name and email search delegates to pos-people</b> and maps resolved
 * person ids back to local parties. A directory hit with no local party is not a
 * customer of this shop, so it is skipped rather than fabricated.</li>
 * <li><b>Name edits are not applied locally.</b> {@code updateCustomer} ignores
 * the DTO's names on purpose — writing them here would create a second source of
 * truth that silently diverges from pos-people.</li>
 * <li><b>Every mutation publishes a fact</b>, which is how the order, marketing,
 * and invoicing replicas stay current.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PersonPartyServiceImpl — person parties over a remote name directory")
class PersonPartyServiceImplTest {

    private static final UUID PARTY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID PERSON_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

    @Mock
    private PersonPartyRepository customerRepository;

    @Mock
    private PersonDirectoryService personDirectoryService;

    @Mock
    private CustomerFactPublisher customerFactPublisher;

    private PersonPartyServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PersonPartyServiceImpl(customerRepository, personDirectoryService, customerFactPublisher);
        when(personDirectoryService.fetchPersonIdentitiesQuietly(anyCollection()))
                .thenReturn(Map.of());
    }

    private static PersonParty party(UUID personId) {
        PersonParty party = new PersonParty();
        party.setPartyId(PARTY_ID);
        party.setPersonId(personId);
        party.setCustomerNumber("C-1001");
        party.setPrimaryAddress("1 Main St");
        party.getVehicleVins().add("VIN-1");
        return party;
    }

    private static PersonDirectoryService.PersonIdentity identity(UUID id, String email) {
        return new PersonDirectoryService.PersonIdentity(
                id, "Ada", "Lovelace", email, List.of(new PersonDirectoryService.ContactPoint("EMAIL", email, true)));
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        @DisplayName("resolves names from pos-people rather than from the local row")
        void namesComeFromTheDirectory() {
            when(customerRepository.findById(PARTY_ID)).thenReturn(Optional.of(party(PERSON_ID)));
            when(personDirectoryService.fetchPersonIdentitiesQuietly(Set.of(PERSON_ID)))
                    .thenReturn(Map.of(PERSON_ID, identity(PERSON_ID, "ada@example.com")));

            CustomerDTO dto = service.getCustomerById(PARTY_ID).orElseThrow();

            assertThat(dto.getFirstName()).isEqualTo("Ada");
            assertThat(dto.getLastName()).isEqualTo("Lovelace");
            assertThat(dto.getCustomerNumber()).isEqualTo("C-1001");
            assertThat(dto.getPartyId()).isEqualTo(PARTY_ID);
            assertThat(dto.getVehicleVins()).containsExactly("VIN-1");
            assertThat(dto.getCustomerType()).isEqualTo("PERSON");
        }

        @Test
        @DisplayName("leaves names null on a party not yet linked to a person")
        void unlinkedPartyHasNoNames() {
            when(customerRepository.findById(PARTY_ID)).thenReturn(Optional.of(party(null)));

            CustomerDTO dto = service.getCustomerById(PARTY_ID).orElseThrow();

            // No personId means no directory lookup at all — not an empty-result lookup.
            assertThat(dto.getFirstName()).isNull();
            assertThat(dto.getLastName()).isNull();
            verify(personDirectoryService, never()).fetchPersonIdentitiesQuietly(anyCollection());
        }

        @Test
        @DisplayName("returns empty for an unknown party")
        void unknownPartyIsEmpty() {
            when(customerRepository.findById(PARTY_ID)).thenReturn(Optional.empty());

            assertThat(service.getCustomerById(PARTY_ID)).isEmpty();
            assertThat(service.findPartyById(PARTY_ID)).isEmpty();
        }

        @Test
        @DisplayName("maps every row when listing unpaged")
        void listsAll() {
            when(customerRepository.findAll()).thenReturn(List.of(party(null), party(null)));

            assertThat(service.getAllCustomers()).hasSize(2);
        }

        @Test
        @DisplayName("existsById delegates to the repository")
        void existsDelegates() {
            when(customerRepository.existsById(PARTY_ID)).thenReturn(true);

            assertThat(service.existsById(PARTY_ID)).isTrue();
        }
    }

    @Nested
    @DisplayName("sort sanitization")
    class SortSanitization {

        private Pageable capturedPageable() {
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(customerRepository).findAll(captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("drops name sorts and falls back to customerNumber")
        void nameSortsAreDropped() {
            when(customerRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

            service.getAllCustomers(PageRequest.of(0, 20, Sort.by("lastName", "firstName")));

            // Those properties no longer exist locally; passing them through would fail the query.
            Sort sort = capturedPageable().getSort();
            assertThat(sort.getOrderFor("lastName")).isNull();
            assertThat(sort.getOrderFor("firstName")).isNull();
            assertThat(sort.getOrderFor("customerNumber")).isNotNull();
        }

        @Test
        @DisplayName("keeps a sort on a property that does exist locally")
        void localSortIsPreserved() {
            when(customerRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

            service.getAllCustomers(PageRequest.of(1, 5, Sort.by(Sort.Direction.DESC, "createdAt")));

            Pageable pageable = capturedPageable();
            assertThat(pageable.getPageNumber()).isEqualTo(1);
            assertThat(pageable.getPageSize()).isEqualTo(5);
            assertThat(pageable.getSort().getOrderFor("createdAt")).isNotNull();
            assertThat(pageable.getSort().getOrderFor("customerNumber")).isNull();
        }

        @Test
        @DisplayName("reports the repository's total, not the mapped page size")
        void totalComesFromTheRepository() {
            when(customerRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(party(null)), PageRequest.of(0, 20), 97));

            Page<CustomerDTO> page = service.getAllCustomers(PageRequest.of(0, 20));

            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getTotalElements()).isEqualTo(97);
        }
    }

    @Nested
    @DisplayName("searchCustomers")
    class Search {

        @Test
        @DisplayName("returns an empty page when neither name nor email was supplied")
        void noCriteriaIsEmpty() {
            assertThat(service.searchCustomers(null, "  ", PageRequest.of(0, 10))
                            .getContent())
                    .isEmpty();

            verifyNoInteractions(personDirectoryService, customerRepository);
        }

        @Test
        @DisplayName("searches the directory by name and maps hits back to local parties")
        void searchesByName() {
            when(personDirectoryService.searchPersons("Ada")).thenReturn(List.of(identity(PERSON_ID, "ada@x.com")));
            when(customerRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(party(PERSON_ID)));

            Page<CustomerDTO> page = service.searchCustomers("  Ada  ", null, PageRequest.of(0, 10));

            assertThat(page.getTotalElements()).isEqualTo(1);
            assertThat(page.getContent().getFirst().getFirstName()).isEqualTo("Ada");
            // The identity is reused from the search, so no second directory round trip.
            verify(personDirectoryService, never()).fetchPersonIdentitiesQuietly(anyCollection());
        }

        @Test
        @DisplayName("filters directory hits by email, case-insensitively and by substring")
        void filtersByEmail() {
            // An email-only search sends the email itself as the directory query.
            when(personDirectoryService.searchPersons("ADA@example.com"))
                    .thenReturn(
                            List.of(identity(PERSON_ID, "Ada@Example.com"), identity(UUID.randomUUID(), "bob@x.com")));
            when(customerRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(party(PERSON_ID)));

            Page<CustomerDTO> page = service.searchCustomers(null, "ADA@example.com", PageRequest.of(0, 10));

            assertThat(page.getTotalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("skips a directory hit that is not a customer of this shop")
        void directoryHitWithoutLocalPartyIsSkipped() {
            when(personDirectoryService.searchPersons("Ada")).thenReturn(List.of(identity(PERSON_ID, "ada@x.com")));
            when(customerRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.empty());

            // A person pos-people knows about is not automatically a customer here.
            assertThat(service.searchCustomers("Ada", null, PageRequest.of(0, 10))
                            .getTotalElements())
                    .isZero();
        }

        @Test
        @DisplayName("skips a directory hit with no id rather than looking one up")
        void identityWithoutIdIsSkipped() {
            when(personDirectoryService.searchPersons("Ada")).thenReturn(List.of(identity(null, "ada@x.com")));

            assertThat(service.searchCustomers("Ada", null, PageRequest.of(0, 10))
                            .getTotalElements())
                    .isZero();
            verify(customerRepository, never()).findByPersonId(any());
        }

        @Test
        @DisplayName("pages the in-memory match list without running off the end")
        void pagesTheMatchList() {
            UUID secondPerson = UUID.randomUUID();
            when(personDirectoryService.searchPersons("Ada"))
                    .thenReturn(List.of(identity(PERSON_ID, "a@x.com"), identity(secondPerson, "b@x.com")));
            when(customerRepository.findByPersonId(any())).thenReturn(Optional.of(party(PERSON_ID)));

            Page<CustomerDTO> secondPage = service.searchCustomers("Ada", null, PageRequest.of(1, 1));

            assertThat(secondPage.getContent()).hasSize(1);
            assertThat(secondPage.getTotalElements()).isEqualTo(2);

            // A page past the end is empty rather than an index error.
            assertThat(service.searchCustomers("Ada", null, PageRequest.of(9, 10))
                            .getContent())
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("writes")
    class Writes {

        @Test
        @DisplayName("resolves the canonical person id in pos-people and publishes the change")
        void createResolvesPersonAndPublishes() {
            when(personDirectoryService.resolveOrCreatePersonId(null, null, "Lovelace", "Ada"))
                    .thenReturn(PERSON_ID);
            when(customerRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            CustomerDTO dto = CustomerDTO.builder()
                    .customerNumber("C-1001")
                    .firstName("Ada")
                    .lastName("Lovelace")
                    .primaryAddress("1 Main St")
                    .vehicleVins(List.of("VIN-1"))
                    .customerType("PERSON")
                    .build();

            service.createCustomer(dto);

            ArgumentCaptor<PersonParty> captor = ArgumentCaptor.forClass(PersonParty.class);
            verify(customerRepository).save(captor.capture());
            PersonParty saved = captor.getValue();
            assertThat(saved.getPersonId()).isEqualTo(PERSON_ID);
            assertThat(saved.getCustomerNumber()).isEqualTo("C-1001");
            assertThat(saved.getVehicleVins()).containsExactly("VIN-1");
            verify(customerFactPublisher).partyChanged(saved);
        }

        @Test
        @DisplayName("creates a person party whatever type string arrives, including an unknown one")
        void unknownTypeStillCreatesAPerson() {
            when(customerRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            for (String type : new String[] {null, "", "person", "COMMERCIAL", "NONSENSE"}) {
                assertThat(service.createCustomer(CustomerDTO.builder()
                                .customerType(type)
                                .firstName("Ada")
                                .lastName("Lovelace")
                                .build()))
                        .isNotNull();
            }

            // This service owns person parties only; the type string cannot make it produce another
            // kind, and an unrecognized value must not throw at the API boundary.
            verify(customerRepository, org.mockito.Mockito.times(5)).save(any(PersonParty.class));
        }

        @Test
        @DisplayName("rejects a create with a blank firstName or lastName")
        void createRejectsBlankNames() {
            assertThatThrownBy(() -> service.createCustomer(CustomerDTO.builder()
                            .customerType("PERSON")
                            .firstName("Ada")
                            .build()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.createCustomer(CustomerDTO.builder()
                            .customerType("PERSON")
                            .lastName("Lovelace")
                            .build()))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(customerRepository);
        }

        @Test
        @DisplayName("updates only the fields the DTO supplies and never the names")
        void updateIgnoresNamesAndNulls() {
            PersonParty existing = party(PERSON_ID);
            when(customerRepository.findById(PARTY_ID)).thenReturn(Optional.of(existing));
            when(customerRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            service.updateCustomer(
                    PARTY_ID,
                    CustomerDTO.builder()
                            .firstName("Grace")
                            .lastName("Hopper")
                            .primaryAddress("2 Oak Ave")
                            .build());

            assertThat(existing.getPrimaryAddress()).isEqualTo("2 Oak Ave");
            // customerNumber was null in the DTO, so it is left alone rather than blanked.
            assertThat(existing.getCustomerNumber()).isEqualTo("C-1001");
            // Names are pos-people's to change; writing them here would fork the source of truth.
            assertThat(existing.getPersonId()).isEqualTo(PERSON_ID);
            verify(personDirectoryService, never()).resolveOrCreatePersonId(any(), any(), any(), any());
            verify(customerFactPublisher).partyChanged(existing);
        }

        @Test
        @DisplayName("replaces the VIN set rather than appending to it")
        void vinsAreReplacedOnUpdate() {
            PersonParty existing = party(PERSON_ID);
            when(customerRepository.findById(PARTY_ID)).thenReturn(Optional.of(existing));
            when(customerRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            service.updateCustomer(
                    PARTY_ID,
                    CustomerDTO.builder().vehicleVins(List.of("VIN-2")).build());

            // A vehicle removed by the caller must actually disappear.
            assertThat(existing.getVehicleVins()).containsExactly("VIN-2");
        }

        @Test
        @DisplayName("an update that omits VINs leaves the ones the party had")
        void omittingVinsLeavesThemUnchanged() {
            PersonParty existing = party(PERSON_ID);
            when(customerRepository.findById(PARTY_ID)).thenReturn(Optional.of(existing));
            when(customerRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            service.updateCustomer(
                    PARTY_ID, CustomerDTO.builder().primaryAddress("2 Oak Ave").build());

            // CustomerDTO's vehicleVins has no field initializer, so an absent field in the request
            // body deserializes as null, not an empty list (issue #1245), and updateEntityFromDTO's
            // `vehicleVins != null` guard now actually skips the clear()/addAll() branch on a
            // partial update, matching how the DTO's other optional fields already behave.
            assertThat(existing.getVehicleVins()).containsExactly("VIN-1");
        }

        @Test
        @DisplayName("reports an update to an unknown party as empty without publishing")
        void updateUnknownPartyIsEmpty() {
            when(customerRepository.findById(PARTY_ID)).thenReturn(Optional.empty());

            assertThat(service.updateCustomer(PARTY_ID, CustomerDTO.builder().build()))
                    .isEmpty();

            verifyNoInteractions(customerFactPublisher);
        }

        @Test
        @DisplayName("publishes a deletion fact so downstream replicas drop the party")
        void deletePublishes() {
            PersonParty existing = party(PERSON_ID);
            when(customerRepository.findById(PARTY_ID)).thenReturn(Optional.of(existing));

            assertThat(service.deleteCustomer(PARTY_ID)).isTrue();

            verify(customerRepository).deleteById(PARTY_ID);
            verify(customerFactPublisher).partyDeleted(existing);
        }

        @Test
        @DisplayName("reports a delete of an unknown party as false without publishing")
        void deleteUnknownPartyIsFalse() {
            when(customerRepository.findById(PARTY_ID)).thenReturn(Optional.empty());

            assertThat(service.deleteCustomer(PARTY_ID)).isFalse();

            verify(customerRepository, never()).deleteById(any());
            verifyNoInteractions(customerFactPublisher);
        }
    }
}
