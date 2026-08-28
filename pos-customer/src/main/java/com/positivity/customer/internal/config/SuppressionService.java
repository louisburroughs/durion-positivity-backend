package com.positivity.customer.internal.config;

import com.positivity.customer.internal.dto.AddSuppressionRequest;
import com.positivity.customer.internal.dto.PagedResponse;
import com.positivity.customer.internal.dto.SuppressionEntryResponse;
import com.positivity.customer.internal.enums.MarketingChannel;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Public API for the hard address-level marketing suppression list (Story #1140).
 *
 * <p>Suppression outranks consent: an address here is never sent to, whatever the party's
 * preferences say. Every send path must consult {@link #isSuppressed} immediately before
 * dispatch, not only at audience-build time.
 */
public interface SuppressionService {

    /** Idempotent: suppressing an already-suppressed address returns the existing entry. */
    @NonNull
    SuppressionEntryResponse add(@NonNull AddSuppressionRequest request);

    void remove(@NonNull UUID suppressionId);

    @NonNull
    PagedResponse<SuppressionEntryResponse> list(@Nullable MarketingChannel channel, int page, int size);

    @NonNull
    List<SuppressionEntryResponse> listForParty(@NonNull UUID partyId);

    boolean isSuppressed(@NonNull MarketingChannel channel, @NonNull String address);

    /**
     * Whether any address previously linked to this party is suppressed on the channel.
     * A cheap pre-filter for audience building; the authoritative check is
     * {@link #isSuppressed} on the resolved address at dispatch time.
     */
    boolean isPartySuppressed(@NonNull UUID partyId, @NonNull MarketingChannel channel);
}
