package com.positivity.platformsender.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.platformsender.internal.entity.ExtCustomerPersonParty;
import com.positivity.platformsender.internal.entity.ExtPeopleContactPerson;
import com.positivity.platformsender.internal.enums.MessageChannel;
import com.positivity.platformsender.internal.repository.ExtCustomerPersonPartyRepository;
import com.positivity.platformsender.internal.repository.ExtPeopleContactPersonRepository;
import com.positivity.platformsender.internal.service.RecipientAddressResolver.Unresolved;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RecipientAddressResolver — party to person to address, from the replicas only")
class RecipientAddressResolverTest {

    private static final UUID ACCOUNT_PARTY = UUID.fromString("01990000-0000-7000-8000-00000000a001");
    private static final UUID CONTACT_PARTY = UUID.fromString("01990000-0000-7000-8000-00000000a002");
    private static final UUID PERSON = UUID.fromString("01990000-0000-7000-8000-00000000b001");

    private final ExtCustomerPersonPartyRepository parties = mock(ExtCustomerPersonPartyRepository.class);
    private final ExtPeopleContactPersonRepository persons = mock(ExtPeopleContactPersonRepository.class);
    private RecipientAddressResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new RecipientAddressResolver(parties, persons, TestSenderProperties.defaults());
        when(parties.findById(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
    }

    private void personParty(UUID partyId, UUID personId) {
        when(parties.findById(partyId))
                .thenReturn(Optional.of(ExtCustomerPersonParty.builder()
                        .partyId(partyId)
                        .personId(personId)
                        .build()));
    }

    private void person(String email, String mobile) {
        when(persons.findById(PERSON))
                .thenReturn(Optional.of(ExtPeopleContactPerson.builder()
                        .personId(PERSON)
                        .email(email)
                        .mobilePhone(mobile)
                        .build()));
    }

    @Test
    @DisplayName("a commercial account's message goes to the contact person the caller named")
    void contactIdWinsForCommercialAccount() {
        personParty(CONTACT_PARTY, PERSON);
        person("Buyer@Fleet.example", "+1 555 010 0100");

        assertThat(resolver.resolve(MessageChannel.EMAIL, ACCOUNT_PARTY, CONTACT_PARTY)
                        .address())
                .isEqualTo("buyer@fleet.example");
        assertThat(resolver.resolve(MessageChannel.SMS, ACCOUNT_PARTY, CONTACT_PARTY)
                        .address())
                .isEqualTo("+15550100100");
    }

    @Test
    @DisplayName("with no contactId, the recipient resolves when it is itself a person party")
    void fallsBackToRecipientParty() {
        personParty(ACCOUNT_PARTY, PERSON);
        person("ada@example.com", null);

        assertThat(resolver.resolve(MessageChannel.EMAIL, ACCOUNT_PARTY, null).address())
                .isEqualTo("ada@example.com");
    }

    @Test
    @DisplayName("an unreplicated contact falls back to the recipient before giving up")
    void unreplicatedContactFallsBack() {
        personParty(ACCOUNT_PARTY, PERSON);
        person("ada@example.com", null);

        assertThat(resolver.resolve(MessageChannel.EMAIL, ACCOUNT_PARTY, CONTACT_PARTY)
                        .address())
                .isEqualTo("ada@example.com");
    }

    @Test
    @DisplayName("no person party at all is RECIPIENT_NOT_REPLICATED")
    void nothingReplicated() {
        assertThat(resolver.resolve(MessageChannel.EMAIL, ACCOUNT_PARTY, CONTACT_PARTY)
                        .unresolved())
                .isEqualTo(Unresolved.RECIPIENT_NOT_REPLICATED);
    }

    @Test
    @DisplayName("a tombstoned party (no person) is RECIPIENT_NOT_REPLICATED")
    void tombstonedParty() {
        personParty(ACCOUNT_PARTY, null);

        assertThat(resolver.resolve(MessageChannel.EMAIL, ACCOUNT_PARTY, null).unresolved())
                .isEqualTo(Unresolved.RECIPIENT_NOT_REPLICATED);
    }

    @Test
    @DisplayName("a person without the channel's contact point is NO_CONTACT_POINT")
    void noContactPoint() {
        personParty(ACCOUNT_PARTY, PERSON);
        person("ada@example.com", "  ");

        assertThat(resolver.resolve(MessageChannel.SMS, ACCOUNT_PARTY, null).unresolved())
                .isEqualTo(Unresolved.NO_CONTACT_POINT);
    }

    @Test
    @DisplayName("a stored value with no deliverable form is UNDELIVERABLE_ADDRESS")
    void undeliverable() {
        personParty(ACCOUNT_PARTY, PERSON);
        person("not-an-email", "555-0100");

        assertThat(resolver.resolve(MessageChannel.EMAIL, ACCOUNT_PARTY, null).unresolved())
                .isEqualTo(Unresolved.UNDELIVERABLE_ADDRESS);
        assertThat(resolver.resolve(MessageChannel.SMS, ACCOUNT_PARTY, null).unresolved())
                .isEqualTo(Unresolved.UNDELIVERABLE_ADDRESS);
    }
}
