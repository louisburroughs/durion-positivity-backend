package com.positivity.customer.internal.service;

import com.positivity.customer.internal.dto.CustomerInteractionResponse;
import com.positivity.customer.internal.dto.PagedResponse;
import com.positivity.customer.internal.dto.RecordInteractionRequest;
import com.positivity.customer.internal.enums.InteractionType;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Public API for a party's interaction timeline (Story #1141).
 *
 * <p>Rows arrive from three places — CSR actions through {@link #record}, workorder note
 * events, and campaign-send facts from {@code marketing.events.v1} — and all read back
 * through the same query so "what have we said to this customer lately?" is one call.
 */
public interface CustomerInteractionService {

    @NonNull
    CustomerInteractionResponse record(@NonNull UUID partyId, @NonNull RecordInteractionRequest request);

    @NonNull
    PagedResponse<CustomerInteractionResponse> listForParty(
            @NonNull UUID partyId, @Nullable InteractionType type, int page, int size);

    /** The most recent touches, for the CRM snapshot's summary block. */
    @NonNull
    List<CustomerInteractionResponse> recentForParty(@NonNull UUID partyId);
}
