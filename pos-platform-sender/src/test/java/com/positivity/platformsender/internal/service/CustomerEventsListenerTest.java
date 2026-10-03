package com.positivity.platformsender.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.platformsender.internal.entity.ExtCustomerPersonParty;
import com.positivity.platformsender.internal.repository.ExtCustomerPersonPartyRepository;
import com.positivity.platformsender.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@DisplayName("CustomerEventsListener — person-party replica")
class CustomerEventsListenerTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final UUID PARTY_ID = UUID.fromString("01990000-0000-7000-8000-0000000000a1");
    private static final UUID PERSON_ID = UUID.fromString("01990000-0000-7000-8000-0000000000b1");
    private static final String EVENT_ID = "01990000-0000-7000-8000-0000000000e2";

    private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
    private final ExtCustomerPersonPartyRepository parties = mock(ExtCustomerPersonPartyRepository.class);
    private CustomerEventsListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        listener = new CustomerEventsListener(
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper(),
                processed,
                parties,
                mock(ObjectProvider.class),
                mock(PlatformTransactionManager.class));
        when(parties.findById(PARTY_ID)).thenReturn(Optional.empty());
    }

    private static String partyUpdated(String partyType, String personId, long version) {
        return """
                {"eventId":"%s","eventType":"customer.party.updated","aggregateVersion":%d,
                 "payload":{"partyId":"%s","partyType":"%s","displayName":"Ada Lovelace","personId":%s,
                            "status":"ACTIVE","requirementsMet":true}}
                """.formatted(
                        EVENT_ID, version, PARTY_ID, partyType, personId == null ? "null" : "\"" + personId + "\"");
    }

    private ExtCustomerPersonParty saved() {
        ArgumentCaptor<ExtCustomerPersonParty> saved = ArgumentCaptor.captor();
        verify(parties).save(saved.capture());
        return saved.getValue();
    }

    @Test
    @DisplayName("a person party maps to its pos-people-contact person")
    void personPartyMapped() {
        listener.onCustomerEvent(partyUpdated("PERSON", PERSON_ID.toString(), 4));

        ExtCustomerPersonParty row = saved();
        assertThat(row.getPartyId()).isEqualTo(PARTY_ID);
        assertThat(row.getPersonId()).isEqualTo(PERSON_ID);
        assertThat(row.getAggregateVersion()).isEqualTo(4);
        verify(processed).save(any());
    }

    @Test
    @DisplayName("a commercial party is not kept, but the event is marked")
    void commercialPartyIgnored() {
        listener.onCustomerEvent(partyUpdated("COMMERCIAL", null, 4));

        verify(parties, never()).save(any());
        verify(processed).save(any());
    }

    @Test
    @DisplayName("a stale update is skipped")
    void staleSkipped() {
        when(parties.findById(PARTY_ID))
                .thenReturn(Optional.of(ExtCustomerPersonParty.builder()
                        .partyId(PARTY_ID)
                        .personId(PERSON_ID)
                        .aggregateVersion(9)
                        .build()));

        listener.onCustomerEvent(partyUpdated("PERSON", PERSON_ID.toString(), 4));

        verify(parties, never()).save(any());
    }

    @Test
    @DisplayName("deleting a known person party tombstones it at the delete's version")
    void deleteTombstones() {
        when(parties.existsById(PARTY_ID)).thenReturn(true);
        when(parties.findById(PARTY_ID))
                .thenReturn(Optional.of(ExtCustomerPersonParty.builder()
                        .partyId(PARTY_ID)
                        .personId(PERSON_ID)
                        .aggregateVersion(4)
                        .build()));

        listener.onCustomerEvent("""
                {"eventId":"%s","eventType":"customer.party.deleted","aggregateVersion":5,
                 "payload":{"partyId":"%s","personId":"%s"}}
                """.formatted(EVENT_ID, PARTY_ID, PERSON_ID));

        ExtCustomerPersonParty row = saved();
        assertThat(row.getPersonId()).isNull();
        assertThat(row.getAggregateVersion()).isEqualTo(5);
    }

    @Test
    @DisplayName("deleting a party this replica never held, with no person, writes nothing but the mark")
    void deleteOfUnknownCommercialParty() {
        listener.onCustomerEvent("""
                {"eventId":"%s","eventType":"customer.party.deleted","aggregateVersion":5,
                 "payload":{"partyId":"%s","personId":null}}
                """.formatted(EVENT_ID, PARTY_ID));

        verify(parties, never()).save(any());
        verify(processed).save(any());
    }

    @Test
    @DisplayName("every other customer fact is marked and ignored")
    void otherFactsMarked() {
        listener.onCustomerEvent("""
                {"eventId":"%s","eventType":"customer.segment.resolved","payload":{}}
                """.formatted(EVENT_ID));

        verify(parties, never()).save(any());
        verify(processed).save(any());
    }

    @Test
    @DisplayName("an already-processed event is skipped entirely")
    void duplicateSkipped() {
        when(processed.existsById(EVENT_ID)).thenReturn(true);

        listener.onCustomerEvent(partyUpdated("PERSON", PERSON_ID.toString(), 4));

        verify(parties, never()).save(any());
        verify(processed, never()).save(any());
    }

    @Test
    @DisplayName("a malformed payload is marked and dropped")
    void malformedMarked() {
        listener.onCustomerEvent("""
                {"eventId":"%s","eventType":"customer.party.updated","payload":{"partyId":"nope"}}
                """.formatted(EVENT_ID));

        verify(parties, never()).save(any());
        verify(processed).save(any());
    }

    @Test
    @DisplayName("a lost connection is rethrown for container retry, with no mark written")
    void lostConnectionRethrown() {
        when(parties.save(any())).thenThrow(new DataAccessResourceFailureException("connection reset"));

        assertThatExceptionOfType(DataAccessResourceFailureException.class)
                .isThrownBy(() -> listener.onCustomerEvent(partyUpdated("PERSON", PERSON_ID.toString(), 4)));
        verify(processed, never()).save(any());
    }

    @Test
    @DisplayName("events without an id, or that are not JSON, are dropped")
    void unidentifiedDropped() {
        listener.onCustomerEvent("{");
        listener.onCustomerEvent("{\"eventType\":\"customer.party.updated\"}");

        verify(processed, never()).save(any());
    }
}
