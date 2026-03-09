package com.positivity.workorder.service;

import com.positivity.workorder.dto.CreateSubstituteLinkRequest;
import com.positivity.workorder.dto.SubstituteLinkResponse;
import com.positivity.workorder.dto.UpdateSubstituteLinkRequest;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.UUID;

public interface SubstituteLinkService {
    SubstituteLinkResponse createLink(@NonNull CreateSubstituteLinkRequest request);

    SubstituteLinkResponse updateLink(@NonNull UUID linkId, @NonNull UpdateSubstituteLinkRequest request);

    SubstituteLinkResponse deleteLink(@NonNull UUID productId, @NonNull UUID substitutePartId);

    List<SubstituteLinkResponse> listLinks(@NonNull UUID productId);

    List<SubstituteLinkResponse> suggestSubstitutes(@NonNull UUID workorderId);
}