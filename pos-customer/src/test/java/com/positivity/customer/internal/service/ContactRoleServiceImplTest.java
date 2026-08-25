package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.dto.GetContactsWithRolesResponse;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.ContactRole;
import com.positivity.customer.internal.entity.ContactRoleAssignment;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.ContactRoleAssignmentRepository;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link ContactRoleServiceImpl#getContactsWithRoles}.
 *
 * <p>
 * The endpoint's display fields are all optional projections of the pos-people-backed
 * {@link PersonDirectoryService.PersonIdentity} (ADR-0015 I2, issue #684): contact name, primary
 * email, and primary phone each fall back to {@code null}/{@code false} independently, so the
 * three ternaries are pinned separately rather than only in combination. The person lookup that
 * gates whether a contact is even added to the response is a presence guard only — its result is
 * discarded — which makes it easy to invert or drop while refactoring; a contact whose person
 * record has disappeared from pos-customer must never surface with an empty name instead of being
 * omitted.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContactRoleServiceImpl — getContactsWithRoles")
class ContactRoleServiceImplTest {

    private static final UUID PARTY_ID = UUID.randomUUID();
    private static final UUID CONTACT_ID = UUID.randomUUID();

    @Mock
    private ContactRoleAssignmentRepository roleAssignmentRepository;

    @Mock
    private CommercialPartyRepository partyRepository;

    @Mock
    private PersonPartyRepository personRepository;

    @Mock
    private PersonDirectoryService personDirectoryService;

    private ContactRoleServiceImpl sut;

    @BeforeEach
    void setUp() {
        sut = new ContactRoleServiceImpl(
                roleAssignmentRepository, partyRepository, personRepository, personDirectoryService);
    }

    private static ContactRoleAssignment assignment(UUID contactId, ContactRole role, boolean primary) {
        return ContactRoleAssignment.builder()
                .contactId(contactId)
                .customerAccountId(PARTY_ID)
                .roleName(role)
                .primary(primary)
                .build();
    }

    private static PersonParty person(UUID personId) {
        PersonParty person = new PersonParty();
        person.setPersonId(personId);
        return person;
    }

    private void givenPartyExists() {
        when(partyRepository.findById(PARTY_ID)).thenReturn(Optional.of(new CommercialParty()));
    }

    private void givenContactResolvesToPerson(UUID contactId) {
        when(personRepository.findByPersonId(contactId)).thenReturn(Optional.of(person(contactId)));
    }

    @Nested
    @DisplayName("party lookup")
    class PartyLookup {

        @Test
        @DisplayName("rejects an unknown party without querying role assignments")
        void unknownParty_throwsNotFound() {
            when(partyRepository.findById(PARTY_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.getContactsWithRoles(PARTY_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Party not found: " + PARTY_ID);
            verify(roleAssignmentRepository, never()).findByCustomerAccountId(any());
        }

        @Test
        @DisplayName("returns an empty contact list for a party with no role assignments")
        void noAssignments_returnsEmptyContacts() {
            givenPartyExists();
            when(roleAssignmentRepository.findByCustomerAccountId(PARTY_ID)).thenReturn(List.of());
            when(personDirectoryService.fetchPersonIdentitiesQuietly(Set.of())).thenReturn(Map.of());

            GetContactsWithRolesResponse response = sut.getContactsWithRoles(PARTY_ID);

            assertThat(response.getPartyId()).isEqualTo(PARTY_ID.toString());
            assertThat(response.getContacts()).isEmpty();
        }
    }

    @Nested
    @DisplayName("person presence guard")
    class PersonPresenceGuard {

        @Test
        @DisplayName("drops a contact whose person record no longer exists in pos-customer")
        void contactWithoutPersonRecord_isOmitted() {
            givenPartyExists();
            when(roleAssignmentRepository.findByCustomerAccountId(PARTY_ID))
                    .thenReturn(List.of(assignment(CONTACT_ID, ContactRole.BILLING, true)));
            when(personDirectoryService.fetchPersonIdentitiesQuietly(Set.of(CONTACT_ID)))
                    .thenReturn(Map.of());
            // No person row for CONTACT_ID: the guard must skip it rather than emit a stub contact.
            when(personRepository.findByPersonId(CONTACT_ID)).thenReturn(Optional.empty());

            GetContactsWithRolesResponse response = sut.getContactsWithRoles(PARTY_ID);

            assertThat(response.getContacts()).isEmpty();
        }
    }

    @Nested
    @DisplayName("contact name projection")
    class ContactNameProjection {

        @Test
        @DisplayName("uses the pos-people display name when the identity resolves")
        void identityPresent_usesDisplayName() {
            givenPartyExists();
            givenContactResolvesToPerson(CONTACT_ID);
            when(roleAssignmentRepository.findByCustomerAccountId(PARTY_ID))
                    .thenReturn(List.of(assignment(CONTACT_ID, ContactRole.BILLING, true)));
            when(personDirectoryService.fetchPersonIdentitiesQuietly(Set.of(CONTACT_ID)))
                    .thenReturn(Map.of(
                            CONTACT_ID,
                            new PersonDirectoryService.PersonIdentity(
                                    CONTACT_ID, "Jane", "Doe", "jane@example.com", List.of())));

            var contact = sut.getContactsWithRoles(PARTY_ID).getContacts().get(0);

            assertThat(contact.getContactName()).isEqualTo("Jane Doe");
        }

        @Test
        @DisplayName("falls back to null when no identity was found for the contact")
        void identityAbsent_nameIsNull() {
            givenPartyExists();
            givenContactResolvesToPerson(CONTACT_ID);
            when(roleAssignmentRepository.findByCustomerAccountId(PARTY_ID))
                    .thenReturn(List.of(assignment(CONTACT_ID, ContactRole.BILLING, true)));
            // Batched lookup can legitimately omit ids it has nothing for (fetchPersonIdentities Javadoc).
            when(personDirectoryService.fetchPersonIdentitiesQuietly(Set.of(CONTACT_ID)))
                    .thenReturn(Map.of());

            var contact = sut.getContactsWithRoles(PARTY_ID).getContacts().get(0);

            assertThat(contact.getContactName()).isNull();
        }

        @Test
        @DisplayName("falls back to null when the identity resolves but has no name on file")
        void identityBlankName_nameIsNull() {
            givenPartyExists();
            givenContactResolvesToPerson(CONTACT_ID);
            when(roleAssignmentRepository.findByCustomerAccountId(PARTY_ID))
                    .thenReturn(List.of(assignment(CONTACT_ID, ContactRole.BILLING, true)));
            when(personDirectoryService.fetchPersonIdentitiesQuietly(Set.of(CONTACT_ID)))
                    .thenReturn(Map.of(
                            CONTACT_ID,
                            new PersonDirectoryService.PersonIdentity(CONTACT_ID, null, null, null, List.of())));

            var contact = sut.getContactsWithRoles(PARTY_ID).getContacts().get(0);

            assertThat(contact.getContactName()).isNull();
        }
    }

    @Nested
    @DisplayName("primary email/phone projection")
    class ContactMethodProjection {

        @Test
        @DisplayName("sets email and hasPrimaryEmail when the identity carries an email")
        void emailPresent_setsEmailAndFlag() {
            givenPartyExists();
            givenContactResolvesToPerson(CONTACT_ID);
            when(roleAssignmentRepository.findByCustomerAccountId(PARTY_ID))
                    .thenReturn(List.of(assignment(CONTACT_ID, ContactRole.BILLING, true)));
            when(personDirectoryService.fetchPersonIdentitiesQuietly(Set.of(CONTACT_ID)))
                    .thenReturn(Map.of(
                            CONTACT_ID,
                            new PersonDirectoryService.PersonIdentity(
                                    CONTACT_ID, "Jane", "Doe", "jane@example.com", List.of())));

            var contact = sut.getContactsWithRoles(PARTY_ID).getContacts().get(0);

            assertThat(contact.getEmail()).isEqualTo("jane@example.com");
            assertThat(contact.getHasPrimaryEmail()).isTrue();
        }

        @Test
        @DisplayName("leaves email null and hasPrimaryEmail false when the identity has none")
        void emailAbsent_leavesEmailNullAndFlagFalse() {
            givenPartyExists();
            givenContactResolvesToPerson(CONTACT_ID);
            when(roleAssignmentRepository.findByCustomerAccountId(PARTY_ID))
                    .thenReturn(List.of(assignment(CONTACT_ID, ContactRole.BILLING, true)));
            when(personDirectoryService.fetchPersonIdentitiesQuietly(Set.of(CONTACT_ID)))
                    .thenReturn(Map.of(
                            CONTACT_ID,
                            new PersonDirectoryService.PersonIdentity(CONTACT_ID, "Jane", "Doe", null, List.of())));

            var contact = sut.getContactsWithRoles(PARTY_ID).getContacts().get(0);

            assertThat(contact.getEmail()).isNull();
            assertThat(contact.getHasPrimaryEmail()).isFalse();
        }

        @Test
        @DisplayName("sets phone when the identity carries a typed PHONE contact point")
        void phonePresent_setsPhone() {
            givenPartyExists();
            givenContactResolvesToPerson(CONTACT_ID);
            when(roleAssignmentRepository.findByCustomerAccountId(PARTY_ID))
                    .thenReturn(List.of(assignment(CONTACT_ID, ContactRole.BILLING, true)));
            when(personDirectoryService.fetchPersonIdentitiesQuietly(Set.of(CONTACT_ID)))
                    .thenReturn(Map.of(
                            CONTACT_ID,
                            new PersonDirectoryService.PersonIdentity(
                                    CONTACT_ID,
                                    "Jane",
                                    "Doe",
                                    null,
                                    List.of(new PersonDirectoryService.ContactPoint(
                                            "PHONE_MOBILE", "+1-555-0142", true)))));

            var contact = sut.getContactsWithRoles(PARTY_ID).getContacts().get(0);

            assertThat(contact.getPhone()).isEqualTo("+1-555-0142");
        }

        @Test
        @DisplayName("leaves phone null when the identity has no phone contact point")
        void phoneAbsent_leavesPhoneNull() {
            givenPartyExists();
            givenContactResolvesToPerson(CONTACT_ID);
            when(roleAssignmentRepository.findByCustomerAccountId(PARTY_ID))
                    .thenReturn(List.of(assignment(CONTACT_ID, ContactRole.BILLING, true)));
            when(personDirectoryService.fetchPersonIdentitiesQuietly(Set.of(CONTACT_ID)))
                    .thenReturn(Map.of(
                            CONTACT_ID,
                            new PersonDirectoryService.PersonIdentity(CONTACT_ID, "Jane", "Doe", null, List.of())));

            var contact = sut.getContactsWithRoles(PARTY_ID).getContacts().get(0);

            assertThat(contact.getPhone()).isNull();
        }
    }

    @Nested
    @DisplayName("role mapping and grouping")
    class RoleMappingAndGrouping {

        @Test
        @DisplayName("maps every assignment for a contact to its role code, label, and primary flag")
        void mapsAllRolesForAContact() {
            givenPartyExists();
            givenContactResolvesToPerson(CONTACT_ID);
            when(roleAssignmentRepository.findByCustomerAccountId(PARTY_ID))
                    .thenReturn(List.of(
                            assignment(CONTACT_ID, ContactRole.BILLING, true),
                            assignment(CONTACT_ID, ContactRole.TECHNICAL, false)));
            when(personDirectoryService.fetchPersonIdentitiesQuietly(Set.of(CONTACT_ID)))
                    .thenReturn(Map.of());

            var roles = sut.getContactsWithRoles(PARTY_ID).getContacts().get(0).getRoles();

            assertThat(roles).hasSize(2);
            assertThat(roles)
                    .extracting(GetContactsWithRolesResponse.AssignedRole::getRoleCode)
                    .containsExactlyInAnyOrder("BILLING", "TECHNICAL");
            assertThat(roles)
                    .filteredOn(r -> "BILLING".equals(r.getRoleCode()))
                    .extracting(GetContactsWithRolesResponse.AssignedRole::getRoleLabel)
                    .containsExactly(ContactRole.BILLING.getLabel());
            assertThat(roles)
                    .filteredOn(r -> "BILLING".equals(r.getRoleCode()))
                    .extracting(GetContactsWithRolesResponse.AssignedRole::getIsPrimary)
                    .containsExactly(true);
            assertThat(roles)
                    .filteredOn(r -> "TECHNICAL".equals(r.getRoleCode()))
                    .extracting(GetContactsWithRolesResponse.AssignedRole::getIsPrimary)
                    .containsExactly(false);
        }

        @Test
        @DisplayName("groups assignments by contact so each resolvable contact appears once")
        void groupsAssignmentsByContact() {
            UUID secondContactId = UUID.randomUUID();
            givenPartyExists();
            givenContactResolvesToPerson(CONTACT_ID);
            givenContactResolvesToPerson(secondContactId);
            when(roleAssignmentRepository.findByCustomerAccountId(PARTY_ID))
                    .thenReturn(List.of(
                            assignment(CONTACT_ID, ContactRole.BILLING, true),
                            assignment(secondContactId, ContactRole.OPERATIONS, true)));
            when(personDirectoryService.fetchPersonIdentitiesQuietly(Set.of(CONTACT_ID, secondContactId)))
                    .thenReturn(Map.of());

            GetContactsWithRolesResponse response = sut.getContactsWithRoles(PARTY_ID);

            assertThat(response.getContacts())
                    .extracting(GetContactsWithRolesResponse.ContactWithRoles::getContactId)
                    .containsExactlyInAnyOrder(CONTACT_ID.toString(), secondContactId.toString());
        }
    }
}
