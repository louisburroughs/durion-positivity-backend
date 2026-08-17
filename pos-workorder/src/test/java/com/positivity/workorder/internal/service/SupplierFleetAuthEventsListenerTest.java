package com.positivity.workorder.internal.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.entity.ProcessedEvent;
import com.positivity.workorder.internal.enums.FleetAuthorizationStatus;
import com.positivity.workorder.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.TransientDataAccessException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Recording what a fleet program decided, unchanged (#1346).
 *
 * <p>The behaviour worth pinning is that granted and refused are passed through verbatim and that a
 * transient failure is retried rather than silently marked processed -- the same discipline every
 * other replica consumer in this codebase follows.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SupplierFleetAuthEventsListener — preserved, not reinterpreted (#1346)")
class SupplierFleetAuthEventsListenerTest {

    private static final Instant NOW = Instant.parse("2026-08-17T10:00:00Z");
    private static final UUID WORKORDER_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000c1");
    private static final UUID PROFILE_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000d1");

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private FleetAuthorizationService fleetAuthorizationService;

    private SupplierFleetAuthEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new SupplierFleetAuthEventsListener(
                Clock.fixed(NOW, ZoneOffset.UTC),
                JsonMapper.builder().build(),
                processedEventRepository,
                fleetAuthorizationService);
        when(processedEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("a grant is recorded as granted, unchanged")
    void grantIsRecorded() {
        when(processedEventRepository.existsById("e-1")).thenReturn(false);

        listener.onSupplierEvent("""
                {"eventId":"e-1","eventType":"supplier.workorderauth.granted","schemaVersion":1,
                 "aggregateId":"019200aa-0000-7000-8000-0000000000a1","aggregateVersion":1,
                 "payload":{"vendorProfileId":"019200aa-0000-7000-8000-0000000000d1",
                            "supplierRef":"michelin-de","workorderId":"019200aa-0000-7000-8000-0000000000c1",
                            "vendorAuthorizationId":"WO-42","contractReference":"C-1",
                            "authorizedAmount":null,"currency":null,"occurredAt":"2026-08-17T09:00:00Z"}}
                """);

        verify(fleetAuthorizationService)
                .recordDecision(
                        eq(WORKORDER_ID),
                        eq(PROFILE_ID),
                        eq(FleetAuthorizationStatus.GRANTED),
                        any(),
                        any(),
                        eq("C-1"));
    }

    @Test
    @DisplayName("a denial is recorded as refused, with the vendor's own reason unchanged")
    void denialIsRecordedAsRefused() {
        when(processedEventRepository.existsById("e-2")).thenReturn(false);

        listener.onSupplierEvent("""
                {"eventId":"e-2","eventType":"supplier.workorderauth.denied","schemaVersion":1,
                 "aggregateId":"019200aa-0000-7000-8000-0000000000a1","aggregateVersion":1,
                 "payload":{"vendorProfileId":"019200aa-0000-7000-8000-0000000000d1",
                            "supplierRef":"michelin-de","workorderId":"019200aa-0000-7000-8000-0000000000c1",
                            "vendorAuthorizationId":null,"reasonCode":"NO_COVER",
                            "reasonText":"Vehicle not on an active contract","occurredAt":"2026-08-17T09:00:00Z"}}
                """);

        verify(fleetAuthorizationService)
                .recordDecision(
                        eq(WORKORDER_ID),
                        eq(PROFILE_ID),
                        eq(FleetAuthorizationStatus.REFUSED),
                        eq("NO_COVER"),
                        eq("Vehicle not on an active contract"),
                        any());
    }

    @Test
    @DisplayName("an already-processed event is not applied twice")
    void redeliveryIsANoOp() {
        when(processedEventRepository.existsById("e-3")).thenReturn(true);

        listener.onSupplierEvent("""
                {"eventId":"e-3","eventType":"supplier.workorderauth.granted","schemaVersion":1,
                 "payload":{}}
                """);

        verify(fleetAuthorizationService, never()).recordDecision(any(), any(), any(), any(), any(), any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("a transient failure is rethrown for retry rather than marked processed")
    void transientFailureIsRethrown() {
        when(processedEventRepository.existsById("e-4")).thenReturn(false);
        org.mockito.Mockito.doThrow(new TransientDataAccessException("db down") {})
                .when(fleetAuthorizationService)
                .recordDecision(any(), any(), any(), any(), any(), any());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> listener.onSupplierEvent("""
                        {"eventId":"e-4","eventType":"supplier.workorderauth.granted","schemaVersion":1,
                         "aggregateId":"019200aa-0000-7000-8000-0000000000a1","aggregateVersion":1,
                         "payload":{"vendorProfileId":"019200aa-0000-7000-8000-0000000000d1",
                                    "supplierRef":"michelin-de","workorderId":"019200aa-0000-7000-8000-0000000000c1",
                                    "vendorAuthorizationId":"WO-42","contractReference":null,
                                    "authorizedAmount":null,"currency":null,"occurredAt":"2026-08-17T09:00:00Z"}}
                        """))
                .isInstanceOf(TransientDataAccessException.class);

        // Marking this processed would leave a workorder gated on an authorization the fleet
        // already granted, with nothing to correct it.
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("an unrecognised event type is still recorded as processed")
    void unrecognisedTypeIsStillProcessed() {
        when(processedEventRepository.existsById("e-5")).thenReturn(false);

        listener.onSupplierEvent("""
                {"eventId":"e-5","eventType":"supplier.something.else","schemaVersion":1,"payload":{}}
                """);

        verify(processedEventRepository).save(any(ProcessedEvent.class));
        verify(fleetAuthorizationService, never()).recordDecision(any(), any(), any(), any(), any(), any());
    }
}
