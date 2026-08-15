package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.workorder.internal.dto.CreateChangeRequestDTO;
import com.positivity.workorder.internal.entity.ApprovalRecord;
import com.positivity.workorder.internal.entity.ChangeRequest;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.entity.WorkorderServiceLine;
import com.positivity.workorder.internal.enums.WorkorderItemStatus;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.ApprovalRecordRepository;
import com.positivity.workorder.internal.repository.ChangeRequestRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import com.positivity.workorder.service.IdempotencyService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Change requests: they may only be raised against work in progress, emergency items must carry
 * photo or written evidence, and every advisor decision writes an approval record and pushes the
 * attached items to their next status.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ChangeRequestServiceImpl")
class ChangeRequestServiceImplTest {

    private static final UUID WORKORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fcc11");
    private static final UUID CHANGE_REQUEST_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fcc12");
    private static final UUID SERVICE_ENTITY_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fcc13");
    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fcc14");
    private static final UUID APPROVER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fcc15");
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Mock
    private ChangeRequestRepository changeRequestRepository;

    @Mock
    private WorkorderRepository workOrderRepository;

    @Mock
    private WorkorderServiceRepository workOrderServiceRepository;

    @Mock
    private WorkorderPartRepository workOrderPartRepository;

    @Mock
    private ApprovalRecordRepository approvalRecordRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private RestClient documentServiceRestClient;

