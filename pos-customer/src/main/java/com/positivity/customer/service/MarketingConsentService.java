package com.positivity.customer.service;

import com.positivity.customer.internal.dto.ConsentEventResponse;
import com.positivity.customer.internal.dto.MarketingConsentDecision;
import com.positivity.customer.internal.dto.MarketingConsentSummaryResponse;
import com.positivity.customer.internal.dto.PagedResponse;
import com.positivity.customer.internal.dto.UpdateMarketingConsentRequest;
import com.positivity.customer.internal.enums.MarketingChannel;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Public API for marketing consent (Stories #1138, #1139).
 *
 * <p>{@link #resolveEligibility} is the one call a send pipeline should make: it folds the
 * account-level master gate, the governing contact's per-channel consent, and the hard
 * suppression list into a single decision. Reading the raw consent fields and re-deriving
 * that logic elsewhere is how the three drift apart.
 */
public interface MarketingConsentService {

    /** Consent + account gate + suppression, folded into one allow/deny with a reason. */
    @NonNull
    MarketingConsentDecision resolveEligibility(@NonNull UUID partyId, @NonNull MarketingChannel channel);

    @NonNull
    MarketingConsentSummaryResponse getConsent(@NonNull UUID partyId);

    /** Writes one immutable {@code ConsentEvent} per channel that actually changed. */
    @NonNull
    MarketingConsentSummaryResponse updateConsent(
            @NonNull UUID partyId, @NonNull UpdateMarketingConsentRequest request);

    /** Set or clear the commercial-account master gate (decision O-2). */
    @NonNull
    MarketingConsentSummaryResponse setAccountMarketingOptOut(@NonNull UUID partyId, boolean optOut);

    @NonNull
    PagedResponse<ConsentEventResponse> getConsentHistory(@NonNull UUID partyId, int page, int size);
}
