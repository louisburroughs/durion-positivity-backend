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
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.registry.AdapterRegistry;
import com.positivity.supplier.internal.registry.SupplierCodecs;
import com.positivity.supplier.internal.repository.SupplierWorkorderAuthorizationRepository;
import com.positivity.supplier.internal.service.SupplierProfileResolver;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import com.positivity.supplier.internal.spi.SupplierWorkorderAuthorizationPort;
import com.positivity.supplier.service.model.FleetAuthorizationResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

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
    private final WorkorderAuthorizationTransactions transactions;

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

        // Through the injected bean, NEVER a method on this. A self-invocation would bypass the
        // Spring proxy and silently disable these methods' @Transactional semantics -- see
        // WorkorderAuthorizationTransactions for what that cost (SonarCloud java:S2229).
        SupplierWorkorderAuthorizationEntity row = transactions.openRow(binding, supplierRef, request);

        SupplierRequestSpec spec = codec.buildAuthorizationRequest(request, partnerId, API_MAJOR_VERSION);
        SupplierHttpResponse response = baseClient.exchange(SupplierRequests.toHttpRequest(binding, spec));

        if (!response.isSuccess()) {
            // Not a denial. The vendor may never have seen the request, or may have created an
            // authorization and failed to tell us -- and those two are indistinguishable from here,
            // which is exactly why a human has to look rather than a loop retrying.
            return transactions.parkForReview(
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
            return transactions.parkForReview(row, "vendor answer could not be read: " + e.getMessage());
        }

        return transactions.applyDecision(row, decision);
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
