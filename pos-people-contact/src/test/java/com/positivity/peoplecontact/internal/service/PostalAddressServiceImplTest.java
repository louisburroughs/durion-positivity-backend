package com.positivity.peoplecontact.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.peoplecontact.internal.dto.PostalAddressDto;
import com.positivity.peoplecontact.internal.entity.PartyPostalAddress;
import com.positivity.peoplecontact.internal.entity.Person;
import com.positivity.peoplecontact.internal.enums.PartyType;
import com.positivity.peoplecontact.internal.exception.PersonNotFoundException;
import com.positivity.peoplecontact.internal.exception.SemanticValidationException;
import com.positivity.peoplecontact.internal.repository.PartyPostalAddressRepository;
import com.positivity.peoplecontact.internal.repository.PersonContactPointRepository;
import com.positivity.peoplecontact.internal.repository.PersonRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link PostalAddressServiceImpl} (FI-4, #1135): international validation
 * (ISO 3166-1 country code, no country-specific postal rules) and the per-party-kind fact
 * emission split.
 */
class PostalAddressServiceImplTest {

    private static final UUID PERSON_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final PartyPostalAddressRepository addressRepository = mock(PartyPostalAddressRepository.class);
    private final PersonRepository personRepository = mock(PersonRepository.class);
    private final PersonContactPointRepository contactPointRepository = mock(PersonContactPointRepository.class);
    private final PeopleContactEventPublisher eventPublisher = mock(PeopleContactEventPublisher.class);

    private final PostalAddressServiceImpl service =
            new PostalAddressServiceImpl(addressRepository, personRepository, contactPointRepository, eventPublisher);

    private static PostalAddressDto address(String countryCode) {
        return PostalAddressDto.builder()
                .line1("  1-2-3 Ginza ")
                .line2("  ")
                .city("Tokyo")
                .region("Tokyo")
                .postalCode("104-0061")
                .countryCode(countryCode)
                .build();
    }

    @Test
    @DisplayName("Stores a person address normalized: trimmed fields, upper-case ISO country")
    void storesPersonAddressNormalized() {
        when(personRepository.existsById(PERSON_ID)).thenReturn(true);
        when(addressRepository.findByPartyTypeAndPartyId(PartyType.PERSON, PERSON_ID))
                .thenReturn(Optional.empty());
        when(addressRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(personRepository.findById(PERSON_ID)).thenReturn(Optional.of(new Person()));
        when(contactPointRepository.findByPersonId(PERSON_ID)).thenReturn(List.of());

        PostalAddressDto stored = service.putAddress(PartyType.PERSON, PERSON_ID, address("jp"));

        assertThat(stored.getLine1()).isEqualTo("1-2-3 Ginza");
        assertThat(stored.getLine2()).isNull();
        assertThat(stored.getCountryCode()).isEqualTo("JP");
        ArgumentCaptor<PartyPostalAddress> saved = ArgumentCaptor.forClass(PartyPostalAddress.class);
        verify(addressRepository).save(saved.capture());
        assertThat(saved.getValue().getPartyType()).isEqualTo(PartyType.PERSON);
        assertThat(saved.getValue().getPartyId()).isEqualTo(PERSON_ID);
        // Person address changes re-emit the full identity snapshot, not an org-address fact.
        verify(eventPublisher).publishPersonUpdated(any(), anyList(), any());
        verify(eventPublisher, never()).publishOrganizationAddressUpdated(any());
    }

    @Test
    @DisplayName("Postal code stays optional — countries without one are storable")
    void allowsMissingPostalCode() {
        when(personRepository.existsById(PERSON_ID)).thenReturn(true);
        when(addressRepository.findByPartyTypeAndPartyId(PartyType.PERSON, PERSON_ID))
                .thenReturn(Optional.empty());
        when(addressRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(personRepository.findById(PERSON_ID)).thenReturn(Optional.of(new Person()));
        when(contactPointRepository.findByPersonId(PERSON_ID)).thenReturn(List.of());

        PostalAddressDto noPostal = PostalAddressDto.builder()
                .line1("12 Nnamdi Azikiwe Street")
                .city("Aba")
                .region("Abia")
                .countryCode("NG")
                .build();

        assertThat(service.putAddress(PartyType.PERSON, PERSON_ID, noPostal).getPostalCode())
                .isNull();
    }

    @Test
    @DisplayName("Rejects a country code that is not ISO 3166-1 alpha-2")
    void rejectsUnknownCountryCode() {
        assertThatThrownBy(() -> service.putAddress(PartyType.PERSON, PERSON_ID, address("ZZ")))
                .isInstanceOf(SemanticValidationException.class)
                .hasMessageContaining("ISO 3166-1");
        assertThatThrownBy(() -> service.putAddress(PartyType.PERSON, PERSON_ID, address(null)))
                .isInstanceOf(SemanticValidationException.class);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("Rejects a person address for a person that does not exist")
    void rejectsUnknownPerson() {
        when(personRepository.existsById(PERSON_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.putAddress(PartyType.PERSON, PERSON_ID, address("US")))
                .isInstanceOf(PersonNotFoundException.class);
        verify(addressRepository, never()).save(any());
    }

    @Test
    @DisplayName("Stores an organization address verbatim by external party id and emits the org fact")
    void storesOrganizationAddress() {
        when(addressRepository.findByPartyTypeAndPartyId(PartyType.ORGANIZATION, ORG_ID))
                .thenReturn(Optional.empty());
        when(addressRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.putAddress(PartyType.ORGANIZATION, ORG_ID, address("DE"));

        // No person existence check for organizations: the id is an external party reference.
        verify(personRepository, never()).existsById(any());
        verify(eventPublisher).publishOrganizationAddressUpdated(any(PartyPostalAddress.class));
        verify(eventPublisher, never()).publishPersonUpdated(any(), anyList(), any());
    }

    @Test
    @DisplayName("Replacing an existing address updates the same row instead of inserting a second one")
    void replacesExistingAddress() {
        PartyPostalAddress existing = PartyPostalAddress.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
                .partyType(PartyType.ORGANIZATION)
                .partyId(ORG_ID)
                .line1("old street")
                .countryCode("US")
                .build();
        when(addressRepository.findByPartyTypeAndPartyId(PartyType.ORGANIZATION, ORG_ID))
                .thenReturn(Optional.of(existing));
        when(addressRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.putAddress(PartyType.ORGANIZATION, ORG_ID, address("FR"));

        ArgumentCaptor<PartyPostalAddress> saved = ArgumentCaptor.forClass(PartyPostalAddress.class);
        verify(addressRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(existing.getId());
        assertThat(saved.getValue().getCountryCode()).isEqualTo("FR");
    }

    @Test
    @DisplayName("Deleting an organization address emits the removed fact; a person delete re-emits the snapshot")
    void deleteEmitsMatchingFact() {
        when(addressRepository.findByPartyTypeAndPartyId(PartyType.ORGANIZATION, ORG_ID))
                .thenReturn(Optional.of(PartyPostalAddress.builder().build()));
        service.deleteAddress(PartyType.ORGANIZATION, ORG_ID);
        verify(addressRepository).deleteByPartyTypeAndPartyId(PartyType.ORGANIZATION, ORG_ID);
        verify(eventPublisher).publishOrganizationAddressRemoved(ORG_ID);

        when(addressRepository.findByPartyTypeAndPartyId(PartyType.PERSON, PERSON_ID))
                .thenReturn(Optional.of(PartyPostalAddress.builder().build()));
        when(personRepository.findById(PERSON_ID)).thenReturn(Optional.of(new Person()));
        when(contactPointRepository.findByPersonId(PERSON_ID)).thenReturn(List.of());
        service.deleteAddress(PartyType.PERSON, PERSON_ID);
        verify(eventPublisher).publishPersonUpdated(any(), anyList(), eq(null));
    }

    @Test
    @DisplayName("Deleting a non-existent address is a no-op with no fact emitted")
    void deleteWithoutAddressIsNoop() {
        when(addressRepository.findByPartyTypeAndPartyId(PartyType.ORGANIZATION, ORG_ID))
                .thenReturn(Optional.empty());

        service.deleteAddress(PartyType.ORGANIZATION, ORG_ID);

        verify(addressRepository, never()).deleteByPartyTypeAndPartyId(any(), any());
        verifyNoInteractions(eventPublisher);
    }
}
