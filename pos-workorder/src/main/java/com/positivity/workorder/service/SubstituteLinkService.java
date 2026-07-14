package com.positivity.workorder.service;

import com.positivity.workorder.internal.dto.pick.CreateSubstituteLinkRequest;
import com.positivity.workorder.internal.dto.pick.SubstituteLinkResponse;
import com.positivity.workorder.internal.dto.pick.UpdateSubstituteLinkRequest;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface SubstituteLinkService {
    SubstituteLinkResponse createLink(@NonNull CreateSubstituteLinkRequest request);

    SubstituteLinkResponse updateLink(@NonNull UUID linkId, @NonNull UpdateSubstituteLinkRequest request);

    SubstituteLinkResponse deleteLink(@NonNull UUID productId, @NonNull UUID substitutePartId);

    List<SubstituteLinkResponse> listLinks(@NonNull UUID productId);

    /**
     * Suggest substitute parts for a workorder. When {@code partId} is provided, suggestions are
     * scoped to active substitute links for that part; when null, suggestions cover the whole
     * workorder.
     */
    List<SubstituteLinkResponse> suggestSubstitutes(@NonNull UUID workorderId, @Nullable UUID partId);
}
