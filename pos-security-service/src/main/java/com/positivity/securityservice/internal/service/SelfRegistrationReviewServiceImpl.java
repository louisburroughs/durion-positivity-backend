package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.ResolveSelfRegistrationReviewCaseRequest;
import com.positivity.securityservice.internal.dto.SelfRegistrationReviewCaseCreateRequest;
import com.positivity.securityservice.internal.dto.SelfRegistrationReviewCaseResponse;
import com.positivity.securityservice.internal.entity.SelfRegistrationReviewCase;
import com.positivity.securityservice.internal.enums.SelfRegistrationCaseStatus;
import com.positivity.securityservice.internal.enums.SelfRegistrationCaseType;
import com.positivity.securityservice.internal.exception.SelfRegistrationReviewCaseNotFoundException;
import com.positivity.securityservice.internal.repository.SelfRegistrationReviewCaseRepository;
import com.positivity.securityservice.service.SelfRegistrationReviewService;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SelfRegistrationReviewServiceImpl implements SelfRegistrationReviewService {

    private final SelfRegistrationReviewCaseRepository selfRegistrationReviewCaseRepository;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public @NonNull UUID openCase(@NonNull SelfRegistrationReviewCaseCreateRequest request) {
        SelfRegistrationReviewCase reviewCase = new SelfRegistrationReviewCase();
        reviewCase.setCaseType(request.caseType());
        reviewCase.setStatus(SelfRegistrationCaseStatus.OPEN);
        reviewCase.setReasonCode(request.reasonCode());
        reviewCase.setReasonMessage(request.reasonMessage());
        reviewCase.setEmail(request.email());
        reviewCase.setRequestedUsername(request.requestedUsername());
        reviewCase.setPersonId(request.personId());
        reviewCase.setLinkedUserId(request.linkedUserId());
        reviewCase.setNotes(request.notes());
        if (request.crmMatchSummary() != null) {
            reviewCase.setCrmCandidateCount(request.crmMatchSummary().getCandidateCount());
            reviewCase.setCrmSharedIdentityCandidateCount(
                    request.crmMatchSummary().getSharedIdentityCandidateCount());
            reviewCase.setCrmExactEmailMatch(request.crmMatchSummary().isExactEmailMatch());
            reviewCase.setCrmExactPhoneMatch(request.crmMatchSummary().isExactPhoneMatch());
            reviewCase.setCrmExactNameMatch(request.crmMatchSummary().isExactNameMatch());
        }
        return selfRegistrationReviewCaseRepository.save(reviewCase).getId();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<SelfRegistrationReviewCaseResponse> listCases(
            @Nullable SelfRegistrationCaseStatus status, @Nullable SelfRegistrationCaseType caseType) {
        List<SelfRegistrationReviewCase> cases;
        if (status == null && caseType == null) {
            cases = selfRegistrationReviewCaseRepository.findAll().stream()
                    .sorted((left, right) -> right.getCreatedAt().compareTo(left.getCreatedAt()))
                    .toList();
        } else if (status != null && caseType != null) {
            cases = selfRegistrationReviewCaseRepository.findByCaseTypeAndStatusOrderByCreatedAtDesc(caseType, status);
        } else if (status != null) {
            cases = selfRegistrationReviewCaseRepository.findByStatusOrderByCreatedAtDesc(status);
        } else {
            cases = selfRegistrationReviewCaseRepository.findAll().stream()
                    .filter(reviewCase -> reviewCase.getCaseType() == caseType)
                    .sorted((left, right) -> right.getCreatedAt().compareTo(left.getCreatedAt()))
                    .toList();
        }
        return cases.stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull SelfRegistrationReviewCaseResponse getCase(@NonNull UUID caseId) {
        return toResponse(findCase(caseId));
    }

    @Override
    @Transactional
    public @NonNull SelfRegistrationReviewCaseResponse resolveCase(
            @NonNull UUID caseId, @NonNull ResolveSelfRegistrationReviewCaseRequest request, @NonNull String actorId) {
        SelfRegistrationReviewCase reviewCase = findCase(caseId);
        reviewCase.setStatus(SelfRegistrationCaseStatus.RESOLVED);
        reviewCase.setResolvedAt(clock.instant());
        reviewCase.setResolvedBy(actorId);
        reviewCase.setResolutionNotes(request.resolutionNotes());
        return toResponse(selfRegistrationReviewCaseRepository.save(reviewCase));
    }

    private SelfRegistrationReviewCase findCase(UUID caseId) {
        return selfRegistrationReviewCaseRepository
                .findById(caseId)
                .orElseThrow(() -> new SelfRegistrationReviewCaseNotFoundException(caseId));
    }

    private SelfRegistrationReviewCaseResponse toResponse(SelfRegistrationReviewCase reviewCase) {
        return SelfRegistrationReviewCaseResponse.builder()
                .caseId(reviewCase.getId())
                .caseType(reviewCase.getCaseType())
                .status(reviewCase.getStatus())
                .reasonCode(reviewCase.getReasonCode())
                .reasonMessage(reviewCase.getReasonMessage())
                .email(reviewCase.getEmail())
                .requestedUsername(reviewCase.getRequestedUsername())
                .personId(reviewCase.getPersonId())
                .linkedUserId(reviewCase.getLinkedUserId())
                .crmCandidateCount(reviewCase.getCrmCandidateCount())
                .crmSharedIdentityCandidateCount(reviewCase.getCrmSharedIdentityCandidateCount())
                .crmExactEmailMatch(reviewCase.getCrmExactEmailMatch())
                .crmExactPhoneMatch(reviewCase.getCrmExactPhoneMatch())
                .crmExactNameMatch(reviewCase.getCrmExactNameMatch())
                .notes(reviewCase.getNotes())
                .createdAt(reviewCase.getCreatedAt())
                .updatedAt(reviewCase.getUpdatedAt())
                .resolvedAt(reviewCase.getResolvedAt())
                .resolvedBy(reviewCase.getResolvedBy())
                .resolutionNotes(reviewCase.getResolutionNotes())
                .build();
    }
}
