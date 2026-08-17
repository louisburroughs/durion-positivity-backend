package com.positivity.supplier.internal.workorderauth.service;

import com.positivity.supplier.internal.adapter.michelins2s.MichelinS2SWorkorderAuthCodec;
import com.positivity.supplier.internal.adapter.michelins2s.WorkorderAuthDecodeException;
import com.positivity.supplier.internal.client.SupplierBaseClient;
import com.positivity.supplier.internal.client.SupplierHttpResponse;
import com.positivity.supplier.internal.client.SupplierRequests;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.domain.model.SupplierWorkorderAuthorization;
import com.positivity.supplier.internal.domain.model.WorkorderAuthorizationRequest;
import com.positivity.supplier.internal.entity.SupplierWorkorderAuthorizationEntity;
import com.positivity.supplier.internal.enums.WorkorderApprovalStatus;
import com.positivity.supplier.internal.enums.WorkorderAuthorizationStatus;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.registry.AdapterRegistry;
import com.positivity.supplier.internal.registry.SupplierCodecs;
import com.positivity.supplier.internal.repository.SupplierWorkorderAuthorizationRepository;
import com.positivity.supplier.internal.service.SupplierProfileResolver;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import com.positivity.supplier.internal.spi.SupplierWorkorderAuthorizationPort;
import com.positivity.supplier.service.model.FleetAuthorizationResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asks a fleet program to authorize work, and records what it said (CAP-323 #1229).
 *
 * <h2>The row is written before the vendor is called</h2>
 *
 * In its own transaction, and committed. If the call then fails, the record of having asked
 * survives — which is the difference between an operator seeing "requested, no answer" and seeing
 * nothing at all. A shop waiting on a fleet authorization that was never recorded has no way to
 * discover it is waiting.
 *
 * <h2>Nothing here is retried automatically</h2>
 *
 * Creating an authorization is a commercial act at the fleet: a blind re-send after an ambiguous
 * failure can open a second authorization against the same contract, which somebody then has to
 * unpick with a fleet manager. So an ambiguous outcome parks the row in {@code MANUAL_REVIEW} with
 * the reason attached, and a human decides whether to ask again. That is more work than a retry loop
 * and it is the right amount of work for the risk.
 *
 * <h2>Unreachable is never denied</h2>
 *
 * A vendor that cannot be reached, a token that would not mint, a response nobody can parse — none
 * of these are refusals, and none are published as one. They stay here as {@code MANUAL_REVIEW}.
 * Telling a shop the fleet said no when in fact nobody asked sends it to argue with a fleet manager
 * about a decision that was never made.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkorderAuthorizationRunner implements SupplierWorkorderAuthorizationPort {

    /**
     * The {@code API-Version} major the S2S v1 URIs carry.
     *
     * <p>A constant rather than configuration because the spec requires the header to match the URI
     * version, and the URIs this codec builds are {@code /api/v1/...}. Making it configurable would
     * only create the possibility of the two disagreeing.
     */
    private static final String API_MAJOR_VERSION = "1";

    private final SupplierProfileResolver profileResolver;
    private final AdapterRegistry adapterRegistry;
    private final SupplierBaseClient baseClient;
    private final SupplierWorkorderAuthorizationRepository authorizationRepository;
    private final WorkorderAuthorizationPublisher publisher;
    private final Clock clock;

    @Override
    @NonNull
    public SupplierWorkorderAuthorization requestAuthorization(
            @NonNull SupplierRef supplierRef, @NonNull WorkorderAuthorizationRequest request) {
        return toDomain(requestAuthorizationRow(supplierRef, request));
    }

    /**
     * Requests authorization and returns the stored row.
     *
     * <p>The row rather than the decision, because callers inside this module go on to work with
     * the operational state — {@code MANUAL_REVIEW}, the review reason, the approval lifecycle —
     * none of which is part of the vendor's answer and none of which belongs in the port's return
     * type.
     *
     * @param supplierRef the fleet program to ask
     * @param request     what is being asked for
     * @return the authorization as it now stands, which may still be {@code PENDING}
     * @throws SupplierConfigurationException when the profile, binding or codec is not configured
     */
    @NonNull
    public SupplierWorkorderAuthorizationEntity requestAuthorizationRow(
            @NonNull SupplierRef supplierRef, @NonNull WorkorderAuthorizationRequest request) {
        ResolvedBinding binding =
                profileResolver.resolveBinding(supplierRef, SupplierCapability.WORKORDER_AUTHORIZATION);
        MichelinS2SWorkorderAuthCodec codec = SupplierCodecs.require(
                adapterRegistry,
                binding,
                SupplierCapability.WORKORDER_AUTHORIZATION,
                MichelinS2SWorkorderAuthCodec.class);
        String partnerId = S2SPartnerId.resolve(profileResolver, supplierRef);

        SupplierWorkorderAuthorizationEntity row = openRow(binding, supplierRef, request);

        SupplierRequestSpec spec = codec.buildAuthorizationRequest(request, partnerId, API_MAJOR_VERSION);
        SupplierHttpResponse response = baseClient.exchange(SupplierRequests.toHttpRequest(binding, spec));

        if (!response.isSuccess()) {
            // Not a denial. The vendor may never have seen the request, or may have created an
            // authorization and failed to tell us -- and those two are indistinguishable from here,
            // which is exactly why a human has to look rather than a loop retrying.
            return parkForReview(
                    row,
                    "vendor exchange failed: " + response.outcome()
                            + (response.failureDetail() == null ? "" : " — " + response.failureDetail()));
        }

        SupplierWorkorderAuthorization decision;
        try {
            decision = codec.decodeDecision(
                    response.body(),
                    response.httpStatus() == null ? 0 : response.httpStatus(),
                    response.header("Location"),
                    request.workorderId());
        } catch (WorkorderAuthDecodeException e) {
            // The vendor answered something. We could not read it. Guessing either way decides
            // whether a shop does work, so neither guess is available.
            return parkForReview(row, "vendor answer could not be read: " + e.getMessage());
        }

        return applyDecision(row, decision);
    }

    /**
     * Records a decision against an existing row and publishes it if terminal.
     *
     * <p>Shared with the poller, so an answer that arrives synchronously and one that arrives an
     * hour later are recorded and published by the same code. Two paths would drift, and the one
     * that drifts is the asynchronous one, which is also the one nobody watches.
     */
    @Transactional
    @NonNull
    public SupplierWorkorderAuthorizationEntity applyDecision(
            @NonNull SupplierWorkorderAuthorizationEntity row, @NonNull SupplierWorkorderAuthorization decision) {
        Instant now = Instant.now(clock);
        SupplierWorkorderAuthorizationEntity managed = authorizationRepository
                .findById(row.getSupplierWorkorderAuthorizationId())
                .orElse(row);

        managed.setLastPolledAt(now);
        if (decision.vendorAuthorizationId() != null) {
            managed.setVendorAuthorizationId(decision.vendorAuthorizationId());
        }

        switch (decision.status()) {
            case PENDING -> {
                managed.setStatus(WorkorderAuthorizationStatus.PENDING);
                if (decision.pollLocation() != null) {
                    managed.setPollLocation(decision.pollLocation());
                }
            }
            case GRANTED -> {
                managed.setStatus(WorkorderAuthorizationStatus.GRANTED);
                managed.setContractReference(decision.contractReference());
                managed.setAuthorizedAmount(decision.authorizedAmount());
                managed.setCurrency(decision.currency());
                managed.setDecidedAt(now);
            }
            case DENIED -> {
                managed.setStatus(WorkorderAuthorizationStatus.DENIED);
                managed.setReasonCode(decision.vendorReasonCode());
                managed.setReasonText(decision.vendorReason());
                managed.setDecidedAt(now);
            }
            case NOT_FOUND -> {
                managed.setStatus(WorkorderAuthorizationStatus.NOT_FOUND);
                managed.setReasonCode(decision.vendorReasonCode());
                managed.setReasonText(decision.vendorReason());
                managed.setDecidedAt(now);
            }
        }

        SupplierWorkorderAuthorizationEntity saved = authorizationRepository.save(managed);

        // Published inside the same transaction as the state change, through the outbox: a decision
        // recorded but not published would leave the rest of the platform believing the fleet never
        // answered (ADR-0044 §4).
        publisher.publishIfTerminal(saved, now);
        return saved;
    }

    /**
     * Parks a row for a human and says why.
     *
     * <p>{@code REQUIRES_NEW} so the reason survives whatever the caller does next. A review reason
     * rolled back with the transaction that discovered it is the one piece of information nobody can
     * reconstruct afterwards.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @NonNull
    public SupplierWorkorderAuthorizationEntity parkForReview(
            @NonNull SupplierWorkorderAuthorizationEntity row, @NonNull String reason) {
        SupplierWorkorderAuthorizationEntity managed = authorizationRepository
                .findById(row.getSupplierWorkorderAuthorizationId())
                .orElse(row);
        managed.setStatus(WorkorderAuthorizationStatus.MANUAL_REVIEW);
        managed.setReviewReason(truncate(reason));
        managed.setLastPolledAt(Instant.now(clock));
        log.warn(
                "Workorder authorization for workorder {} at {} needs review: {}",
                managed.getWorkorderId(),
                managed.getSupplierRef(),
                reason);
        return authorizationRepository.save(managed);
    }

    /**
     * Opens (or re-opens) the row for this workorder, committed before the vendor is called.
     *
     * <p>Re-requesting updates the existing row rather than adding a second. Two rows would let a
     * denial and a grant coexist for one workorder with nothing to say which one a shop should act
     * on, and the uniqueness index exists to make that impossible rather than merely unlikely.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @NonNull
    public SupplierWorkorderAuthorizationEntity openRow(
            @NonNull ResolvedBinding binding,
            @NonNull SupplierRef supplierRef,
            @NonNull WorkorderAuthorizationRequest request) {
        Instant now = Instant.now(clock);
        UUID vendorProfileId = binding.profile().getVendorProfileId();

        SupplierWorkorderAuthorizationEntity row = authorizationRepository
                .findByVendorProfileIdAndWorkorderId(vendorProfileId, request.workorderId())
                .orElseGet(() -> SupplierWorkorderAuthorizationEntity.builder()
                        .vendorProfileId(vendorProfileId)
                        .workorderId(request.workorderId())
                        .approvalStatus(WorkorderApprovalStatus.NOT_REQUESTED)
                        .approvalAttempts(0)
                        .build());

        row.setSupplierRef(supplierRef.value());
        row.setStatus(WorkorderAuthorizationStatus.PENDING);
        row.setRequestedAt(now);
        // Cleared on re-request: a stale reason from the previous attempt read as if it described
        // this one.
        row.setReviewReason(null);
        row.setReasonCode(null);
        row.setReasonText(null);
        row.setDecidedAt(null);
        row.setPollLocation(null);
        return authorizationRepository.save(row);
    }
    /** Keeps a vendor's error text inside the column it is stored in. */
    @NonNull
    private static String truncate(@NonNull String reason) {
        return reason.length() <= 1000 ? reason : reason.substring(0, 1000);
    }

    /**
     * The live authorization for a workorder at a named vendor, if one was ever requested.
     *
     * <p>Resolves the alias to a profile first, so an unknown {@code supplierRef} surfaces as the
     * configuration problem it is rather than as "no authorization found" — those two answers lead a
     * service advisor to do very different things.
     */
    @NonNull
    public Optional<SupplierWorkorderAuthorizationEntity> findBySupplierRef(
            @NonNull SupplierRef supplierRef, @NonNull UUID workorderId) {
        ResolvedBinding binding =
                profileResolver.resolveBinding(supplierRef, SupplierCapability.WORKORDER_AUTHORIZATION);
        return authorizationRepository.findByVendorProfileIdAndWorkorderId(
                binding.profile().getVendorProfileId(), workorderId);
    }

    /**
     * The vendor's answer, without this module's operational state.
     *
     * <p>{@code MANUAL_REVIEW} maps to {@code PENDING}: it means nobody got an answer, and the
     * honest thing to tell a caller asking whether work is authorized is "not yet". Mapping it to
     * denied would report a refusal the fleet never made.
     */
    @NonNull
    private static SupplierWorkorderAuthorization toDomain(@NonNull SupplierWorkorderAuthorizationEntity row) {
        return new SupplierWorkorderAuthorization(
                row.getVendorAuthorizationId(),
                row.getWorkorderId(),
                switch (row.getStatus()) {
                    case GRANTED -> SupplierWorkorderAuthorization.Status.GRANTED;
                    case DENIED -> SupplierWorkorderAuthorization.Status.DENIED;
                    case NOT_FOUND -> SupplierWorkorderAuthorization.Status.NOT_FOUND;
                    case PENDING, MANUAL_REVIEW -> SupplierWorkorderAuthorization.Status.PENDING;
                },
                row.getReasonText(),
                row.getReasonCode(),
                row.getContractReference(),
                row.getAuthorizedAmount(),
                row.getCurrency(),
                row.getPollLocation());
    }

    /**
     * Requests authorization and returns the operator-facing projection.
     *
     * <p>The projection is built here rather than in the controller so no controller holds a JPA
     * entity: an entity on a request thread is a lazy-loading accident waiting for a field nobody
     * fetched, and the architecture rules forbid it for that reason.
     */
    @NonNull
    public FleetAuthorizationResponse requestAndProject(
            @NonNull SupplierRef supplierRef, @NonNull WorkorderAuthorizationRequest request) {
        return project(requestAuthorizationRow(supplierRef, request));
    }

    /** The recorded authorization for a workorder at a named vendor, projected. */
    @NonNull
    public Optional<FleetAuthorizationResponse> findProjection(
            @NonNull SupplierRef supplierRef, @NonNull UUID workorderId) {
        return findBySupplierRef(supplierRef, workorderId).map(WorkorderAuthorizationRunner::project);
    }

    /**
     * The operator-facing view, review state included.
     *
     * <p>Unlike the cross-module contract view this does show {@code MANUAL_REVIEW} and its reason.
     * The whole point of that state is that somebody can see it: hiding it here would leave the
     * queue of unapproved, unpaid work with no way to look at it.
     */
    @NonNull
    private static FleetAuthorizationResponse project(@NonNull SupplierWorkorderAuthorizationEntity row) {
        return new FleetAuthorizationResponse(
                row.getWorkorderId(),
                row.getSupplierRef(),
                row.getStatus().name(),
                row.getVendorAuthorizationId(),
                row.getContractReference(),
                row.getAuthorizedAmount(),
                row.getCurrency(),
                row.getReasonCode(),
                row.getReasonText(),
                row.getReviewReason(),
                row.getApprovalStatus().name(),
                row.getRequestedAt(),
                row.getDecidedAt());
    }

    /**
     * The authorizations and completions waiting on a human, newest first.
     *
     * <p>This is the reason {@code MANUAL_REVIEW} exists at all. A state that nothing can list is a
     * state nobody acts on, and the rows in it represent work already performed that a fleet has not
     * agreed to pay for.
     */
    @NonNull
    public List<FleetAuthorizationResponse> findNeedingReview(int limit) {
        return authorizationRepository.findNeedingReview(Limit.of(limit)).stream()
                .map(WorkorderAuthorizationRunner::project)
                .toList();
    }
}
