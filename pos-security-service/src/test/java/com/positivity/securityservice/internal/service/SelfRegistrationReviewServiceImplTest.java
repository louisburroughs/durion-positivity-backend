package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.dto.CrmMatchSummaryDto;
import com.positivity.securityservice.internal.dto.ResolveSelfRegistrationReviewCaseRequest;
import com.positivity.securityservice.internal.dto.SelfRegistrationReviewCaseCreateRequest;
import com.positivity.securityservice.internal.dto.SelfRegistrationReviewCaseResponse;
import com.positivity.securityservice.internal.entity.SelfRegistrationReviewCase;
import com.positivity.securityservice.internal.enums.SelfRegistrationCaseStatus;
import com.positivity.securityservice.internal.enums.SelfRegistrationCaseType;
import com.positivity.securityservice.internal.exception.SelfRegistrationReviewCaseNotFoundException;
import com.positivity.securityservice.internal.repository.SelfRegistrationReviewCaseRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Self-registration review queue: a blocked registration opens a case carrying the CRM match
 * evidence, cases are listed by any combination of status and type, and resolving one stamps the
 * acting administrator.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SelfRegistrationReviewServiceImpl")
class SelfRegistrationReviewServiceImplTest {

    private static final UUID CASE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01");
    private static final UUID PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02");
    private static final UUID LINKED_USER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a03");
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    @Mock
    private SelfRegistrationReviewCaseRepository selfRegistrationReviewCaseRepository;

    private SelfRegistrationReviewServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SelfRegistrationReviewServiceImpl(
                selfRegistrationReviewCaseRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        when(selfRegistrationReviewCaseRepository.save(any())).thenAnswer(invocation -> {
            SelfRegistrationReviewCase reviewCase = invocation.getArgument(0);
            if (reviewCase.getId() == null) {
                reviewCase.setId(CASE_ID);
            }
            return reviewCase;
        });
    }

    private static SelfRegistrationReviewCaseCreateRequest.SelfRegistrationReviewCaseCreateRequestBuilder request() {
        return SelfRegistrationReviewCaseCreateRequest.builder()
                .caseType(SelfRegistrationCaseType.IDENTITY_REVIEW)
                .reasonCode("DUPLICATE_IDENTITY")
                .reasonMessage("Multiple persons matched the submitted email")
                .email("jane.smith@example.invalid")
                .requestedUsername("jane.smith")
                .personId(PERSON_ID)
                .linkedUserId(LINKED_USER_ID)
                .notes("opened by the registration flow");
    }

    private static SelfRegistrationReviewCase reviewCase(
            SelfRegistrationCaseType caseType, SelfRegistrationCaseStatus status, Instant createdAt) {
        SelfRegistrationReviewCase reviewCase = new SelfRegistrationReviewCase();
        reviewCase.setId(CASE_ID);
        reviewCase.setCaseType(caseType);
        reviewCase.setStatus(status);
        reviewCase.setReasonCode("DUPLICATE_IDENTITY");
        reviewCase.setEmail("jane.smith@example.invalid");
        reviewCase.setPersonId(PERSON_ID);
        reviewCase.setCreatedAt(createdAt);
        return reviewCase;
    }

    @Nested
    @DisplayName("openCase")
    class OpenCase {

