package com.positivity.price.service;

import com.positivity.price.service.model.LaborRateQuoteRequest;
import com.positivity.price.service.model.LaborRateQuoteResponse;
import org.jspecify.annotations.NonNull;

/**
 * The granted contract for resolving a shop's hourly labor rate (#1575 Tier 0, T0-3;
 * ADR-0026 D1–D5, ADR-0044 amendment 2026-09-07).
 *
 * <p>The counterpart to pos-catalog's {@code ServiceLaborTimeService}: that one answers how long
 * an operation takes on a vehicle, this one answers what an hour of it costs at a location, and
 * pos-workorder multiplies them into a LABOR line. Splitting them follows ADR-0054 — the rate is
 * a sell price and belongs to pos-price — and keeps either half replaceable without touching the
 * other.
 *
 * <p>Why a synchronous edge rather than an event: the matrix makes the answer a function of the
 * quote (which conditions the writer agreed apply), not a value that can be broadcast and
 * cached. A replica would have to carry the whole rate and matrix table per location and then
 * re-implement the compounding, which is the derivation this method exists to own.
 *
 * <p>Never throws for a miss. A scope with no rate in force answers {@code NO_RATE_AVAILABLE}
 * and the caller leaves the price for the writer to type.
 */
public interface ShopLaborRateService {

    @NonNull
    LaborRateQuoteResponse resolveLaborRate(@NonNull LaborRateQuoteRequest request);
}
