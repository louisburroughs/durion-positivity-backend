package com.positivity.customer.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Batch request resolving a set of party ids to their display names.
 *
 * <p>Consumed server-side by sibling services (e.g. pos-invoice) that store only the
 * party id and need the display name to enrich finder/search rows.
 */
@Schema(description = "Batch request to resolve party ids to display names")
public record PartyNameResolveRequest(
        @Schema(description = "Party identifiers to resolve", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty
        @Size(max = 200)
        List<UUID> partyIds) {

    public PartyNameResolveRequest {
        partyIds = partyIds == null ? List.of() : List.copyOf(partyIds);
    }
}