    private ChangeRequestServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ChangeRequestServiceImpl(
                Clock.fixed(NOW, ZoneOffset.UTC),
                changeRequestRepository,
                workOrderRepository,
                workOrderServiceRepository,
                workOrderPartRepository,
                approvalRecordRepository,
                idempotencyService,
                documentServiceRestClient,
                new ObjectMapper());

        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken("jane.smith", "n/a", List.of());
        token.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "jane.smith"));
        SecurityContextHolder.getContext().setAuthentication(token);

        when(changeRequestRepository.save(any())).thenAnswer(invocation -> {
            ChangeRequest changeRequest = invocation.getArgument(0);
            if (changeRequest.getId() == null) {
                changeRequest.setId(CHANGE_REQUEST_ID);
            }
            return changeRequest;
        });
        when(workOrderServiceRepository.findByChangeRequest_Id(any())).thenReturn(List.of());
        when(workOrderPartRepository.findByChangeRequest_Id(any())).thenReturn(List.of());
        // The document service is unreachable in a unit test; the PDF step is best-effort and
        // must not fail the change request.
        when(documentServiceRestClient.post()).thenThrow(new IllegalStateException("document service unavailable"));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static Workorder workorder(WorkorderStatus status) {
        Workorder workorder = new Workorder();
        workorder.setId(WORKORDER_ID);
        workorder.setStatus(status);
        return workorder;
    }

    private void stubWorkorder(WorkorderStatus status) {
        when(workOrderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder(status)));
    }

    private static CreateChangeRequestDTO.CreateChangeRequestDTOBuilder dto() {
        return CreateChangeRequestDTO.builder()
                .workorderId(WORKORDER_ID)
                .description("Rear rotors are scored")
                .services(List.of(CreateChangeRequestDTO.WorkorderItemDTO.builder()
                        .serviceEntityId(SERVICE_ENTITY_ID)
                        .build()));
    }

    private static ChangeRequest changeRequest(ChangeRequest.ChangeRequestStatus status) {
        return ChangeRequest.builder()
                .id(CHANGE_REQUEST_ID)
                .workorder(workorder(WorkorderStatus.WORK_IN_PROGRESS))
                .requestedByUserId("jane.smith")
                .description("Rear rotors are scored")
                .status(status)
                .build();
    }

    @Nested
    @DisplayName("createChangeRequest")
    class CreateChangeRequest {

        @Test
        void opensTheRequestForAdvisorReviewAndAttachesItsItems() {
            stubWorkorder(WorkorderStatus.WORK_IN_PROGRESS);

            ChangeRequest created =
                    service.createChangeRequest(dto().parts(List.of(CreateChangeRequestDTO.WorkorderItemDTO.builder()
                                    .productEntityId(PRODUCT_ID)
                                    .quantity(2)
                                    .build()))
                            .build());

            assertThat(created.getStatus()).isEqualTo(ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW);
            assertThat(created.getRequestedByUserId()).isEqualTo("jane.smith");
            assertThat(created.getRequestedAt()).isEqualTo(NOW_LOCAL);

            ArgumentCaptor<WorkorderServiceLine> savedService = ArgumentCaptor.forClass(WorkorderServiceLine.class);
            verify(workOrderServiceRepository).save(savedService.capture());
            assertThat(savedService.getValue().getStatus()).isEqualTo(WorkorderItemStatus.PENDING_APPROVAL);
            assertThat(savedService.getValue().getServiceEntityId()).isEqualTo(SERVICE_ENTITY_ID);

            ArgumentCaptor<WorkorderPart> savedPart = ArgumentCaptor.forClass(WorkorderPart.class);
            verify(workOrderPartRepository).save(savedPart.capture());
            assertThat(savedPart.getValue().getStatus()).isEqualTo(WorkorderItemStatus.PENDING_APPROVAL);
            assertThat(savedPart.getValue().getQuantity()).isEqualByComparingTo("2");
        }

        @Test
        void survivesAnUnreachableDocumentServiceWithoutAPdfReference() {
            stubWorkorder(WorkorderStatus.WORK_IN_PROGRESS);

            assertThat(service.createChangeRequest(dto().build()).getSupplementalEstimatePdfId())
                    .isNull();
        }

        @Test
        void requiresADescription() {
            CreateChangeRequestDTO request = dto().description("  ").build();

            assertThatThrownBy(() -> service.createChangeRequest(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Description is required");
        }

        @Test
        void requiresAtLeastOneServiceOrPart() {
            CreateChangeRequestDTO request =
                    dto().services(List.of()).parts(List.of()).build();

            assertThatThrownBy(() -> service.createChangeRequest(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("At least one service or part");
        }

        @Test
        void failsForAnUnknownWorkorder() {
            when(workOrderRepository.findById(WORKORDER_ID)).thenReturn(Optional.empty());
            CreateChangeRequestDTO request = dto().build();

            assertThatThrownBy(() -> service.createChangeRequest(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Work order not found");
        }

        @Test
        void refusesAWorkorderThatIsNotInProgress() {
            stubWorkorder(WorkorderStatus.COMPLETED);
            CreateChangeRequestDTO request = dto().build();

            assertThatThrownBy(() -> service.createChangeRequest(request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("WORK_IN_PROGRESS");
        }
    }

    @Nested
    @DisplayName("emergency documentation")
    class EmergencyDocumentation {

        @Test
        void refusesAnEmergencyExceptionWithNoEmergencyItems() {
            stubWorkorder(WorkorderStatus.WORK_IN_PROGRESS);
            CreateChangeRequestDTO request = dto().isEmergencyException(true)
                    .exceptionReason("brake failure")
                    .build();

            assertThatThrownBy(() -> service.createChangeRequest(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no items are marked as emergency/safety");
        }

        @Test
        void requiresPhotoEvidenceOrAnExplicitPhotoNotPossibleFlag() {
            stubWorkorder(WorkorderStatus.WORK_IN_PROGRESS);
            CreateChangeRequestDTO request = dto().isEmergencyException(true)
                    .services(List.of(CreateChangeRequestDTO.WorkorderItemDTO.builder()
                            .serviceEntityId(SERVICE_ENTITY_ID)
                            .isEmergencySafety(true)
                            .build()))
                    .build();

            assertThatThrownBy(() -> service.createChangeRequest(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("photo evidence");
        }

        @Test
        void requiresNotesWhenNoPhotoIsPossible() {
            stubWorkorder(WorkorderStatus.WORK_IN_PROGRESS);
            CreateChangeRequestDTO request = dto().isEmergencyException(true)
                    .services(List.of(CreateChangeRequestDTO.WorkorderItemDTO.builder()
                            .serviceEntityId(SERVICE_ENTITY_ID)
                            .isEmergencySafety(true)
                            .photoNotPossible(true)
                            .build()))
                    .build();

            assertThatThrownBy(() -> service.createChangeRequest(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("notes explaining the situation");
        }

        @Test
        void acceptsAnEmergencyItemWithAPhoto() {
            stubWorkorder(WorkorderStatus.WORK_IN_PROGRESS);

            ChangeRequest created = service.createChangeRequest(dto().isEmergencyException(true)
                    .exceptionReason("brake failure")
                    .services(List.of(CreateChangeRequestDTO.WorkorderItemDTO.builder()
                            .serviceEntityId(SERVICE_ENTITY_ID)
                            .isEmergencySafety(true)
                            .photoEvidenceUrl("https://example.invalid/rotor.jpg")
                            .build()))
                    .build());

            assertThat(created.getIsEmergencyException()).isTrue();
            assertThat(created.getExceptionReason()).isEqualTo("brake failure");
        }

        @Test
        void acceptsAnEmergencyPartWithNotesInsteadOfAPhoto() {
            stubWorkorder(WorkorderStatus.WORK_IN_PROGRESS);

            assertThat(service.createChangeRequest(dto().isEmergencyException(true)
                            .services(List.of())
                            .parts(List.of(CreateChangeRequestDTO.WorkorderItemDTO.builder()
                                    .productEntityId(PRODUCT_ID)
                                    .isEmergencySafety(true)
                                    .photoNotPossible(true)
                                    .emergencyNotes("bay lighting too poor to photograph")
                                    .build()))
                            .build()))
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("createChangeRequestWithIdempotency")
    class CreateWithIdempotency {

        @Test
        void replaysTheOriginalRequestForAKnownKey() {
            when(idempotencyService.getExistingChangeRequestId(anyString(), anyString()))
                    .thenReturn(Optional.of(CHANGE_REQUEST_ID));
            when(changeRequestRepository.findById(CHANGE_REQUEST_ID))
                    .thenReturn(Optional.of(changeRequest(ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW)));

            ChangeRequest replayed = service.createChangeRequestWithIdempotency(dto().build(), "cr-key-1");

            assertThat(replayed.getId()).isEqualTo(CHANGE_REQUEST_ID);
            verify(workOrderRepository, never()).findById(any());
        }

        @Test
        void failsWhenTheReplayedRequestIsGone() {
            when(idempotencyService.getExistingChangeRequestId(anyString(), anyString()))
                    .thenReturn(Optional.of(CHANGE_REQUEST_ID));
            when(changeRequestRepository.findById(CHANGE_REQUEST_ID)).thenReturn(Optional.empty());
            CreateChangeRequestDTO request = dto().build();

            assertThatThrownBy(() -> service.createChangeRequestWithIdempotency(request, "cr-key-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("non-existent change request");
        }

        @Test
        void createsNormallyWhenNoKeyIsSupplied() {
            stubWorkorder(WorkorderStatus.WORK_IN_PROGRESS);

            assertThat(service.createChangeRequestWithIdempotency(dto().build(), "  "))
                    .isNotNull();
            verify(idempotencyService, never()).getExistingChangeRequestId(anyString(), anyString());
            verify(idempotencyService, never()).markKeyProcessedForChangeRequest(anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("advisor decisions")
    class AdvisorDecisions {

        @Test
        void approvalRecordsTheDecisionAndReleasesTheItems() {
            ChangeRequest pending = changeRequest(ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW);
            when(changeRequestRepository.findById(CHANGE_REQUEST_ID)).thenReturn(Optional.of(pending));
            WorkorderServiceLine line = WorkorderServiceLine.builder()
                    .status(WorkorderItemStatus.PENDING_APPROVAL)
                    .build();
            when(workOrderServiceRepository.findByChangeRequest_Id(CHANGE_REQUEST_ID))
                    .thenReturn(List.of(line));

            ChangeRequest approved = service.approveChangeRequest(CHANGE_REQUEST_ID, APPROVER_ID, "go ahead");

            assertThat(approved.getStatus()).isEqualTo(ChangeRequest.ChangeRequestStatus.APPROVED);
            assertThat(approved.getApprovedBy()).isEqualTo("jane.smith");
            assertThat(approved.getApprovedAt()).isEqualTo(NOW_LOCAL);
            assertThat(line.getStatus()).isEqualTo(WorkorderItemStatus.READY_TO_EXECUTE);

            ArgumentCaptor<ApprovalRecord> record = ArgumentCaptor.forClass(ApprovalRecord.class);
            verify(approvalRecordRepository).save(record.capture());
            assertThat(record.getValue().getResolutionStatus()).isEqualTo(ApprovalRecord.ResolutionStatus.APPROVED);
            assertThat(record.getValue().getApprovalNote()).isEqualTo("go ahead");
        }

        @Test
        void declineCancelsTheAttachedItems() {
            ChangeRequest pending = changeRequest(ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW);
            when(changeRequestRepository.findById(CHANGE_REQUEST_ID)).thenReturn(Optional.of(pending));
            WorkorderPart part = WorkorderPart.builder()
                    .status(WorkorderItemStatus.PENDING_APPROVAL)
                    .build();
            when(workOrderPartRepository.findByChangeRequest_Id(CHANGE_REQUEST_ID))
                    .thenReturn(List.of(part));

            ChangeRequest declined = service.declineChangeRequest(CHANGE_REQUEST_ID, "customer declined");

            assertThat(declined.getStatus()).isEqualTo(ChangeRequest.ChangeRequestStatus.DECLINED);
            assertThat(declined.getDeclinedAt()).isEqualTo(NOW_LOCAL);
            assertThat(part.getStatus()).isEqualTo(WorkorderItemStatus.CANCELLED);

            ArgumentCaptor<ApprovalRecord> record = ArgumentCaptor.forClass(ApprovalRecord.class);
            verify(approvalRecordRepository).save(record.capture());
            assertThat(record.getValue().getResolutionStatus()).isEqualTo(ApprovalRecord.ResolutionStatus.REJECTED);
        }

        @Test
        void bothDecisionsRequireANote() {
            when(changeRequestRepository.findById(CHANGE_REQUEST_ID))
                    .thenReturn(Optional.of(changeRequest(ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW)));

            assertThatThrownBy(() -> service.approveChangeRequest(CHANGE_REQUEST_ID, APPROVER_ID, "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Approval note is required");
            assertThatThrownBy(() -> service.declineChangeRequest(CHANGE_REQUEST_ID, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Approval note is required");
        }

        @Test
        void bothDecisionsRefuseARequestThatIsNoLongerPending() {
            when(changeRequestRepository.findById(CHANGE_REQUEST_ID))
                    .thenReturn(Optional.of(changeRequest(ChangeRequest.ChangeRequestStatus.APPROVED)));

            assertThatThrownBy(() -> service.approveChangeRequest(CHANGE_REQUEST_ID, APPROVER_ID, "note"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> service.declineChangeRequest(CHANGE_REQUEST_ID, "note"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void bothDecisionsFailForAnUnknownRequest() {
            when(changeRequestRepository.findById(CHANGE_REQUEST_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.approveChangeRequest(CHANGE_REQUEST_ID, APPROVER_ID, "note"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.declineChangeRequest(CHANGE_REQUEST_ID, "note"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.applyEmergencyOverride(CHANGE_REQUEST_ID, "reason"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void emergencyOverrideApprovesWithAnExceptionAndItsReason() {
            ChangeRequest pending = changeRequest(ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW);
            when(changeRequestRepository.findById(CHANGE_REQUEST_ID)).thenReturn(Optional.of(pending));

            ChangeRequest overridden = service.applyEmergencyOverride(CHANGE_REQUEST_ID, "brake failure");

            assertThat(overridden.getStatus()).isEqualTo(ChangeRequest.ChangeRequestStatus.APPROVED_WITH_EXCEPTION);
            assertThat(overridden.getIsEmergencyException()).isTrue();
            assertThat(overridden.getExceptionReason()).isEqualTo("brake failure");

            ArgumentCaptor<ApprovalRecord> record = ArgumentCaptor.forClass(ApprovalRecord.class);
            verify(approvalRecordRepository).save(record.capture());
            assertThat(record.getValue().getResolutionStatus())
                    .isEqualTo(ApprovalRecord.ResolutionStatus.APPROVED_WITH_EXCEPTION);
        }

        @Test
        void emergencyOverrideRequiresAReasonAndAPendingRequest() {
            when(changeRequestRepository.findById(CHANGE_REQUEST_ID))
                    .thenReturn(Optional.of(changeRequest(ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW)));
            assertThatThrownBy(() -> service.applyEmergencyOverride(CHANGE_REQUEST_ID, "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Exception reason is required");

            when(changeRequestRepository.findById(CHANGE_REQUEST_ID))
                    .thenReturn(Optional.of(changeRequest(ChangeRequest.ChangeRequestStatus.DECLINED)));
            assertThatThrownBy(() -> service.applyEmergencyOverride(CHANGE_REQUEST_ID, "brake failure"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void everyDecisionRequiresAnAuthenticatedActor() {
            SecurityContextHolder.clearContext();

            assertThatThrownBy(() -> service.approveChangeRequest(CHANGE_REQUEST_ID, APPROVER_ID, "note"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> service.declineChangeRequest(CHANGE_REQUEST_ID, "note"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> service.applyEmergencyOverride(CHANGE_REQUEST_ID, "reason"))
                    .isInstanceOf(IllegalStateException.class);
            verify(changeRequestRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("denial acknowledgment and close gating")
    class DenialAndClosing {

        @Test
        void acknowledgesDenialOnEveryEmergencyItem() {
            ChangeRequest declined = changeRequest(ChangeRequest.ChangeRequestStatus.DECLINED);
            declined.setIsEmergencyException(true);
            when(changeRequestRepository.findById(CHANGE_REQUEST_ID)).thenReturn(Optional.of(declined));
            WorkorderServiceLine emergencyService =
                    WorkorderServiceLine.builder().isEmergencySafety(true).build();
            WorkorderServiceLine routineService =
                    WorkorderServiceLine.builder().isEmergencySafety(false).build();
            WorkorderPart emergencyPart =
                    WorkorderPart.builder().isEmergencySafety(true).build();
            when(workOrderServiceRepository.findByChangeRequest_Id(CHANGE_REQUEST_ID))
                    .thenReturn(List.of(emergencyService, routineService));
            when(workOrderPartRepository.findByChangeRequest_Id(CHANGE_REQUEST_ID))
                    .thenReturn(List.of(emergencyPart));

            service.recordCustomerDenialAcknowledgment(CHANGE_REQUEST_ID);

            assertThat(emergencyService.getCustomerDenialAcknowledged()).isTrue();
            assertThat(routineService.getCustomerDenialAcknowledged()).isNotEqualTo(true);
            assertThat(emergencyPart.getCustomerDenialAcknowledged()).isTrue();
        }

        @Test
        void refusesAcknowledgmentForANonEmergencyOrStillOpenRequest() {
            when(changeRequestRepository.findById(CHANGE_REQUEST_ID))
                    .thenReturn(Optional.of(changeRequest(ChangeRequest.ChangeRequestStatus.DECLINED)));
            assertThatThrownBy(() -> service.recordCustomerDenialAcknowledgment(CHANGE_REQUEST_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not marked as emergency/safety");

            ChangeRequest stillOpen = changeRequest(ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW);
            stillOpen.setIsEmergencyException(true);
            when(changeRequestRepository.findById(CHANGE_REQUEST_ID)).thenReturn(Optional.of(stillOpen));
            assertThatThrownBy(() -> service.recordCustomerDenialAcknowledgment(CHANGE_REQUEST_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must be declined");
        }

        @Test
        void blocksClosingWhileAnEmergencyDenialIsUnacknowledged() {
            ChangeRequest declined = changeRequest(ChangeRequest.ChangeRequestStatus.DECLINED);
            declined.setIsEmergencyException(true);
            when(changeRequestRepository.findByWorkorder_IdAndStatus(
                            WORKORDER_ID, ChangeRequest.ChangeRequestStatus.DECLINED))
                    .thenReturn(List.of(declined));
            when(workOrderServiceRepository.findByChangeRequest_Id(CHANGE_REQUEST_ID))
                    .thenReturn(List.of(WorkorderServiceLine.builder()
                            .isEmergencySafety(true)
                            .build()));

            assertThat(service.canCloseWorkorder(WORKORDER_ID)).isFalse();
        }

        @Test
        void allowsClosingOnceEveryEmergencyDenialIsAcknowledged() {
            ChangeRequest declined = changeRequest(ChangeRequest.ChangeRequestStatus.DECLINED);
            declined.setIsEmergencyException(true);
            when(changeRequestRepository.findByWorkorder_IdAndStatus(
                            WORKORDER_ID, ChangeRequest.ChangeRequestStatus.DECLINED))
                    .thenReturn(List.of(declined));
            when(workOrderServiceRepository.findByChangeRequest_Id(CHANGE_REQUEST_ID))
                    .thenReturn(List.of(WorkorderServiceLine.builder()
                            .isEmergencySafety(true)
                            .customerDenialAcknowledged(true)
                            .build()));
            when(workOrderPartRepository.findByChangeRequest_Id(CHANGE_REQUEST_ID))
                    .thenReturn(List.of(WorkorderPart.builder()
                            .isEmergencySafety(true)
                            .customerDenialAcknowledged(true)
                            .build()));

            assertThat(service.canCloseWorkorder(WORKORDER_ID)).isTrue();
        }

        @Test
        void ignoresNonEmergencyDeclinesWhenGatingTheClose() {
            when(changeRequestRepository.findByWorkorder_IdAndStatus(
                            WORKORDER_ID, ChangeRequest.ChangeRequestStatus.DECLINED))
                    .thenReturn(List.of(changeRequest(ChangeRequest.ChangeRequestStatus.DECLINED)));

            assertThat(service.canCloseWorkorder(WORKORDER_ID)).isTrue();
        }
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        void reportsOnlyApprovalGatedPendingRequests() {
            ChangeRequest gated = changeRequest(ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW);
            ChangeRequest ungated = changeRequest(ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW);
            ungated.setIsApprovalGated(false);
            when(changeRequestRepository.findByWorkorder_IdAndStatus(
                            WORKORDER_ID, ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW))
                    .thenReturn(List.of(gated, ungated));

            assertThat(service.hasPendingApprovalGatedRequests(WORKORDER_ID)).isTrue();
            assertThat(service.getPendingApprovalGatedRequests(WORKORDER_ID)).containsExactly(gated);
        }

        @Test
        void reportsNoPendingGateWhenEveryRequestIsUngated() {
            ChangeRequest ungated = changeRequest(ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW);
            ungated.setIsApprovalGated(false);
            when(changeRequestRepository.findByWorkorder_IdAndStatus(
                            WORKORDER_ID, ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW))
                    .thenReturn(List.of(ungated));

            assertThat(service.hasPendingApprovalGatedRequests(WORKORDER_ID)).isFalse();
            assertThat(service.getPendingApprovalGatedRequests(WORKORDER_ID)).isEmpty();
        }

        @Test
        void listsAWorkordersChangeRequests() {
            when(changeRequestRepository.findByWorkorder_Id(WORKORDER_ID))
                    .thenReturn(List.of(changeRequest(ChangeRequest.ChangeRequestStatus.APPROVED)));

            assertThat(service.getChangeRequestsByWorkorder(WORKORDER_ID)).hasSize(1);
        }

        @Test
        void readsOneChangeRequestById() {
            when(changeRequestRepository.findById(CHANGE_REQUEST_ID))
                    .thenReturn(Optional.of(changeRequest(ChangeRequest.ChangeRequestStatus.APPROVED)));

            assertThat(service.getChangeRequestById(CHANGE_REQUEST_ID).getId()).isEqualTo(CHANGE_REQUEST_ID);
        }

        @Test
        void failsForAnUnknownChangeRequestId() {
            when(changeRequestRepository.findById(CHANGE_REQUEST_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getChangeRequestById(CHANGE_REQUEST_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Change request not found");
        }
    }
}