        @Test
        void opensTheCaseWithTheSubmittedIdentityDetails() {
            UUID caseId = service.openCase(request().build());

            ArgumentCaptor<SelfRegistrationReviewCase> saved =
                    ArgumentCaptor.forClass(SelfRegistrationReviewCase.class);
            verify(selfRegistrationReviewCaseRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(SelfRegistrationCaseStatus.OPEN);
            assertThat(saved.getValue().getCaseType()).isEqualTo(SelfRegistrationCaseType.IDENTITY_REVIEW);
            assertThat(saved.getValue().getEmail()).isEqualTo("jane.smith@example.invalid");
            assertThat(saved.getValue().getRequestedUsername()).isEqualTo("jane.smith");
            assertThat(saved.getValue().getPersonId()).isEqualTo(PERSON_ID);
            assertThat(saved.getValue().getLinkedUserId()).isEqualTo(LINKED_USER_ID);
            assertThat(caseId).isEqualTo(CASE_ID);
        }

        @Test
        void copiesTheCrmMatchEvidenceOntoTheCase() {
            service.openCase(request()
                    .crmMatchSummary(CrmMatchSummaryDto.builder()
                            .candidateCount(3)
                            .sharedIdentityCandidateCount(2)
                            .exactEmailMatch(true)
                            .exactPhoneMatch(false)
                            .exactNameMatch(true)
                            .build())
                    .build());

            ArgumentCaptor<SelfRegistrationReviewCase> saved =
                    ArgumentCaptor.forClass(SelfRegistrationReviewCase.class);
            verify(selfRegistrationReviewCaseRepository).save(saved.capture());
            assertThat(saved.getValue().getCrmCandidateCount()).isEqualTo(3);
            assertThat(saved.getValue().getCrmSharedIdentityCandidateCount()).isEqualTo(2);
            assertThat(saved.getValue().getCrmExactEmailMatch()).isTrue();
            assertThat(saved.getValue().getCrmExactPhoneMatch()).isFalse();
            assertThat(saved.getValue().getCrmExactNameMatch()).isTrue();
        }

        @Test
        void leavesTheCrmColumnsUnsetWhenNoMatchSummaryIsSupplied() {
            service.openCase(request().build());

            ArgumentCaptor<SelfRegistrationReviewCase> saved =
                    ArgumentCaptor.forClass(SelfRegistrationReviewCase.class);
            verify(selfRegistrationReviewCaseRepository).save(saved.capture());
            assertThat(saved.getValue().getCrmCandidateCount()).isNull();
            assertThat(saved.getValue().getCrmExactEmailMatch()).isNull();
        }
    }

    @Nested
    @DisplayName("listCases")
    class ListCases {

        @Test
        void listsEveryCaseNewestFirstWhenNoFilterIsGiven() {
            SelfRegistrationReviewCase older = reviewCase(
                    SelfRegistrationCaseType.IDENTITY_REVIEW,
                    SelfRegistrationCaseStatus.OPEN,
                    Instant.parse("2026-01-01T00:00:00Z"));
            SelfRegistrationReviewCase newer = reviewCase(
                    SelfRegistrationCaseType.ACCOUNT_RECOVERY,
                    SelfRegistrationCaseStatus.RESOLVED,
                    Instant.parse("2026-02-01T00:00:00Z"));
            when(selfRegistrationReviewCaseRepository.findAll()).thenReturn(List.of(older, newer));

            List<SelfRegistrationReviewCaseResponse> cases = service.listCases(null, null);

            assertThat(cases).hasSize(2);
            assertThat(cases.get(0).caseType()).isEqualTo(SelfRegistrationCaseType.ACCOUNT_RECOVERY);
        }

        @Test
        void delegatesTheCombinedFilterToTheRepository() {
            when(selfRegistrationReviewCaseRepository.findByCaseTypeAndStatusOrderByCreatedAtDesc(
                            SelfRegistrationCaseType.IDENTITY_REVIEW, SelfRegistrationCaseStatus.OPEN))
                    .thenReturn(List.of(reviewCase(
                            SelfRegistrationCaseType.IDENTITY_REVIEW, SelfRegistrationCaseStatus.OPEN, NOW)));

            assertThat(service.listCases(SelfRegistrationCaseStatus.OPEN, SelfRegistrationCaseType.IDENTITY_REVIEW))
                    .hasSize(1);
        }

