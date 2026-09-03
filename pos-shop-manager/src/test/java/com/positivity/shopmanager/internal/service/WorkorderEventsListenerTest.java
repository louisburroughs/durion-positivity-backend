package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.workorder.WorkorderUpdatedV1;
import com.positivity.shopmanager.internal.dto.WorkorderStatusChangedEvent;
import com.positivity.shopmanager.internal.entity.ExtWorkorderReplica;
import com.positivity.shopmanager.internal.enums.ShopDashboardUnitType;
import com.positivity.shopmanager.internal.repository.ExtWorkorderReplicaRepository;
import com.positivity.shopmanager.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

/**
 * The workorder replica consumer, beyond the shared replica contract (#1658).
 *
 * <p>What is pinned here is the one judgement call the listener makes: pos-workorder emits a
 * snapshot fact for <em>every</em> business transaction that touches a workorder, most of which do
 * not move its status. Re-raising each of those as a status change would append a duplicate entry
 * to the linked appointment's status timeline on every unrelated edit, so the listener compares
 * against the row it is replacing and stays quiet when nothing moved.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkorderEventsListener — replica writes and status notifications")
class WorkorderEventsListenerTest {

    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID BAY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID VEHICLE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID MECHANIC_ONE = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID MECHANIC_TWO = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final String EVENT_ID = "01960003-0000-7000-8000-000000000001";
    private static final Instant NOW = Instant.parse("2026-09-03T09:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private ExtWorkorderReplicaRepository workorderRepository;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private WorkorderEventsListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(workorderRepository.findById(any())).thenReturn(Optional.empty());
        listener = new WorkorderEventsListener(
                clock,
                objectMapper,
                processedEventRepository,
                workorderRepository,
                applicationEventPublisher,
                Mockito.mock(ObjectProvider.class));
    }

    @Test
    @DisplayName("#1658 AC9 - the widened payload lands in the replica in full")
    void widenedPayloadIsReplicated() {
        listener.onWorkorderEvent(envelope(3, "WORK_IN_PROGRESS", BAY_ID, "BAY"));

        ArgumentCaptor<ExtWorkorderReplica> captor = ArgumentCaptor.forClass(ExtWorkorderReplica.class);
        verify(workorderRepository).save(captor.capture());
        ExtWorkorderReplica saved = captor.getValue();
        assertThat(saved.getWorkorderId()).isEqualTo(WORKORDER_ID);
        assertThat(saved.getWorkorderNumber()).isEqualTo("WO-2026-1001");
        assertThat(saved.getStatus()).isEqualTo("WORK_IN_PROGRESS");
        assertThat(saved.getLocationId()).isEqualTo(LOCATION_ID);
        assertThat(saved.getResourceId()).isEqualTo(BAY_ID);
        assertThat(saved.getResourceType()).isEqualTo("BAY");
        assertThat(saved.getVehicleId()).isEqualTo(VEHICLE_ID);
        assertThat(saved.getScheduledDate()).isEqualTo("2026-09-03");
        assertThat(saved.getPromisedAt()).isNull();
        assertThat(saved.getMechanicIds()).contains(MECHANIC_ONE.toString()).contains(MECHANIC_TWO.toString());
        assertThat(saved.getAggregateVersion()).isEqualTo(3);
        assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("#1658 AC9 - a status transition raises the widened in-process notification")
    void statusTransitionRaisesNotification() {
        when(workorderRepository.findById(WORKORDER_ID))
                .thenReturn(Optional.of(ExtWorkorderReplica.builder()
                        .workorderId(WORKORDER_ID)
                        .status("ASSIGNED")
                        .aggregateVersion(2)
                        .build()));

        listener.onWorkorderEvent(envelope(3, "WORK_IN_PROGRESS", BAY_ID, "BAY"));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        WorkorderStatusChangedEvent event = (WorkorderStatusChangedEvent) captor.getValue();
        assertThat(event.workorderId()).isEqualTo(WORKORDER_ID);
        assertThat(event.newStatus()).isEqualTo("WORK_IN_PROGRESS");
        assertThat(event.workorderNumber()).isEqualTo("WO-2026-1001");
        assertThat(event.locationId()).isEqualTo(LOCATION_ID);
        assertThat(event.resourceId()).isEqualTo(BAY_ID);
        assertThat(event.resourceType()).isEqualTo(ShopDashboardUnitType.BAY);
        assertThat(event.vehicleId()).isEqualTo(VEHICLE_ID);
        assertThat(event.mechanicIds()).containsExactly(MECHANIC_ONE, MECHANIC_TWO);
        assertThat(event.promisedAt()).isNull();
    }

    @Test
    @DisplayName("#1658 - a fact that does not move the status is replicated but raises nothing")
    void unchangedStatusDoesNotRaiseNotification() {
        when(workorderRepository.findById(WORKORDER_ID))
                .thenReturn(Optional.of(ExtWorkorderReplica.builder()
                        .workorderId(WORKORDER_ID)
                        .status("WORK_IN_PROGRESS")
                        .aggregateVersion(2)
                        .build()));

        listener.onWorkorderEvent(envelope(3, "WORK_IN_PROGRESS", BAY_ID, "BAY"));

        verify(workorderRepository).save(any());
        verify(applicationEventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("#1658 - the first fact for a workorder is a transition from nothing")
    void firstFactRaisesNotification() {
        listener.onWorkorderEvent(envelope(1, "DRAFT", null, null));

        verify(applicationEventPublisher).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("#1658 AC6 - a terminal fact is replicated, not deleted, so the read side frees the unit")
    void terminalStatusIsRetainedInTheReplica() {
        when(workorderRepository.findById(WORKORDER_ID))
                .thenReturn(Optional.of(ExtWorkorderReplica.builder()
                        .workorderId(WORKORDER_ID)
                        .status("WORK_IN_PROGRESS")
                        .aggregateVersion(2)
                        .build()));

        listener.onWorkorderEvent(envelope(3, "COMPLETED", BAY_ID, "BAY"));

        ArgumentCaptor<ExtWorkorderReplica> captor = ArgumentCaptor.forClass(ExtWorkorderReplica.class);
        verify(workorderRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("COMPLETED");
        assertThat(captor.getValue().getResourceId()).isEqualTo(BAY_ID);
        verify(workorderRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("#1658 - a resourceType the owner adds later degrades to unknown rather than failing")
    void unknownResourceTypeIsTolerated() {
        listener.onWorkorderEvent(envelope(1, "ASSIGNED", BAY_ID, "TRAILER"));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        assertThat(((WorkorderStatusChangedEvent) captor.getValue()).resourceType())
                .isNull();
        verify(workorderRepository).save(any());
    }

    @Test
    @DisplayName("#1658 - locationId falls back to shopId when the owner published only the latter")
    void locationIdFallsBackToShopId() {
        listener.onWorkorderEvent("""
                {"eventId":"%s","eventType":"%s","aggregateVersion":1,"payload":{
                  "workorderId":"%s","workorderNumber":"WO-1","status":"DRAFT","shopId":"%s",
                  "customerId":null,"vehicleId":null,"invoiceId":null,"parts":[],"services":[],
                  "createdAt":null,"updatedAt":null,"locationId":null,"resourceId":null,
                  "resourceType":null,"mechanicIds":[],"promisedAt":null,"scheduledDate":null}}""".formatted(EVENT_ID, WorkorderUpdatedV1.EVENT_TYPE, WORKORDER_ID, LOCATION_ID));

        ArgumentCaptor<ExtWorkorderReplica> captor = ArgumentCaptor.forClass(ExtWorkorderReplica.class);
        verify(workorderRepository).save(captor.capture());
        assertThat(captor.getValue().getLocationId()).isEqualTo(LOCATION_ID);
    }

    private String envelope(long aggregateVersion, String status, UUID resourceId, String resourceType) {
        return """
                {"eventId":"%s","eventType":"%s","aggregateVersion":%d,"payload":{
                  "workorderId":"%s","workorderNumber":"WO-2026-1001","status":"%s","shopId":"%s",
                  "customerId":null,"vehicleId":"%s","invoiceId":null,"parts":[],"services":[],
                  "createdAt":null,"updatedAt":null,"locationId":"%s","resourceId":%s,
                  "resourceType":%s,"mechanicIds":["%s","%s"],"promisedAt":null,
                  "scheduledDate":"2026-09-03"}}""".formatted(
                        EVENT_ID,
                        WorkorderUpdatedV1.EVENT_TYPE,
                        aggregateVersion,
                        WORKORDER_ID,
                        status,
                        LOCATION_ID,
                        VEHICLE_ID,
                        LOCATION_ID,
                        resourceId == null ? "null" : "\"" + resourceId + "\"",
                        resourceType == null ? "null" : "\"" + resourceType + "\"",
                        MECHANIC_ONE,
                        MECHANIC_TWO);
    }
}
