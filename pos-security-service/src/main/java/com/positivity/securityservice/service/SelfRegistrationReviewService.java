package com.positivity.securityservice.service;

import com.positivity.securityservice.internal.dto.ResolveSelfRegistrationReviewCaseRequest;
import com.positivity.securityservice.internal.dto.SelfRegistrationReviewCaseCreateRequest;
import com.positivity.securityservice.internal.dto.SelfRegistrationReviewCaseResponse;
import com.positivity.securityservice.internal.enums.SelfRegistrationCaseStatus;
import com.positivity.securityservice.internal.enums.SelfRegistrationCaseType;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface SelfRegistrationReviewService {

    @NonNull
    UUID openCase(@NonNull SelfRegistrationReviewCaseCreateRequest request);

    @NonNull
    List<SelfRegistrationReviewCaseResponse> listCases(
            @Nullable SelfRegistrationCaseStatus status, @Nullable SelfRegistrationCaseType caseType);

    @NonNull
    SelfRegistrationReviewCaseResponse getCase(@NonNull UUID caseId);

    @NonNull
    SelfRegistrationReviewCaseResponse resolveCase(
            @NonNull UUID caseId, @NonNull ResolveSelfRegistrationReviewCaseRequest request, @NonNull String actorId);
}