        @Test
        void delegatesTheStatusOnlyFilterToTheRepository() {
            when(selfRegistrationReviewCaseRepository.findByStatusOrderByCreatedAtDesc(SelfRegistrationCaseStatus.OPEN))
                    .thenReturn(List.of(reviewCase(
                            SelfRegistrationCaseType.IDENTITY_REVIEW, SelfRegistrationCaseStatus.OPEN, NOW)));

            assertThat(service.listCases(SelfRegistrationCaseStatus.OPEN, null)).hasSize(1);
        }

        @Test
        void filtersByTypeInMemoryWhenNoStatusIsGiven() {
            when(selfRegistrationReviewCaseRepository.findAll())
                    .thenReturn(List.of(
                            reviewCase(
                                    SelfRegistrationCaseType.IDENTITY_REVIEW,
                                    SelfRegistrationCaseStatus.OPEN,
                                    Instant.parse("2026-01-01T00:00:00Z")),
                            reviewCase(
                                    SelfRegistrationCaseType.ACCOUNT_RECOVERY,
                                    SelfRegistrationCaseStatus.OPEN,
                                    Instant.parse("2026-02-01T00:00:00Z"))));

            List<SelfRegistrationReviewCaseResponse> cases =
                    service.listCases(null, SelfRegistrationCaseType.ACCOUNT_RECOVERY);

            assertThat(cases).hasSize(1);
            assertThat(cases.get(0).caseType()).isEqualTo(SelfRegistrationCaseType.ACCOUNT_RECOVERY);
        }
    }

    @Nested
    @DisplayName("getCase and resolveCase")
    class GetAndResolve {

        @Test
        void mapsTheStoredCaseOntoItsResponse() {
            SelfRegistrationReviewCase stored =
                    reviewCase(SelfRegistrationCaseType.IDENTITY_REVIEW, SelfRegistrationCaseStatus.OPEN, NOW);
            stored.setCrmCandidateCount(2);
            when(selfRegistrationReviewCaseRepository.findById(CASE_ID)).thenReturn(Optional.of(stored));

            SelfRegistrationReviewCaseResponse response = service.getCase(CASE_ID);

            assertThat(response.caseId()).isEqualTo(CASE_ID);
            assertThat(response.email()).isEqualTo("jane.smith@example.invalid");
            assertThat(response.personId()).isEqualTo(PERSON_ID);
            assertThat(response.crmCandidateCount()).isEqualTo(2);
            assertThat(response.status()).isEqualTo(SelfRegistrationCaseStatus.OPEN);
        }

        @Test
        void stampsTheResolvingAdministratorAndTheirNotes() {
            SelfRegistrationReviewCase stored =
                    reviewCase(SelfRegistrationCaseType.IDENTITY_REVIEW, SelfRegistrationCaseStatus.OPEN, NOW);
            when(selfRegistrationReviewCaseRepository.findById(CASE_ID)).thenReturn(Optional.of(stored));

            SelfRegistrationReviewCaseResponse response = service.resolveCase(
                    CASE_ID,
                    new ResolveSelfRegistrationReviewCaseRequest("Recovered the existing account"),
                    "admin.user");

            assertThat(stored.getStatus()).isEqualTo(SelfRegistrationCaseStatus.RESOLVED);
            assertThat(stored.getResolvedAt()).isEqualTo(NOW);
            assertThat(stored.getResolvedBy()).isEqualTo("admin.user");
            assertThat(response.resolutionNotes()).isEqualTo("Recovered the existing account");
        }

        @Test
        void failsForACaseThatDoesNotExist() {
            when(selfRegistrationReviewCaseRepository.findById(CASE_ID)).thenReturn(Optional.empty());
            ResolveSelfRegistrationReviewCaseRequest request = new ResolveSelfRegistrationReviewCaseRequest("notes");

            assertThatThrownBy(() -> service.getCase(CASE_ID))
                    .isInstanceOf(SelfRegistrationReviewCaseNotFoundException.class);
            assertThatThrownBy(() -> service.resolveCase(CASE_ID, request, "admin.user"))
                    .isInstanceOf(SelfRegistrationReviewCaseNotFoundException.class);
        }
    }
}
