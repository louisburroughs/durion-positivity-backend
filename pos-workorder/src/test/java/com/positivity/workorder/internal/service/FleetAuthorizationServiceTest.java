package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.workorder.WorkorderFleetAuthRequestedV1;
import com.positivity.workorder.internal.config.OutboxEventWriter;
import com.positivity.workorder.internal.entity.ExtBillingRulesReplica;
import com.positivity.workorder.internal.entity.ExtVehicleReplica;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderFleetAuthorization;
import com.positivity.workorder.internal.enums.FleetAuthorizationResolution;
import com.positivity.workorder.internal.enums.FleetAuthorizationStatus;
import com.positivity.workorder.internal.repository.ExtBillingRulesReplicaRepository;
import com.positivity.workorder.internal.repository.ExtVehicleReplicaRepository;
import com.positivity.workorder.internal.repository.WorkorderFleetAuthorizationRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.beans.factory.ObjectProvider;

/**
 * Fleet payment authorization (#1346).
 *
 * <p>The behaviours pinned here are the domain ruling in code form: the gate closes on everything
 * but a grant, a refusal is never automatic, and a re-request is refused while one is already live.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FleetAuthorizationService — the payer-authorization lifecycle (#1346)")
class FleetAuthorizationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-17T10:00:00Z");
    private static final UUID WORKORDER_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000c1");
    private static final String PARTY_ID = "party-fleet-1";

    @Mock
    private WorkorderRepository workorderRepository;

    @Mock
    private WorkorderFleetAuthorizationRepository authorizationRepository;

    @Mock
    private ExtBillingRulesReplicaRepository billingRulesRepository;

    @Mock
    private ExtVehicleReplicaRepository vehicleRepository;

    @Mock
    private ObjectProvider<OutboxEventWriter> outboxEventWriterProvider;

    @Mock
    private OutboxEventWriter outboxEventWriter;

    private FleetAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = new FleetAuthorizationService(
                workorderRepository,
                authorizationRepository,
                billingRulesRepository,
                vehicleRepository,
                outboxEventWriterProvider,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(outboxEventWriterProvider.getIfAvailable()).thenReturn(outboxEventWriter);
        when(authorizationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("the start gate")
    class StartGate {

        @Test
        @DisplayName("a workorder whose payer does not require fleet authorization may start")
        void nonFleetWorkorderIsUnaffected() {
            withWorkorder(null);
            when(billingRulesRepository.findById(PARTY_ID)).thenReturn(Optional.empty());

            service.assertMayStart(WORKORDER_ID);

            verify(authorizationRepository, never()).save(any());
        }

        @Test
        @DisplayName("granted opens the gate")
        void grantedPermitsStart() {
            withWorkorder(null);
            withRequiredPolicy();
            when(authorizationRepository.findByWorkorderId(WORKORDER_ID))
                    .thenReturn(Optional.of(authorization(FleetAuthorizationStatus.GRANTED)));

            service.assertMayStart(WORKORDER_ID);
        }

        @Test
        @DisplayName("never requested refuses the start")
        void neverRequestedRefusesStart() {
            withWorkorder(null);
            withRequiredPolicy();
            when(authorizationRepository.findByWorkorderId(WORKORDER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.assertMayStart(WORKORDER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("NOT_REQUESTED");
        }

        @Test
        @DisplayName("pending refuses the start")
        void pendingRefusesStart() {
            withWorkorder(null);
            withRequiredPolicy();
            when(authorizationRepository.findByWorkorderId(WORKORDER_ID))
                    .thenReturn(Optional.of(authorization(FleetAuthorizationStatus.PENDING)));

            assertThatThrownBy(() -> service.assertMayStart(WORKORDER_ID)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("refused refuses the start, and does not cancel or convert automatically")
        void refusedRefusesStartWithoutSideEffects() {
            // The domain ruled: no automatic cancellation, no automatic conversion to customer-pay.
            // The only observable effect of a refusal here is that the start is refused.
            withWorkorder(null);
            withRequiredPolicy();
            when(authorizationRepository.findByWorkorderId(WORKORDER_ID))
                    .thenReturn(Optional.of(authorization(FleetAuthorizationStatus.REFUSED)));

            assertThatThrownBy(() -> service.assertMayStart(WORKORDER_ID)).isInstanceOf(IllegalStateException.class);
            verify(workorderRepository, never()).save(any());
        }

        @Test
        @DisplayName("manual review refuses the start but is never described as a denial")
        void manualReviewIsNotADenial() {
            withWorkorder(null);
            withRequiredPolicy();
            WorkorderFleetAuthorization stuck = authorization(FleetAuthorizationStatus.MANUAL_REVIEW);
            stuck.setReviewReason("vendor unreachable");
            when(authorizationRepository.findByWorkorderId(WORKORDER_ID)).thenReturn(Optional.of(stuck));

            assertThatThrownBy(() -> service.assertMayStart(WORKORDER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MANUAL_REVIEW")
                    .hasMessageNotContaining("declined")
                    .hasMessageNotContaining("refused");
        }

        @Test
        @DisplayName("a refused start stamps when the block first happened, once")
        void firstBlockIsStampedOnce() {
            withWorkorder(null);
            withRequiredPolicy();
            WorkorderFleetAuthorization pending = authorization(FleetAuthorizationStatus.PENDING);
            when(authorizationRepository.findByWorkorderId(WORKORDER_ID)).thenReturn(Optional.of(pending));

            assertThatThrownBy(() -> service.assertMayStart(WORKORDER_ID)).isInstanceOf(IllegalStateException.class);
            assertThat(pending.getFirstBlockedAt()).isEqualTo(NOW);

            // A second refused start must not move an already-stamped time forward: the release
            // delay is measured from the first attempt, and re-stamping would reset the clock every
            // time someone tried and failed to start the job.
            Instant firstStamp = pending.getFirstBlockedAt();
            assertThatThrownBy(() -> service.assertMayStart(WORKORDER_ID)).isInstanceOf(IllegalStateException.class);
            assertThat(pending.getFirstBlockedAt()).isEqualTo(firstStamp);
        }
    }

    @Nested
    @DisplayName("requesting authorization")
    class Requesting {

        @Test
        @DisplayName("a vehicle with plate, VIN or unit number produces a real request")
        void identifiedVehicleIsRequested() {
            withWorkorder(vehicleId());
            withRequiredPolicy();
            when(authorizationRepository.findByWorkorderId(WORKORDER_ID)).thenReturn(Optional.empty());
            when(vehicleRepository.findById(vehicleId())).thenReturn(Optional.of(vehicle("ABC-123", null, null)));

            Optional<WorkorderFleetAuthorization> result = service.requestAuthorization(WORKORDER_ID);

            assertThat(result).isPresent();
            assertThat(result.get().getStatus()).isEqualTo(FleetAuthorizationStatus.PENDING);
            ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
            verify(outboxEventWriter)
                    .publish(
                            org.mockito.ArgumentMatchers.eq(WorkorderFleetAuthRequestedV1.EVENT_TYPE),
                            org.mockito.ArgumentMatchers.anyInt(),
                            org.mockito.ArgumentMatchers.eq(WORKORDER_ID),
                            payload.capture());
            assertThat(payload.getValue())
                    .isInstanceOfSatisfying(
                            WorkorderFleetAuthRequestedV1.class,
                            command -> assertThat(command.licensePlate()).isEqualTo("ABC-123"));
        }

        @Test
        @DisplayName("no known vehicle identity parks the row for review instead of sending an unnamed request")
        void unidentifiableVehicleIsNotSent() {
            withWorkorder(vehicleId());
            withRequiredPolicy();
            when(authorizationRepository.findByWorkorderId(WORKORDER_ID)).thenReturn(Optional.empty());
            when(vehicleRepository.findById(vehicleId())).thenReturn(Optional.empty());

            Optional<WorkorderFleetAuthorization> result = service.requestAuthorization(WORKORDER_ID);

            assertThat(result).isPresent();
            assertThat(result.get().getStatus()).isEqualTo(FleetAuthorizationStatus.MANUAL_REVIEW);
            verify(outboxEventWriter, never()).publish(any(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
        }

        @Test
        @DisplayName("a vehicle replica with none of plate, VIN or unit number is also not sent")
        void replicaWithNoIdentifyingFieldsIsNotSent() {
            withWorkorder(vehicleId());
            withRequiredPolicy();
            when(authorizationRepository.findByWorkorderId(WORKORDER_ID)).thenReturn(Optional.empty());
            when(vehicleRepository.findById(vehicleId())).thenReturn(Optional.of(vehicle(null, null, null)));

            Optional<WorkorderFleetAuthorization> result = service.requestAuthorization(WORKORDER_ID);

            assertThat(result.get().getStatus()).isEqualTo(FleetAuthorizationStatus.MANUAL_REVIEW);
        }

        @Test
        @DisplayName("already granted is not asked again")
        void grantedIsNotReRequested() {
            withWorkorder(vehicleId());
            withRequiredPolicy();
            when(authorizationRepository.findByWorkorderId(WORKORDER_ID))
                    .thenReturn(Optional.of(authorization(FleetAuthorizationStatus.GRANTED)));

            service.requestAuthorization(WORKORDER_ID);

            verify(outboxEventWriter, never()).publish(any(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
        }

        @Test
        @DisplayName("already pending is not asked again, because a second ask can open a second authorization")
        void pendingIsNotReRequested() {
            withWorkorder(vehicleId());
            withRequiredPolicy();
            when(authorizationRepository.findByWorkorderId(WORKORDER_ID))
                    .thenReturn(Optional.of(authorization(FleetAuthorizationStatus.PENDING)));

            service.requestAuthorization(WORKORDER_ID);

            verify(outboxEventWriter, never()).publish(any(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
        }

        @Test
        @DisplayName("a refused authorization may be re-requested, and the stale refusal is cleared")
        void refusedMayBeReRequested() {
            withWorkorder(vehicleId());
            withRequiredPolicy();
            when(vehicleRepository.findById(vehicleId())).thenReturn(Optional.of(vehicle("ABC-123", null, null)));
            WorkorderFleetAuthorization refused = authorization(FleetAuthorizationStatus.REFUSED);
            refused.setVendorReasonText("Contract expired");
            when(authorizationRepository.findByWorkorderId(WORKORDER_ID)).thenReturn(Optional.of(refused));

            Optional<WorkorderFleetAuthorization> result = service.requestAuthorization(WORKORDER_ID);

            assertThat(result.get().getStatus()).isEqualTo(FleetAuthorizationStatus.PENDING);
            // A stale refusal reason beside a fresh request would read as if the fleet had just
            // refused again.
            assertThat(result.get().getVendorReasonText()).isNull();
        }

        @Test
        @DisplayName("a non-fleet workorder is not requested at all")
        void nonFleetWorkorderIsNotRequested() {
            withWorkorder(vehicleId());
            when(billingRulesRepository.findById(PARTY_ID)).thenReturn(Optional.empty());

            assertThat(service.requestAuthorization(WORKORDER_ID)).isEmpty();
            verify(authorizationRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("resolving a refusal")
    class Resolving {

        @Test
        @DisplayName("cancelling records who and why, and moves the status")
        void cancelIsRecorded() {
            when(authorizationRepository.findByWorkorderId(WORKORDER_ID))
                    .thenReturn(Optional.of(authorization(FleetAuthorizationStatus.REFUSED)));

            WorkorderFleetAuthorization resolved = service.resolve(
                    WORKORDER_ID, FleetAuthorizationResolution.CANCELLED, "customer will self-pay", "advisor-1");

            assertThat(resolved.getStatus()).isEqualTo(FleetAuthorizationStatus.CANCELLED);
            assertThat(resolved.getResolvedBy()).isEqualTo("advisor-1");
            assertThat(resolved.getResolutionReason()).isEqualTo("customer will self-pay");
        }

        @Test
        @DisplayName("a granted authorization cannot be resolved, because there is nothing to resolve")
        void grantedCannotBeResolved() {
            when(authorizationRepository.findByWorkorderId(WORKORDER_ID))
                    .thenReturn(Optional.of(authorization(FleetAuthorizationStatus.GRANTED)));

            assertThatThrownBy(() -> service.resolve(
                            WORKORDER_ID, FleetAuthorizationResolution.CANCELLED, "reason", "advisor-1"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("resolving an unrequested authorization is refused with a 404-shaped exception")
        void unrequestedCannotBeResolved() {
            when(authorizationRepository.findByWorkorderId(WORKORDER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolve(
                            WORKORDER_ID, FleetAuthorizationResolution.CANCELLED, "reason", "advisor-1"))
                    .isInstanceOf(
                            com.positivity.workorder.internal.exception.FleetAuthorizationNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("recording a vendor decision")
    class RecordingDecision {

        @Test
        @DisplayName("granted and refused are preserved without reinterpretation")
        void decisionsArePreservedAsGiven() {
            WorkorderFleetAuthorization pending = authorization(FleetAuthorizationStatus.PENDING);
            when(authorizationRepository.findByWorkorderId(WORKORDER_ID)).thenReturn(Optional.of(pending));

            service.recordDecision(
                    WORKORDER_ID,
                    UUID.fromString("019200aa-0000-7000-8000-0000000000d1"),
                    FleetAuthorizationStatus.REFUSED,
                    "NO_COVER",
                    "Vehicle not on an active fleet contract",
                    null);

            assertThat(pending.getStatus()).isEqualTo(FleetAuthorizationStatus.REFUSED);
            assertThat(pending.getVendorReasonCode()).isEqualTo("NO_COVER");
        }

        @Test
        @DisplayName("a decision for a workorder that was never asked is not silently dropped nor does it crash")
        void decisionForUnknownWorkorderIsHandled() {
            when(authorizationRepository.findByWorkorderId(WORKORDER_ID)).thenReturn(Optional.empty());

            service.recordDecision(
                    WORKORDER_ID,
                    UUID.fromString("019200aa-0000-7000-8000-0000000000d1"),
                    FleetAuthorizationStatus.GRANTED,
                    null,
                    null,
                    null);

            verify(authorizationRepository, never()).save(any());
        }
    }

    private void withWorkorder(UUID vehicleId) {
        Workorder workorder = new Workorder();
        workorder.setId(WORKORDER_ID);
        workorder.setCrmPartyId(PARTY_ID);
        workorder.setVehicleId(vehicleId);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));
    }

    private void withRequiredPolicy() {
        ExtBillingRulesReplica rules = new ExtBillingRulesReplica();
        rules.setPartyId(PARTY_ID);
        rules.setFleetAuthorizationRequired(true);
        rules.setFleetSupplierRef("michelin-de");
        when(billingRulesRepository.findById(PARTY_ID)).thenReturn(Optional.of(rules));
    }

    private static UUID vehicleId() {
        return UUID.fromString("019200aa-0000-7000-8000-0000000000e1");
    }

    private static ExtVehicleReplica vehicle(String plate, String vin, String unitNumber) {
        ExtVehicleReplica replica = new ExtVehicleReplica();
        replica.setVehicleId(vehicleId());
        replica.setLicensePlate(plate);
        replica.setVin(vin);
        replica.setUnitNumber(unitNumber);
        return replica;
    }

    private static WorkorderFleetAuthorization authorization(FleetAuthorizationStatus status) {
        return WorkorderFleetAuthorization.builder()
                .workorderFleetAuthorizationId(UUID.fromString("019200aa-0000-7000-8000-0000000000a1"))
                .workorderId(WORKORDER_ID)
                .supplierRef("michelin-de")
                .status(status)
                .requestedAt(NOW.minusSeconds(60))
                .build();
    }
}
