package com.positivity.customer.internal.service;

import com.positivity.customer.internal.dto.AssignPartyTagRequest;
import com.positivity.customer.internal.dto.PartyTagAssignmentResponse;
import com.positivity.customer.internal.dto.PartyTagResponse;
import com.positivity.customer.internal.dto.UpsertPartyTagRequest;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Public API for CRM party tags (Story #1136).
 *
 * <p>Tags are the flat classification layer segments and campaigns filter on. Assignment
 * is idempotent so campaign- and import-driven tagging can replay safely.
 */
public interface PartyTagService {

    @NonNull
    PartyTagResponse createTag(@NonNull UpsertPartyTagRequest request);

    @NonNull
    PartyTagResponse updateTag(@NonNull UUID tagId, @NonNull UpsertPartyTagRequest request);

    /** Removes the tag and every assignment of it. */
    void deleteTag(@NonNull UUID tagId);

    @NonNull
    List<PartyTagResponse> listTags(boolean includeInactive);

    @NonNull
    PartyTagResponse getTag(@NonNull UUID tagId);

    /** Idempotent: re-assigning an already-attached tag returns the existing assignment. */
    @NonNull
    PartyTagAssignmentResponse assignTag(@NonNull UUID partyId, @NonNull AssignPartyTagRequest request);

    /** Idempotent: removing an absent tag is a no-op. */
    void removeTag(@NonNull UUID partyId, @NonNull UUID tagId);

    @NonNull
    List<PartyTagAssignmentResponse> listPartyTags(@NonNull UUID partyId);

    /**
     * Party ids carrying the given tags, used by segment resolution. {@code matchAll}
     * selects AND semantics (party carries every tag) over the default OR.
     */
    @NonNull
    List<UUID> findPartyIdsByTags(@NonNull List<UUID> tagIds, boolean matchAll);
}
