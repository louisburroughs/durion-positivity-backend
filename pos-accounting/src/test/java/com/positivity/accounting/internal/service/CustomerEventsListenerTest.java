package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.ExtCustomerBillingRules;
import com.positivity.accounting.internal.entity.ExtCustomerParty;
import com.positivity.accounting.internal.repository.ExtCustomerBillingRulesRepository;
import com.positivity.accounting.internal.repository.ExtCustomerPartyRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
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
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

class CustomerEventsListenerTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-08T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID PARTY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final ExtCustomerBillingRulesRepository replica = mock(ExtCustomerBillingRulesRepository.class);
    private final ExtCustomerPartyRepository partyReplica = mock(ExtCustomerPartyRepository.class);

    private CustomerEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new CustomerEventsListener(
                TEST_CLOCK,
                new ObjectMapper(),
                processedEvents,
                replica,
                partyReplica,
                org.mockito.Mockito.mock(ObjectProvider.class));
    }

    private String event(String eventId, long version) {
        return """
                {"eventId":"%s","eventType":"customer.billing-rules.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,
                 "payload":{"partyId":"%s","poRequired":true,"paymentTerms":"NET30","currency":"USD"}}
                """.formatted(eventId, PARTY_ID, version, PARTY_ID);
    }

    @Test
    @DisplayName("Materializes billing-rules event into the replica and records the eventId")
    void upsertsReplica() {
        when(processedEvents.existsById("e-1")).thenReturn(false);
        when(replica.findById(PARTY_ID)).thenReturn(Optional.empty());

        listener.onCustomerEvent(event("e-1", 5));

        ArgumentCaptor<ExtCustomerBillingRules> saved = ArgumentCaptor.forClass(ExtCustomerBillingRules.class);
        verify(replica).save(saved.capture());
        assertThat(saved.getValue().getPartyId()).isEqualTo(PARTY_ID);
        assertThat(saved.getValue().getPoRequired()).isTrue();
        assertThat(saved.getValue().getPaymentTerms()).isEqualTo("NET30");
        assertThat(saved.getValue().getAggregateVersion()).isEqualTo(5L);
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Duplicate eventId is skipped (idempotency)")
    void skipsDuplicates() {
        when(processedEvents.existsById("e-1")).thenReturn(true);

        listener.onCustomerEvent(event("e-1", 5));

        verify(replica, never()).save(any());
    }

    @Test
    @DisplayName("Stale event (version strictly below the replica's) does not regress the replica")
    void skipsStaleVersions() {
        when(processedEvents.existsById("e-2")).thenReturn(false);
        when(replica.findById(PARTY_ID))
                .thenReturn(Optional.of(ExtCustomerBillingRules.builder()
                        .partyId(PARTY_ID)
                        .aggregateVersion(9L)
                        .updatedAt(Instant.parse("2026-07-08T11:00:00Z"))
                        .build()));

        listener.onCustomerEvent(event("e-2", 5));

        verify(replica, never()).save(any());
        // Still recorded as processed so redelivery does not loop.
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Applies an event with an equal version stamp (#1486, ReplicaVersionGuard)")
    void appliesEqualVersion() {
        // Load-bearing: the party's aggregateVersion strictly advances (committed JPA @Version,
        // flushed before emit), so an equal version means identical content, and
        // POST .../facts/replay deliberately resends a fact at the held version to repair a
        // replica with wrong or missing rows. Skipping on equal (the old `>=` guard) silently
        // turned replay into a no-op (#1486).
        when(processedEvents.existsById("e-eq")).thenReturn(false);
        when(replica.findById(PARTY_ID))
                .thenReturn(Optional.of(ExtCustomerBillingRules.builder()
                        .partyId(PARTY_ID)
                        .aggregateVersion(9L)
                        .updatedAt(Instant.parse("2026-07-08T11:00:00Z"))
                        .build()));

        listener.onCustomerEvent(event("e-eq", 9));

        ArgumentCaptor<ExtCustomerBillingRules> saved = ArgumentCaptor.forClass(ExtCustomerBillingRules.class);
        verify(replica).save(saved.capture());
        assertThat(saved.getValue().getAggregateVersion()).isEqualTo(9L);
    }

    @Test
    @DisplayName("Skips a legacy version-0 event when the replica already holds a real version (#1486)")
    void skipsVersionZeroEventAgainstARealHeldVersion() {
        // The publisher always stamps a real, flushed @Version now, so a 0 here can only be a
        // legacy or malformed envelope; ReplicaVersionGuard treats it as stale against any real
        // held version rather than special-casing it to always apply (the retired
        // `&& aggregateVersion > 0` carve-out).
        when(processedEvents.existsById("e-zero")).thenReturn(false);
        when(replica.findById(PARTY_ID))
                .thenReturn(Optional.of(ExtCustomerBillingRules.builder()
                        .partyId(PARTY_ID)
                        .aggregateVersion(9L)
                        .updatedAt(Instant.parse("2026-07-08T11:00:00Z"))
                        .build()));

        listener.onCustomerEvent(event("e-zero", 0));

        verify(replica, never()).save(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Unknown event types are ignored")
    void ignoresOtherTypes() {
        // customer.segment.changed is a real fact on this topic that accounting does not
        // materialize. It used to be customer.party.updated here, which #1779 turned into a
        // handled type — the assertion below only means anything for a type nothing consumes.
        listener.onCustomerEvent("{\"eventId\":\"e-3\",\"eventType\":\"customer.segment.changed\"}");

        verify(replica, never()).save(any());
        verify(partyReplica, never()).save(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Materializes a party-updated event into the customer-party replica (issue #1779)")
    void materializesPartyUpdated() {
        when(processedEvents.existsById("e-party-1")).thenReturn(false);
        when(partyReplica.findById(PARTY_ID)).thenReturn(Optional.empty());

        listener.onCustomerEvent(partyUpdatedEvent("e-party-1", 3, "Northside Fleet Services", "C-10427"));

        ArgumentCaptor<ExtCustomerParty> saved = ArgumentCaptor.forClass(ExtCustomerParty.class);
        verify(partyReplica).save(saved.capture());
        assertThat(saved.getValue().getPartyId()).isEqualTo(PARTY_ID);
        assertThat(saved.getValue().getDisplayName()).isEqualTo("Northside Fleet Services");
        assertThat(saved.getValue().getCustomerNumber()).isEqualTo("C-10427");
        assertThat(saved.getValue().getAggregateVersion()).isEqualTo(3);
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("A party's null display values are replicated as null, never filled in (issue #1779)")
    void replicatesAbsentPartyDisplayValuesAsNull() {
        when(processedEvents.existsById("e-party-2")).thenReturn(false);
        when(partyReplica.findById(PARTY_ID)).thenReturn(Optional.empty());

        listener.onCustomerEvent(partyUpdatedEvent("e-party-2", 1, null, null));

        ArgumentCaptor<ExtCustomerParty> saved = ArgumentCaptor.forClass(ExtCustomerParty.class);
        verify(partyReplica).save(saved.capture());
        assertThat(saved.getValue().getDisplayName()).isNull();
        assertThat(saved.getValue().getCustomerNumber()).isNull();
    }

    @Test
    @DisplayName("A stale party-updated event does not overwrite a newer replica row")
    void skipsStalePartyUpdate() {
        when(processedEvents.existsById("e-party-3")).thenReturn(false);
        ExtCustomerParty held = ExtCustomerParty.builder()
                .partyId(PARTY_ID)
                .partyType("COMMERCIAL")
                .displayName("Held Name")
                .status("ACTIVE")
                .aggregateVersion(9)
                .updatedAt(Instant.now(TEST_CLOCK))
                .build();
        when(partyReplica.findById(PARTY_ID)).thenReturn(Optional.of(held));

        listener.onCustomerEvent(partyUpdatedEvent("e-party-3", 4, "Older Name", "C-1"));

        verify(partyReplica, never()).save(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("A party-deleted event drops the replica row (issue #1779)")
    void deletesPartyReplicaRow() {
        when(processedEvents.existsById("e-party-4")).thenReturn(false);

        listener.onCustomerEvent("""
                {"eventId":"e-party-4","eventType":"customer.party.deleted","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":7,
                 "payload":{"partyId":"%s"}}
                """.formatted(PARTY_ID, PARTY_ID));

        verify(partyReplica).deleteById(PARTY_ID);
        verify(processedEvents).save(any());
    }

    private String partyUpdatedEvent(String eventId, long version, String displayName, String customerNumber) {
        return """
                {"eventId":"%s","eventType":"customer.party.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,
                 "payload":{"partyId":"%s","partyType":"COMMERCIAL","customerNumber":%s,
                            "displayName":%s,"status":"ACTIVE","requirementsMet":true}}
                """.formatted(eventId, PARTY_ID, version, PARTY_ID, jsonOrNull(customerNumber), jsonOrNull(displayName));
    }

    private static String jsonOrNull(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    @Test
    @DisplayName("Transient DB errors propagate for container retry/DLQ")
    void transientErrorsPropagate() {
        when(processedEvents.existsById("e-4")).thenReturn(false);
        when(replica.findById(PARTY_ID)).thenThrow(new QueryTimeoutException("db timeout"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onCustomerEvent(event("e-4", 5)));
        verify(processedEvents, never()).save(any());
    }
}
