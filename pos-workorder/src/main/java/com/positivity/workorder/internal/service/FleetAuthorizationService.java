package com.positivity.workorder.internal.service;

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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fleet payment authorization: whether the payer has agreed to fund this workorder (#1346).
 *
 * <h2>What this is, in the domain's words</h2>
 *
 * <em>Fleet payment authorization</em> — a Commercial Customer agreeing to pay. It is not estimate
 * customer consent, not change-request customer consent, and not workorder acceptance.
 * {@code AWAITING_APPROVAL} keeps its existing meaning and is untouched, because renaming or
 * overloading an existing state would need a state-machine migration and would conflate "who is
 * paying" with "how far along is the work".
 *
 * <h2>Required by policy, not per job</h2>
 *
 * Whether authorization is needed comes from the payer's billing rules, replicated from pos-invoice.
 * The same commercial account answers the same way for every workorder it pays for. Deciding it per
 * workorder would make it something an advisor can forget — and an unset gate is an open gate.
 *
 * <h2>The gate is closed by default and opens only on a grant</h2>
 *
 * Pending, manual review, refused, cancelled and never-asked all keep it shut. Two of those are not
 * the fleet's fault, and the distinction is preserved everywhere it is shown, but none of them is
 * permission to spend somebody else's money.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FleetAuthorizationService {

    private final WorkorderRepository workorderRepository;
    private final WorkorderFleetAuthorizationRepository authorizationRepository;
    private final ExtBillingRulesReplicaRepository billingRulesRepository;
    private final ExtVehicleReplicaRepository vehicleRepository;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final Clock clock;

    /**
     * Refuses the start of a workorder whose fleet payment authorization is unresolved.
     *
     * <p>Called from the state machine rather than a controller, so the rule holds however work is
     * started — the domain ruled this must be enforced in the backend command and that no role may
     * bypass it. There is deliberately no override: a financial-risk override was considered and
     * declined.
     *
     * <p>Also stamps when the block first happened, which is what the delayed release of held
     * resources is measured from. A job nobody has tried to start is not holding a bay up.
     *
     * @throws IllegalStateException when authorization is required and not granted
     */
    @Transactional
    public void assertMayStart(@NonNull UUID workorderId) {
        Optional<WorkorderFleetAuthorization> authorization = authorizationRepository.findByWorkorderId(workorderId);

        if (!isRequired(workorderId)) {
            return;
        }

        FleetAuthorizationStatus status = authorization
                .map(WorkorderFleetAuthorization::getStatus)
                .orElse(FleetAuthorizationStatus.NOT_REQUESTED);
        if (status.permitsStart()) {
            return;
        }

        authorization.ifPresent(this::stampFirstBlocked);
        throw new IllegalStateException(String.format(
                "Workorder %s cannot be started: fleet payment authorization is %s. %s",
                workorderId, status, explain(status, authorization.orElse(null))));
    }

    /** Whether this workorder's payer requires fleet authorization before work may start. */
    @Transactional(readOnly = true)
    public boolean isRequired(@NonNull UUID workorderId) {
        return billingRulesFor(workorderId)
                .map(ExtBillingRulesReplica::isFleetAuthorizationRequired)
                .orElse(false);
    }

    /**
     * Opens an authorization for a workorder and asks the fleet program, if one is required.
     *
     * <p>Called automatically when a fleet-covered workorder is approved, and again by an advisor
     * after a scope revision. Idempotent in the direction that matters: an authorization already
     * granted or already awaiting an answer is not asked again, because a second request opens a
     * second authorization at the vendor and somebody has to unpick that with a fleet manager.
     *
     * @return the authorization as it now stands, or empty when none is required
     */
    @Transactional
    @NonNull
    public Optional<WorkorderFleetAuthorization> requestAuthorization(@NonNull UUID workorderId) {
        Optional<ExtBillingRulesReplica> rules = billingRulesFor(workorderId);
        if (rules.isEmpty() || !rules.get().isFleetAuthorizationRequired()) {
            return Optional.empty();
        }
        String supplierRef = rules.get().getFleetSupplierRef();

        Optional<WorkorderFleetAuthorization> existing = authorizationRepository.findByWorkorderId(workorderId);
        if (existing.isPresent() && !mayAskAgain(existing.get().getStatus())) {
            // Already granted, or already waiting on the fleet. Asking again would open a second
            // authorization at the vendor against the same contract.
            log.debug(
                    "Not re-requesting fleet authorization for workorder {}: status is {}",
                    workorderId,
                    existing.get().getStatus());
            return existing;
        }

        Instant now = Instant.now(clock);
        WorkorderFleetAuthorization authorization = existing.orElseGet(() -> WorkorderFleetAuthorization.builder()
                .workorderId(workorderId)
                .supplierRef(supplierRef)
                .build());
        authorization.setSupplierRef(supplierRef);
        authorization.setRequestedAt(now);
        // Cleared on a re-request: a stale refusal reason beside a fresh request reads as if the
        // fleet had just refused again.
        authorization.setVendorReasonCode(null);
        authorization.setVendorReasonText(null);
        authorization.setReviewReason(null);
        authorization.setDecidedAt(null);

        Optional<WorkorderFleetAuthRequestedV1> command = buildCommand(workorderId, supplierRef, authorization);
        if (command.isEmpty()) {
            // Required, but this side cannot name the vehicle, so nobody can be asked. Parked for a
            // person rather than sent: an unnamed vehicle would come back as a refusal, and a
            // refusal means the fleet declined — which it did not.
            authorization.setStatus(FleetAuthorizationStatus.MANUAL_REVIEW);
            authorization.setReviewReason(
                    "no vehicle identity is known for this workorder, so no fleet program can be asked");
            log.warn("Cannot request fleet authorization for workorder {}: vehicle identity unknown", workorderId);
            return Optional.of(authorizationRepository.save(authorization));
        }

        authorization.setStatus(FleetAuthorizationStatus.PENDING);
        WorkorderFleetAuthorization saved = authorizationRepository.save(authorization);

        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer != null) {
            writer.publish(
                    WorkorderFleetAuthRequestedV1.EVENT_TYPE,
                    WorkorderFleetAuthRequestedV1.SCHEMA_VERSION,
                    workorderId,
                    command.get());
        }
        log.info("Requested fleet payment authorization for workorder {} from {}", workorderId, supplierRef);
        return Optional.of(saved);
    }

    /**
     * Records the fleet program's decision, exactly as it was given.
     *
     * <p>Granted, refused and pending are preserved without reinterpretation, per the domain ruling.
     * Nothing here changes a workorder's status: an authorization outcome is an input to workexec's
     * workflow, not a transition in it.
     */
    @Transactional
    public void recordDecision(
            @NonNull UUID workorderId,
            @NonNull UUID vendorProfileId,
            @NonNull FleetAuthorizationStatus status,
            @Nullable String reasonCode,
            @Nullable String reasonText,
            @Nullable String contractReference) {
        Optional<WorkorderFleetAuthorization> found = authorizationRepository.findByWorkorderId(workorderId);
        if (found.isEmpty()) {
            // A decision for a workorder nobody asked about. Recorded rather than dropped: the fleet
            // answered, and an answer nobody can see is worse than an unexpected one.
            log.warn("Fleet authorization decision for workorder {} that has no request recorded", workorderId);
            return;
        }

        WorkorderFleetAuthorization authorization = found.get();
        authorization.setVendorProfileId(vendorProfileId);
        authorization.setStatus(status);
        authorization.setVendorReasonCode(reasonCode);
        authorization.setVendorReasonText(reasonText);
        authorization.setContractReference(contractReference);
        authorization.setDecidedAt(Instant.now(clock));
        authorizationRepository.save(authorization);

        log.info("Fleet payment authorization for workorder {} is {}", workorderId, status);
    }

    /**
     * Records an advisor's decision about an authorization that did not come back granted.
     *
     * <p>Nothing is automatic. The domain ruled a refusal leaves the workorder open, is not
     * cancelled and is not converted to customer-pay, so the choice is recorded with who made it and
     * why rather than inferred from the workorder's later shape.
     */
    @Transactional
    @NonNull
    public WorkorderFleetAuthorization resolve(
            @NonNull UUID workorderId,
            @NonNull FleetAuthorizationResolution resolution,
            @NonNull String reason,
            @NonNull String actorId) {
        WorkorderFleetAuthorization authorization = authorizationRepository
                .findByWorkorderId(workorderId)
                .orElseThrow(() -> new com.positivity.workorder.internal.exception.FleetAuthorizationNotFoundException(
                        workorderId));

        if (authorization.getStatus() == FleetAuthorizationStatus.GRANTED) {
            // Resolving a granted authorization would discard permission the fleet actually gave.
            throw new IllegalStateException(
                    "Workorder " + workorderId + " has a granted fleet payment authorization and needs no resolution");
        }

        authorization.setResolution(resolution);
        authorization.setResolutionReason(reason);
        authorization.setResolvedBy(actorId);
        authorization.setResolvedAt(Instant.now(clock));
        if (resolution == FleetAuthorizationResolution.CANCELLED) {
            authorization.setStatus(FleetAuthorizationStatus.CANCELLED);
        }
        log.info("Fleet payment authorization for workorder {} resolved as {} by {}", workorderId, resolution, actorId);
        return authorizationRepository.save(authorization);
    }

    /** The authorization recorded for a workorder, if any. */
    @Transactional(readOnly = true)
    @NonNull
    public Optional<WorkorderFleetAuthorization> find(@NonNull UUID workorderId) {
        return authorizationRepository.findByWorkorderId(workorderId);
    }

    /**
     * Whether a fresh request may be sent.
     *
     * <p>Granted needs nothing. Pending is already waiting, and asking again while a fleet is
     * deciding opens a second authorization against one contract.
     */
    private static boolean mayAskAgain(@NonNull FleetAuthorizationStatus status) {
        return status != FleetAuthorizationStatus.GRANTED && status != FleetAuthorizationStatus.PENDING;
    }

    /** Stamps when a start was first refused, leaving an earlier stamp alone. */
    private void stampFirstBlocked(@NonNull WorkorderFleetAuthorization authorization) {
        if (authorization.getFirstBlockedAt() == null) {
            authorization.setFirstBlockedAt(Instant.now(clock));
            authorizationRepository.save(authorization);
        }
    }

    @NonNull
    private Optional<ExtBillingRulesReplica> billingRulesFor(@NonNull UUID workorderId) {
        return workorderRepository
                .findById(workorderId)
                .map(Workorder::getCrmPartyId)
                .filter(partyId -> partyId != null && !partyId.isBlank())
                .flatMap(billingRulesRepository::findById);
    }

    /**
     * Builds the request, or empty when this side cannot name the vehicle.
     *
     * <p>Identity comes from the replicated vehicle fact. Empty is a real outcome rather than an
     * error: the replica may not yet hold a vehicle this workorder references, and that is a gap to
     * report, not a reason to send a fleet program a request it can only refuse.
     */
    @NonNull
    private Optional<WorkorderFleetAuthRequestedV1> buildCommand(
            @NonNull UUID workorderId,
            @NonNull String supplierRef,
            @NonNull WorkorderFleetAuthorization authorization) {
        Optional<ExtVehicleReplica> vehicle = workorderRepository
                .findById(workorderId)
                .map(Workorder::getVehicleId)
                .filter(java.util.Objects::nonNull)
                .flatMap(vehicleRepository::findById);
        if (vehicle.isEmpty()) {
            return Optional.empty();
        }

        ExtVehicleReplica identity = vehicle.get();
        try {
            return Optional.of(new WorkorderFleetAuthRequestedV1(
                    workorderId,
                    supplierRef,
                    null,
                    identity.getLicensePlate(),
                    identity.getVin(),
                    identity.getUnitNumber(),
                    identity.getOdometerValue() == null ? null : String.valueOf(identity.getOdometerValue()),
                    nextAttempt(authorization),
                    List.of()));
        } catch (IllegalArgumentException e) {
            // The replica holds the vehicle but none of the fields a fleet identifies it by.
            log.warn("Vehicle for workorder {} carries no plate, VIN or unit number", workorderId);
            return Optional.empty();
        }
    }

    private static int nextAttempt(@NonNull WorkorderFleetAuthorization authorization) {
        return authorization.getRequestedAt() == null ? 1 : 2;
    }

    /** Operator-facing explanation, kept distinct for the statuses that are not the fleet's doing. */
    @NonNull
    private static String explain(
            @NonNull FleetAuthorizationStatus status, @Nullable WorkorderFleetAuthorization authorization) {
        return switch (status) {
            case NOT_REQUESTED -> "It has not been requested yet.";
            case PENDING -> "The fleet program has not answered yet.";
            case REFUSED ->
                "The fleet program declined to pay"
                        + (authorization == null || authorization.getVendorReasonText() == null
                                ? "."
                                : ": " + authorization.getVendorReasonText());
            // Deliberately not phrased as a refusal: nobody could get an answer, which is a
            // different thing and leads an advisor somewhere different.
            case MANUAL_REVIEW ->
                "No answer could be obtained and it needs review"
                        + (authorization == null || authorization.getReviewReason() == null
                                ? "."
                                : ": " + authorization.getReviewReason());
            case CANCELLED -> "It was cancelled; this workorder needs another payer.";
            case GRANTED -> "";
        };
    }
}
