package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.ExtVehicle;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.ExtVehicleRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link VehicleEventsListener} (ADR-0044 §6, #843): replica upsert with
 * idempotency and stale-version guards, plus maintenance of the customer-owned vehicle-party
 * association (VIN follows the event's accountId; deactivation removes it everywhere).
 */
class VehicleEventsListenerTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-12T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID VEHICLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OTHER_PARTY_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final String VIN = "1HGBH41JXMN109186";

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final ExtVehicleRepository replica = mock(ExtVehicleRepository.class);
    private final PersonPartyRepository personParties = mock(PersonPartyRepository.class);
    private final CommercialPartyRepository commercialParties = mock(CommercialPartyRepository.class);

    private VehicleEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new VehicleEventsListener(
                TEST_CLOCK, new ObjectMapper(), processedEvents, replica, personParties, commercialParties);
    }

    private String event(String eventId, long version, UUID accountId, boolean active) {
        return """
                {"eventId":"%s","eventType":"vehicle.vehicle.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,
                 "payload":{"vehicleId":"%s","accountId":"%s","vin":"%s","vinNormalized":"%s",
                            "make":"Honda","model":"Accord","year":2022,"active":%b}}
                """.formatted(eventId, VEHICLE_ID, version, VEHICLE_ID, accountId, VIN, VIN, active);
    }

    private CommercialParty commercialParty(UUID id) {
        CommercialParty p = new CommercialParty();
        p.setPartyId(id);
        return p;
    }

    @Test
    @DisplayName("Materializes vehicle event into the replica and records the eventId")
    void upsertsReplica() {
        when(processedEvents.existsById("e-1")).thenReturn(false);
        when(replica.findById(VEHICLE_ID)).thenReturn(Optional.empty());
        when(personParties.findByVehicleVin(VIN)).thenReturn(List.of());
        when(commercialParties.findByVehicleVin(VIN)).thenReturn(List.of());
        when(personParties.findById(ACCOUNT_ID)).thenReturn(Optional.empty());
        when(commercialParties.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        listener.onVehicleEvent(event("e-1", 5, ACCOUNT_ID, true));

        ArgumentCaptor<ExtVehicle> saved = ArgumentCaptor.forClass(ExtVehicle.class);
        verify(replica).save(saved.capture());
        assertThat(saved.getValue().getVehicleId()).isEqualTo(VEHICLE_ID);
        assertThat(saved.getValue().getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(saved.getValue().getVin()).isEqualTo(VIN);
        assertThat(saved.getValue().getMake()).isEqualTo("Honda");
        assertThat(saved.getValue().isActive()).isTrue();
        assertThat(saved.getValue().getAggregateVersion()).isEqualTo(5L);
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Adds the VIN to the owning party's association set")
    void addsAssociationToOwner() {
        CommercialParty owner = commercialParty(ACCOUNT_ID);
        when(processedEvents.existsById("e-2")).thenReturn(false);
        when(replica.findById(VEHICLE_ID)).thenReturn(Optional.empty());
        when(personParties.findByVehicleVin(VIN)).thenReturn(List.of());
        when(commercialParties.findByVehicleVin(VIN)).thenReturn(List.of());
        when(personParties.findById(ACCOUNT_ID)).thenReturn(Optional.empty());
        when(commercialParties.findById(ACCOUNT_ID)).thenReturn(Optional.of(owner));

        listener.onVehicleEvent(event("e-2", 0, ACCOUNT_ID, true));

        assertThat(owner.getVehicleVins()).containsExactly(VIN);
        verify(commercialParties).save(owner);
    }

    @Test
    @DisplayName("Moves the VIN between parties on ownership transfer")
    void movesAssociationOnTransfer() {
        CommercialParty previousOwner = commercialParty(OTHER_PARTY_ID);
        previousOwner.getVehicleVins().add(VIN);
        CommercialParty newOwner = commercialParty(ACCOUNT_ID);
        when(processedEvents.existsById("e-3")).thenReturn(false);
        when(replica.findById(VEHICLE_ID)).thenReturn(Optional.empty());
        when(personParties.findByVehicleVin(VIN)).thenReturn(List.of());
        when(commercialParties.findByVehicleVin(VIN)).thenReturn(List.of(previousOwner));
        when(personParties.findById(ACCOUNT_ID)).thenReturn(Optional.empty());
        when(commercialParties.findById(ACCOUNT_ID)).thenReturn(Optional.of(newOwner));

        listener.onVehicleEvent(event("e-3", 7, ACCOUNT_ID, true));

        assertThat(previousOwner.getVehicleVins()).isEmpty();
        assertThat(newOwner.getVehicleVins()).containsExactly(VIN);
        verify(commercialParties).save(previousOwner);
        verify(commercialParties).save(newOwner);
    }

    @Test
    @DisplayName("Removes the VIN from all parties on deactivation")
    void removesAssociationOnDeactivation() {
        PersonParty holder = new PersonParty();
        holder.setPartyId(ACCOUNT_ID);
        holder.getVehicleVins().add(VIN);
        when(processedEvents.existsById("e-4")).thenReturn(false);
        when(replica.findById(VEHICLE_ID)).thenReturn(Optional.empty());
        when(personParties.findByVehicleVin(VIN)).thenReturn(List.of(holder));
        when(commercialParties.findByVehicleVin(VIN)).thenReturn(List.of());

        listener.onVehicleEvent(event("e-4", 8, ACCOUNT_ID, false));

        assertThat(holder.getVehicleVins()).isEmpty();
        verify(personParties).save(holder);
        ArgumentCaptor<ExtVehicle> saved = ArgumentCaptor.forClass(ExtVehicle.class);
        verify(replica).save(saved.capture());
        assertThat(saved.getValue().isActive()).isFalse();
    }

    @Test
    @DisplayName("Skips duplicate events by eventId")
    void skipsDuplicates() {
        when(processedEvents.existsById("e-dup")).thenReturn(true);

        listener.onVehicleEvent(event("e-dup", 5, ACCOUNT_ID, true));

        verify(replica, never()).save(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Skips stale events whose aggregateVersion is at or below the replica's")
    void skipsStaleVersions() {
        when(processedEvents.existsById("e-old")).thenReturn(false);
        when(replica.findById(VEHICLE_ID))
                .thenReturn(Optional.of(ExtVehicle.builder()
                        .vehicleId(VEHICLE_ID)
                        .accountId(ACCOUNT_ID)
                        .vin(VIN)
                        .vinNormalized(VIN)
                        .active(true)
                        .aggregateVersion(9L)
                        .updatedAt(Instant.now(TEST_CLOCK))
                        .build()));

        listener.onVehicleEvent(event("e-old", 5, ACCOUNT_ID, true));

        verify(replica, never()).save(any());
        // Still recorded as processed so redelivery does not reprocess it.
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Ignores other event types")
    void ignoresOtherEventTypes() {
        listener.onVehicleEvent("""
                {"eventId":"e-x","eventType":"vehicle.manifest.published","payload":{}}
                """);

        verify(replica, never()).save(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Skips unparsable messages without recording them")
    void skipsUnparsableMessages() {
        listener.onVehicleEvent("{ not json ");

        verify(replica, never()).save(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Records malformed payloads as processed so they do not poison the partition")
    void recordsMalformedPayloads() {
        when(processedEvents.existsById("e-bad")).thenReturn(false);

        listener.onVehicleEvent("""
                {"eventId":"e-bad","eventType":"vehicle.vehicle.updated","aggregateVersion":1,
                 "payload":{"vehicleId":"not-a-uuid"}}
                """);

        verify(replica, never()).save(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Rethrows transient DB errors for container retry/DLQ (ADR-0044 §4)")
    void rethrowsTransientErrors() {
        when(processedEvents.existsById("e-t")).thenReturn(false);
        when(replica.findById(VEHICLE_ID)).thenThrow(new QueryTimeoutException("db timeout"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onVehicleEvent(event("e-t", 5, ACCOUNT_ID, true)));
        verify(processedEvents, never()).save(any());
    }
}
