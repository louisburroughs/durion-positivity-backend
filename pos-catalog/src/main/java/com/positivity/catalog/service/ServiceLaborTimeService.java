package com.positivity.catalog.service;

import com.positivity.catalog.service.model.LaborTimeQuoteRequest;
import com.positivity.catalog.service.model.LaborTimeQuoteResponse;
import org.jspecify.annotations.NonNull;

/**
 * Vehicle-specific labor-time resolution at quote time — the platform's second scoped
 * synchronous cross-module read (ADR-0044 amendment 2026-09-02, ADR-0058 §5).
 *
 * <p><strong>Approved caller:</strong> <em>pos-workorder</em> only ({@code LABOR} estimate-item
 * defaulting). The allowlisted edge is the named client class
 * {@code CatalogLaborTimeClientImpl} in pos-workorder, enforced file-scoped in the platform
 * {@code DomainWallsTest} — a further caller argues its own case in a new ADR amendment rather
 * than inheriting this one. The vehicle-keyed matrix cannot ride events: it is large, licensed,
 * and query-shaped, and QUERY_ONLY sources may never be replicated at all (ADR-0058 §4); the
 * degraded/offline path is the vehicle-agnostic default hours on the catalog service fact.
 *
 * <p><strong>Degradation contract:</strong> this read never throws for a miss or a vendor-side
 * failure. No stored row, no live answer, and no default hours surface as the typed
 * {@link LaborTimeQuoteResponse.Status} outcomes — callers must render the estimate line
 * without a prefill rather than fail their flow.
 */
public interface ServiceLaborTimeService {

    /**
     * Resolves the applicable labor time for (service operation, vehicle).
     *
     * @param request the operation, vehicle key (null fields widen), and time-class preference
     * @return a typed answer; never {@code null}, never a leaked provider error
     */
    @NonNull
    LaborTimeQuoteResponse resolveLaborTime(@NonNull LaborTimeQuoteRequest request);
}
