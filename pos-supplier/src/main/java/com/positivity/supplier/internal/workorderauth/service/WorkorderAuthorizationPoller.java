package com.positivity.supplier.internal.workorderauth.service;

import com.positivity.supplier.internal.adapter.michelins2s.MichelinS2SWorkorderAuthCodec;
import com.positivity.supplier.internal.adapter.michelins2s.WorkorderAuthDecodeException;
import com.positivity.supplier.internal.client.SupplierBaseClient;
import com.positivity.supplier.internal.client.SupplierHttpRequest;
import com.positivity.supplier.internal.client.SupplierHttpResponse;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.domain.model.SupplierWorkorderAuthorization;
import com.positivity.supplier.internal.entity.SupplierWorkorderAuthorizationEntity;
import com.positivity.supplier.internal.enums.WorkorderAuthorizationStatus;
import com.positivity.supplier.internal.registry.AdapterRegistry;
import com.positivity.supplier.internal.registry.AdapterResolution;
import com.positivity.supplier.internal.repository.SupplierWorkorderAuthorizationRepository;
import com.positivity.supplier.internal.service.SupplierProfileResolver;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Resolves authorizations the vendor accepted but did not decide (CAP-323 #1229).
 *
 * <h2>Why this exists at all</h2>
 *
 * The S2S API may answer a request with 202 and a {@code Location} instead of a decision. Without
 * something that goes back and reads that location, a 202 is indistinguishable from a request that
 * vanished: the shop waits, the fleet has decided, and nobody connects the two. This is the piece
 * that makes an asynchronous acceptance an actual answer rather than a promise nobody collects.
 *
 * <h2>Giving up is part of the design</h2>
 *
 * A row that has been pending past {@code pending-timeout} stops being polled and goes to
 * {@code MANUAL_REVIEW}. Polling forever would be cheaper to write and worse: an authorization that
 * a vendor will never resolve would be indistinguishable from one it is about to, and the shop
 * waiting on it would never be told to stop waiting.
 */
@Slf4j
@Service
public class WorkorderAuthorizationPoller {

    private final SupplierWorkorderAuthorizationRepository authorizationRepository;
    private final SupplierProfileResolver profileResolver;
    private final AdapterRegistry adapterRegistry;
    private final SupplierBaseClient baseClient;
    private final WorkorderAuthorizationRunner runner;
    private final Clock clock;

    /** How many rows one tick will read. Bounded so a backlog cannot monopolise a tick. */
    private final int batchSize;

    /**
     * How long an authorization may stay undecided before a human is asked to look.
     *
     * <p>Configurable because it is a claim about a vendor's turnaround, and that is only learned
     * from a live vendor. Bounded because "never" is not an answer a shop can act on.
     */
    private final Duration pendingTimeout;

    public WorkorderAuthorizationPoller(
            SupplierWorkorderAuthorizationRepository authorizationRepository,
            SupplierProfileResolver profileResolver,
            AdapterRegistry adapterRegistry,
            SupplierBaseClient baseClient,
            WorkorderAuthorizationRunner runner,
            Clock clock,
            @Value("${pos.supplier.workorderauth.poll-batch-size:50}") int batchSize,
            @Value("${pos.supplier.workorderauth.pending-timeout:PT24H}") Duration pendingTimeout) {
        this.authorizationRepository = authorizationRepository;
        this.profileResolver = profileResolver;
        this.adapterRegistry = adapterRegistry;
        this.baseClient = baseClient;
        this.runner = runner;
        this.clock = clock;
        this.batchSize = batchSize;
        this.pendingTimeout = pendingTimeout;
    }

    /** Polls undecided authorizations, least recently polled first. */
    @Scheduled(fixedDelayString = "${pos.supplier.workorderauth.poll-interval-ms:120000}")
    public void pollPendingAuthorizations() {
        List<SupplierWorkorderAuthorizationEntity> pending =
                authorizationRepository.findAwaitingPoll(WorkorderAuthorizationStatus.PENDING, Limit.of(batchSize));

        for (SupplierWorkorderAuthorizationEntity row : pending) {
            try {
                pollOne(row);
            } catch (Exception e) {
                // One vendor's problem must not stop the others being resolved. The row keeps its
                // PENDING status, so the next tick tries it again.
                log.warn(
                        "Polling authorization {} for workorder {} failed: {}",
                        row.getSupplierWorkorderAuthorizationId(),
                        row.getWorkorderId(),
                        e.toString(),
                        e);
            }
        }
    }

    private void pollOne(@NonNull SupplierWorkorderAuthorizationEntity row) {
        Instant now = Instant.now(clock);
        if (row.getRequestedAt() != null
                && row.getRequestedAt().plus(pendingTimeout).isBefore(now)) {
            runner.parkForReview(
                    row,
                    "vendor accepted the authorization but did not decide within " + pendingTimeout
                            + "; it has not been asked again");
            return;
        }

        String pollPath = pollPathFor(row.getPollLocation());
        if (pollPath == null) {
            // Accepted with nowhere to look. Nothing further is possible for this row, and the
            // codec should have refused it at 202 -- so reaching here means a row predates that
            // check or was written by hand.
            runner.parkForReview(row, "authorization is pending with no poll location, so its decision cannot be read");
            return;
        }

        SupplierRef supplierRef = new SupplierRef(row.getSupplierRef());
        ResolvedBinding binding =
                profileResolver.resolveBinding(supplierRef, SupplierCapability.WORKORDER_AUTHORIZATION);
        MichelinS2SWorkorderAuthCodec codec = resolveCodec(binding);
        String partnerId =
                profileResolver.resolvePartyContext(supplierRef, null).billing().getAccountNumber();

        SupplierRequestSpec spec = codec.buildPollRequest(pollPath, partnerId == null ? "" : partnerId);
        SupplierHttpResponse response = baseClient.exchange(toHttpRequest(binding, spec));

        if (!response.isSuccess()) {
            // Left PENDING deliberately: a failed poll says nothing about the vendor's decision, and
            // the timeout above is what eventually stops this becoming an infinite wait.
            log.debug(
                    "Poll of authorization {} did not succeed ({}); will retry",
                    row.getSupplierWorkorderAuthorizationId(),
                    response.outcome());
            row.setLastPolledAt(now);
            authorizationRepository.save(row);
            return;
        }

        SupplierWorkorderAuthorization decision;
        try {
            decision = codec.decodeDecision(
                    response.body(),
                    response.httpStatus() == null ? 0 : response.httpStatus(),
                    response.header("Location"),
                    row.getWorkorderId());
        } catch (WorkorderAuthDecodeException e) {
            runner.parkForReview(row, "vendor answer could not be read while polling: " + e.getMessage());
            return;
        }

        runner.applyDecision(row, decision);
    }

    /**
     * Turns a vendor {@code Location} into a path this client can request.
     *
     * <p>An absolute URL is reduced to its path because the binding owns the base URL: honouring a
     * vendor-supplied host would let a redirect move an authenticated, credential-bearing request to
     * a host nobody configured.
     */
    @Nullable
    private static String pollPathFor(@Nullable String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        String trimmed = location.trim();
        try {
            URI uri = URI.create(trimmed);
            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                return null;
            }
            return path.startsWith("/") ? path : "/" + path;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @NonNull
    private SupplierHttpRequest toHttpRequest(@NonNull ResolvedBinding binding, @NonNull SupplierRequestSpec spec) {
        return new SupplierHttpRequest(
                binding,
                HttpMethod.valueOf(spec.method()),
                spec.pathSuffix(),
                spec.queryParams(),
                spec.body(),
                spec.contentType(),
                spec.accept(),
                spec.idempotent(),
                spec.headers());
    }

    @NonNull
    private MichelinS2SWorkorderAuthCodec resolveCodec(@NonNull ResolvedBinding binding) {
        AdapterResolution resolution = adapterRegistry.resolve(
                SupplierCapability.WORKORDER_AUTHORIZATION, binding.family(), binding.version());
        if (resolution instanceof AdapterResolution.Resolved resolved
                && resolved.codec() instanceof MichelinS2SWorkorderAuthCodec codec) {
            return codec;
        }
        throw new IllegalStateException("No workorder authorization codec registered for " + binding.family());
    }
}
