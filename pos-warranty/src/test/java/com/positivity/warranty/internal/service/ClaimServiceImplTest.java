package com.positivity.warranty.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.warranty.internal.client.VehicleInventoryClient;
import com.positivity.warranty.internal.dto.ClaimActionRequest;
import com.positivity.warranty.internal.dto.ClaimCreateRequest;
import com.positivity.warranty.internal.dto.ClaimDecisionRequest;
import com.positivity.warranty.internal.dto.ClaimLineRequest;
import com.positivity.warranty.internal.dto.ClaimResponse;
import com.positivity.warranty.internal.dto.ClaimUpdateRequest;
import com.positivity.warranty.internal.entity.ClaimNote;
import com.positivity.warranty.internal.entity.ClaimStatusHistory;
import com.positivity.warranty.internal.entity.PartReturn;
import com.positivity.warranty.internal.entity.VendorReimbursement;
import com.positivity.warranty.internal.entity.WarrantyClaim;
import com.positivity.warranty.internal.entity.WarrantyClaimLine;
import com.positivity.warranty.internal.entity.WarrantyPolicy;
import com.positivity.warranty.internal.enums.ClaimDecision;
import com.positivity.warranty.internal.enums.ClaimStatus;
import com.positivity.warranty.internal.enums.ClaimType;
import com.positivity.warranty.internal.enums.EligibilityResult;
import com.positivity.warranty.internal.enums.LineDisposition;
import com.positivity.warranty.internal.enums.LineSourceType;
import com.positivity.warranty.internal.enums.PartReturnStatus;
import com.positivity.warranty.internal.enums.ReimbursementStatus;
import com.positivity.warranty.internal.exception.IllegalClaimStateException;
import com.positivity.warranty.internal.exception.WarrantyNotFoundException;
import com.positivity.warranty.internal.repository.ClaimNoteRepository;
import com.positivity.warranty.internal.repository.ClaimSettlementRepository;
import com.positivity.warranty.internal.repository.ClaimStatusHistoryRepository;
import com.positivity.warranty.internal.repository.PartReturnRepository;
import com.positivity.warranty.internal.repository.VendorReimbursementRepository;
import com.positivity.warranty.internal.repository.WarrantyClaimRepository;
import com.positivity.warranty.internal.repository.WarrantyPolicyRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Claim lifecycle service (PRD §5, §7): intake with frozen vehicle snapshot and claim-code
 * allocation, edit gating (DRAFT/INFO_NEEDED only), submit with eligibility + photo-evidence
 * enforcement, adjudication (deny/override reasons, appeal), and terminal moves (cancel, close
 * gated on child lifecycles).
 */
@ExtendWith(MockitoExtension.class)
class ClaimServiceImplTest {

    private static final UUID CLAIM_ID = UUID.fromString("018f0000-0000-7000-8000-000000000101");
    private static final UUID CUSTOMER_ID = UUID.fromString("018f0000-0000-7000-8000-000000000102");
    private static final UUID VEHICLE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000103");
    private static final UUID POLICY_ID = UUID.fromString("018f0000-0000-7000-8000-000000000104");
    private static final UUID INVOICE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000105");
    private static final UUID LINE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000106");
    private static final String CLAIM_CODE = "WC-2026-000042";

    @Mock
    private WarrantyClaimRepository claimRepository;

    @Mock
    private WarrantyPolicyRepository policyRepository;

    @Mock
    private ClaimStatusHistoryRepository statusHistoryRepository;

    @Mock
    private ClaimNoteRepository noteRepository;

    @Mock
    private ClaimSettlementRepository settlementRepository;

    @Mock
    private VendorReimbursementRepository reimbursementRepository;

    @Mock
    private PartReturnRepository partReturnRepository;

    @Mock
    private ClaimCodeService claimCodeService;

    @Mock
    private EligibilityService eligibilityService;

    @Mock
    private VehicleInventoryClient vehicleInventoryClient;

    @Mock
    private ClaimSnapshotPublisher claimSnapshotPublisher;

    private ClaimServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ClaimServiceImpl(
                claimRepository,
                policyRepository,
                statusHistoryRepository,
                noteRepository,
                settlementRepository,
                reimbursementRepository,
                partReturnRepository,
                claimCodeService,
                eligibilityService,
                vehicleInventoryClient,
                claimSnapshotPublisher,
                Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------ fixtures

    private static WarrantyClaimLine line() {
        return WarrantyClaimLine.builder()
                .id(LINE_ID)
                .sourceType(LineSourceType.INVOICE_LINE)
                .sourceId(INVOICE_ID)
                .sku("TIRE-205-55R16")
                .quantity(BigDecimal.ONE)
                .originalUnitPrice(new BigDecimal("129.99"))
                .build();
    }

    private static WarrantyClaim claim(ClaimStatus status) {
        WarrantyClaim claim = WarrantyClaim.builder()
                .id(CLAIM_ID)
                .claimCode(CLAIM_CODE)
                .claimType(ClaimType.MANUFACTURER_DEFECT)
                .status(status)
                .customerId(CUSTOMER_ID)
                .vehicleId(VEHICLE_ID)
                .lines(new ArrayList<>())
                .build();
        claim.addLine(line());
        return claim;
    }

    private void stubLoad(WarrantyClaim claim) {
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim));
    }

    private void stubSaveEcho() {
        when(claimRepository.save(any(WarrantyClaim.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static ClaimCreateRequest createRequest(UUID originInvoiceId, List<String> photos) {
        return new ClaimCreateRequest(
                ClaimType.MANUFACTURER_DEFECT,
                CUSTOMER_ID,
                VEHICLE_ID,
                null,
                null,
                originInvoiceId,
                originInvoiceId == null ? null : LocalDate.of(2025, 6, 1),
                null,
                "sidewall separation",
                LocalDate.of(2026, 7, 1),
                photos,
                List.of(new ClaimLineRequest(
                        LineSourceType.INVOICE_LINE,
                        originInvoiceId,
                        null,
                        null,
                        "TIRE-205-55R16",
                        "All-season tire",
                        null,
                        "DOT Y9RJ FPUU 2325",
                        null,
                        new BigDecimal("129.99"),
                        12,
                        6)));
    }

    private static ClaimUpdateRequest updateRequest(UUID originInvoiceId) {
        return new ClaimUpdateRequest(
                null, null, null, originInvoiceId, null, null, "updated description", LocalDate.of(2026, 7, 2), null);
    }

    // ------------------------------------------------------------------ create

    @Nested
    class Create {

        @Test
        void freezesVehicleSnapshotAndAllocatesClaimCode() {
            when(claimCodeService.nextClaimCode()).thenReturn(CLAIM_CODE);
            when(vehicleInventoryClient.getVehicle(VEHICLE_ID))
                    .thenReturn(Optional.of(
                            new VehicleInventoryClient.VehicleSnapshot("1HGCM82633A004352", 42_000, "MILES")));
            stubSaveEcho();

            ClaimResponse response = service.create(createRequest(INVOICE_ID, List.of("https://p/1.jpg")));

            assertThat(response.claimCode()).isEqualTo(CLAIM_CODE);
            assertThat(response.status()).isEqualTo(ClaimStatus.DRAFT);
            assertThat(response.vin()).isEqualTo("1HGCM82633A004352");
            assertThat(response.odometerAtClaim()).isEqualTo(42_000L);
            assertThat(response.odometerUnit()).isEqualTo("MILES");
            assertThat(response.originUnverified()).isFalse();
            assertThat(response.lines()).hasSize(1);
            assertThat(response.lines().getFirst().quantity()).isEqualByComparingTo(BigDecimal.ONE);

            ArgumentCaptor<ClaimStatusHistory> history = ArgumentCaptor.captor();
            verify(statusHistoryRepository).save(history.capture());
            assertThat(history.getValue().getFromStatus()).isNull();
            assertThat(history.getValue().getToStatus()).isEqualTo(ClaimStatus.DRAFT);
            verify(claimSnapshotPublisher).publish(any());
        }

        @Test
        void survivesVehicleLookupOutageWithNullSnapshot() {
            when(claimCodeService.nextClaimCode()).thenReturn(CLAIM_CODE);
            when(vehicleInventoryClient.getVehicle(VEHICLE_ID)).thenReturn(Optional.empty());
            stubSaveEcho();

            ClaimResponse response = service.create(createRequest(INVOICE_ID, null));

            assertThat(response.vin()).isNull();
            assertThat(response.odometerAtClaim()).isNull();
            assertThat(response.claimCode()).isEqualTo(CLAIM_CODE);
        }

        @Test
        void walkInWithoutOriginIsFlaggedOriginUnverified() {
            when(claimCodeService.nextClaimCode()).thenReturn(CLAIM_CODE);
            when(vehicleInventoryClient.getVehicle(VEHICLE_ID)).thenReturn(Optional.empty());
            stubSaveEcho();

            ClaimResponse response = service.create(createRequest(null, null));

            assertThat(response.originUnverified()).isTrue();
            assertThat(response.originWorkorderId()).isNull();
            assertThat(response.originInvoiceId()).isNull();
        }

        @Test
        void locatedOriginIsNotFlaggedUnverified() {
            when(claimCodeService.nextClaimCode()).thenReturn(CLAIM_CODE);
            when(vehicleInventoryClient.getVehicle(VEHICLE_ID)).thenReturn(Optional.empty());
            stubSaveEcho();

            ClaimResponse response = service.create(createRequest(INVOICE_ID, null));

            assertThat(response.originUnverified()).isFalse();
            assertThat(response.originInvoiceId()).isEqualTo(INVOICE_ID);
        }
    }

    // ------------------------------------------------------------------ edits

    @Nested
    class Edits {

        @Test
        void updateAppliesWhileDraftAndRecomputesOriginUnverified() {
            stubLoad(claim(ClaimStatus.DRAFT));
            stubSaveEcho();

            ClaimResponse response = service.update(CLAIM_ID, updateRequest(INVOICE_ID));

            assertThat(response.originUnverified()).isFalse();
            assertThat(response.failureDescription()).isEqualTo("updated description");
            verify(claimSnapshotPublisher).publish(CLAIM_ID);
        }

        @Test
        void updateClearingOriginFlagsClaimUnverified() {
            stubLoad(claim(ClaimStatus.INFO_NEEDED));
            stubSaveEcho();

            ClaimResponse response = service.update(CLAIM_ID, updateRequest(null));

            assertThat(response.originUnverified()).isTrue();
        }

        @Test
        void updateIsRejectedOutsideDraftAndInfoNeeded() {
            stubLoad(claim(ClaimStatus.SUBMITTED));

            assertThatThrownBy(() -> service.update(CLAIM_ID, updateRequest(INVOICE_ID)))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> {
                        IllegalClaimStateException e = (IllegalClaimStateException) ex;
                        assertThat(e.getCode()).isEqualTo("WARRANTY_CLAIM_NOT_EDITABLE");
                        assertThat(e.getNextAction()).contains("DRAFT").contains("INFO_NEEDED");
                    });
            verify(claimRepository, never()).save(any());
        }

        @Test
        void addLineIsRejectedOnApprovedClaim() {
            stubLoad(claim(ClaimStatus.APPROVED));

            assertThatThrownBy(() -> service.addLine(
                            CLAIM_ID,
                            new ClaimLineRequest(
                                    LineSourceType.MANUAL,
                                    null,
                                    null,
                                    null,
                                    "SKU",
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null)))
                    .isInstanceOf(IllegalClaimStateException.class);
        }

        @Test
        void addLineWorksInInfoNeeded() {
            stubLoad(claim(ClaimStatus.INFO_NEEDED));
            stubSaveEcho();

            ClaimResponse response = service.addLine(
                    CLAIM_ID,
                    new ClaimLineRequest(
                            LineSourceType.MANUAL,
                            null,
                            null,
                            null,
                            "SKU-2",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null));

            assertThat(response.lines()).hasSize(2);
        }

        @Test
        void removeLineOfUnknownIdIs404() {
            stubLoad(claim(ClaimStatus.DRAFT));

            UUID unknownLine = UUID.fromString("018f0000-0000-7000-8000-0000000000ff");
            assertThatThrownBy(() -> service.removeLine(CLAIM_ID, unknownLine))
                    .isInstanceOf(WarrantyNotFoundException.class)
                    .satisfies(ex -> assertThat(((WarrantyNotFoundException) ex).getCode())
                            .isEqualTo(ClaimServiceImpl.LINE_NOT_FOUND_CODE));
        }

        @Test
        void addPhotoDeduplicates() {
            WarrantyClaim claim = claim(ClaimStatus.DRAFT);
            claim.setPhotoEvidenceUrls(List.of("https://p/1.jpg"));
            stubLoad(claim);
            stubSaveEcho();

            ClaimResponse response = service.addPhoto(CLAIM_ID, "https://p/1.jpg");

            assertThat(response.photoEvidenceUrls()).containsExactly("https://p/1.jpg");
        }

        @Test
        void removePhotoNotAttachedIs404() {
            stubLoad(claim(ClaimStatus.DRAFT));

            assertThatThrownBy(() -> service.removePhoto(CLAIM_ID, "https://p/none.jpg"))
                    .isInstanceOf(WarrantyNotFoundException.class)
                    .satisfies(ex -> assertThat(((WarrantyNotFoundException) ex).getCode())
                            .isEqualTo(ClaimServiceImpl.PHOTO_NOT_FOUND_CODE));
        }

        @Test
        void missingClaimIs404() {
            when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(CLAIM_ID, updateRequest(null)))
                    .isInstanceOf(WarrantyNotFoundException.class)
                    .satisfies(ex -> assertThat(((WarrantyNotFoundException) ex).getCode())
                            .isEqualTo(ClaimServiceImpl.NOT_FOUND_CODE));
        }
    }

    // ------------------------------------------------------------------ submit

    @Nested
    class Submit {

        @Test
        void draftSubmitRunsEligibilityAndAppendsHistory() {
            stubLoad(claim(ClaimStatus.DRAFT));
            stubSaveEcho();

            ClaimResponse response = service.submit(CLAIM_ID);

            assertThat(response.status()).isEqualTo(ClaimStatus.SUBMITTED);
            verify(eligibilityService).evaluate(CLAIM_ID);

            ArgumentCaptor<ClaimStatusHistory> history = ArgumentCaptor.captor();
            verify(statusHistoryRepository).save(history.capture());
            assertThat(history.getValue().getFromStatus()).isEqualTo(ClaimStatus.DRAFT);
            assertThat(history.getValue().getToStatus()).isEqualTo(ClaimStatus.SUBMITTED);
            assertThat(history.getValue().getReason()).isEqualTo("claim submitted");
            assertThat(history.getValue().getActor()).isNotBlank();
            // ADR-0044 R3 / PRD §9.3: every committed claim mutation emits the full snapshot.
            verify(claimSnapshotPublisher).publish(CLAIM_ID);
        }

        @Test
        void infoNeededSubmitReturnsToReview() {
            stubLoad(claim(ClaimStatus.INFO_NEEDED));
            stubSaveEcho();

            ClaimResponse response = service.submit(CLAIM_ID);

            assertThat(response.status()).isEqualTo(ClaimStatus.IN_REVIEW);
            ArgumentCaptor<ClaimStatusHistory> history = ArgumentCaptor.captor();
            verify(statusHistoryRepository).save(history.capture());
            assertThat(history.getValue().getReason()).isEqualTo("info provided");
        }

        @Test
        void submitOutsideDraftOrInfoNeededIsIllegalWithNextMoves() {
            stubLoad(claim(ClaimStatus.APPROVED));

            assertThatThrownBy(() -> service.submit(CLAIM_ID))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> assertThat(((IllegalClaimStateException) ex).getNextAction())
                            .contains("SETTLED"));
            verify(eligibilityService, never()).evaluate(any());
            // Refused mutation must not emit a snapshot — snapshot iff commit (ADR-0044 R3).
            verify(claimSnapshotPublisher, never()).publish(any());
        }

        @Test
        void submitWithoutLinesIsRejectedBeforeEligibility() {
            WarrantyClaim empty = claim(ClaimStatus.DRAFT);
            empty.getLines().clear();
            stubLoad(empty);

            assertThatThrownBy(() -> service.submit(CLAIM_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one claim line");
            verify(eligibilityService, never()).evaluate(any());
        }

        @Test
        void photoEvidenceEnforcedWhenWinningPolicyRequiresIt() {
            WarrantyClaim claim = claim(ClaimStatus.DRAFT);
            claim.setPolicyId(POLICY_ID);
            stubLoad(claim);
            when(policyRepository.findById(POLICY_ID))
                    .thenReturn(Optional.of(WarrantyPolicy.builder()
                            .id(POLICY_ID)
                            .name("Road Hazard Gold")
                            .requiresPhotoEvidence(true)
                            .build()));

            assertThatThrownBy(() -> service.submit(CLAIM_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("photo evidence");
            verify(eligibilityService).evaluate(CLAIM_ID);
            verify(statusHistoryRepository, never()).save(any());
            verify(claimRepository, never()).save(any());
            verify(claimSnapshotPublisher, never()).publish(any());
        }

        @Test
        void photoRequirementSatisfiedByAttachedPhoto() {
            WarrantyClaim claim = claim(ClaimStatus.DRAFT);
            claim.setPolicyId(POLICY_ID);
            claim.setPhotoEvidenceUrls(List.of("https://p/1.jpg"));
            stubLoad(claim);
            when(policyRepository.findById(POLICY_ID))
                    .thenReturn(Optional.of(WarrantyPolicy.builder()
                            .id(POLICY_ID)
                            .name("Road Hazard Gold")
                            .requiresPhotoEvidence(true)
                            .build()));
            stubSaveEcho();

            assertThat(service.submit(CLAIM_ID).status()).isEqualTo(ClaimStatus.SUBMITTED);
        }

        @Test
        void policyNotRequiringPhotosSubmitsWithoutAny() {
            WarrantyClaim claim = claim(ClaimStatus.DRAFT);
            claim.setPolicyId(POLICY_ID);
            stubLoad(claim);
            when(policyRepository.findById(POLICY_ID))
                    .thenReturn(Optional.of(WarrantyPolicy.builder()
                            .id(POLICY_ID)
                            .name("Standard")
                            .requiresPhotoEvidence(false)
                            .build()));
            stubSaveEcho();

            assertThat(service.submit(CLAIM_ID).status()).isEqualTo(ClaimStatus.SUBMITTED);
        }
    }

    // ------------------------------------------------------------------ decide

    @Nested
    class Decide {

        @Test
        void approveFromInReviewSetsDecisionActorAndTimestamp() {
            WarrantyClaim claim = claim(ClaimStatus.IN_REVIEW);
            claim.setEligibilityResult(EligibilityResult.ELIGIBLE);
            stubLoad(claim);
            stubSaveEcho();

            ClaimResponse response =
                    service.decide(CLAIM_ID, new ClaimDecisionRequest(ClaimDecisionRequest.Action.APPROVE, null));

            assertThat(response.status()).isEqualTo(ClaimStatus.APPROVED);
            assertThat(response.decision()).isEqualTo(ClaimDecision.APPROVE);
            assertThat(response.decidedBy()).isNotBlank();
            assertThat(response.decidedAt()).isNotNull();
            assertThat(response.overrodeSuggestion()).isFalse();
            // ADR-0044 R3 / PRD §9.3: every committed claim mutation emits the full snapshot.
            verify(claimSnapshotPublisher).publish(CLAIM_ID);
        }

        @Test
        void decisionOnSubmittedClaimImplicitlyBeginsReview() {
            WarrantyClaim claim = claim(ClaimStatus.SUBMITTED);
            claim.setEligibilityResult(EligibilityResult.ELIGIBLE);
            stubLoad(claim);
            stubSaveEcho();

            ClaimResponse response =
                    service.decide(CLAIM_ID, new ClaimDecisionRequest(ClaimDecisionRequest.Action.APPROVE, null));

            assertThat(response.status()).isEqualTo(ClaimStatus.APPROVED);
            ArgumentCaptor<ClaimStatusHistory> history = ArgumentCaptor.captor();
            verify(statusHistoryRepository, times(2)).save(history.capture());
            assertThat(history.getAllValues().get(0).getToStatus()).isEqualTo(ClaimStatus.IN_REVIEW);
            assertThat(history.getAllValues().get(0).getReason()).isEqualTo("review started");
            assertThat(history.getAllValues().get(1).getToStatus()).isEqualTo(ClaimStatus.APPROVED);
        }

        @Test
        void denyRequiresAReason() {
            WarrantyClaim claim = claim(ClaimStatus.IN_REVIEW);
            claim.setEligibilityResult(EligibilityResult.INDETERMINATE);
            stubLoad(claim);

            assertThatThrownBy(() ->
                            service.decide(CLAIM_ID, new ClaimDecisionRequest(ClaimDecisionRequest.Action.DENY, "  ")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requires a reason");
            verify(claimRepository, never()).save(any());
        }

        @Test
        void denyWithReasonLandsInDenied() {
            WarrantyClaim claim = claim(ClaimStatus.IN_REVIEW);
            claim.setEligibilityResult(EligibilityResult.INELIGIBLE);
            stubLoad(claim);
            stubSaveEcho();

            ClaimResponse response = service.decide(
                    CLAIM_ID, new ClaimDecisionRequest(ClaimDecisionRequest.Action.DENY, "outside mileage limit"));

            assertThat(response.status()).isEqualTo(ClaimStatus.DENIED);
            assertThat(response.decision()).isEqualTo(ClaimDecision.DENY);
            assertThat(response.decisionReason()).isEqualTo("outside mileage limit");
            assertThat(response.overrodeSuggestion()).isFalse();
        }

        @Test
        void approvingAnIneligibleSuggestionWithoutReasonIsRejected() {
            WarrantyClaim claim = claim(ClaimStatus.IN_REVIEW);
            claim.setEligibilityResult(EligibilityResult.INELIGIBLE);
            stubLoad(claim);

            assertThatThrownBy(() -> service.decide(
                            CLAIM_ID, new ClaimDecisionRequest(ClaimDecisionRequest.Action.APPROVE, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("override reason");
        }

        @Test
        void approvingAnIneligibleSuggestionWithReasonSetsOverrodeSuggestion() {
            WarrantyClaim claim = claim(ClaimStatus.IN_REVIEW);
            claim.setEligibilityResult(EligibilityResult.INELIGIBLE);
            stubLoad(claim);
            stubSaveEcho();

            ClaimResponse response = service.decide(
                    CLAIM_ID, new ClaimDecisionRequest(ClaimDecisionRequest.Action.APPROVE, "goodwill approval"));

            assertThat(response.status()).isEqualTo(ClaimStatus.APPROVED);
            assertThat(response.overrodeSuggestion()).isTrue();
            assertThat(response.decisionReason()).isEqualTo("goodwill approval");
        }

        @Test
        void denyingAnEligibleSuggestionSetsOverrodeSuggestion() {
            WarrantyClaim claim = claim(ClaimStatus.IN_REVIEW);
            claim.setEligibilityResult(EligibilityResult.ELIGIBLE);
            stubLoad(claim);
            stubSaveEcho();

            ClaimResponse response = service.decide(
                    CLAIM_ID, new ClaimDecisionRequest(ClaimDecisionRequest.Action.DENY, "photo shows road hazard"));

            assertThat(response.status()).isEqualTo(ClaimStatus.DENIED);
            assertThat(response.overrodeSuggestion()).isTrue();
        }

        @Test
        void requestInfoMovesToInfoNeeded() {
            WarrantyClaim claim = claim(ClaimStatus.IN_REVIEW);
            claim.setEligibilityResult(EligibilityResult.INDETERMINATE);
            stubLoad(claim);
            stubSaveEcho();

            ClaimResponse response = service.decide(
                    CLAIM_ID, new ClaimDecisionRequest(ClaimDecisionRequest.Action.REQUEST_INFO, "need tread depth"));

            assertThat(response.status()).isEqualTo(ClaimStatus.INFO_NEEDED);
            assertThat(response.decision()).isEqualTo(ClaimDecision.REQUEST_INFO);
        }

        @Test
        void decisionOnDraftClaimIsIllegal() {
            WarrantyClaim claim = claim(ClaimStatus.DRAFT);
            claim.setEligibilityResult(EligibilityResult.ELIGIBLE);
            stubLoad(claim);

            assertThatThrownBy(() -> service.decide(
                            CLAIM_ID, new ClaimDecisionRequest(ClaimDecisionRequest.Action.APPROVE, null)))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> assertThat(((IllegalClaimStateException) ex).getNextAction())
                            .contains("SUBMITTED"));
        }

        @Test
        void approveDefaultsLinesToApprovedAtComputedAmount() {
            WarrantyClaim claim = claim(ClaimStatus.IN_REVIEW);
            claim.setEligibilityResult(EligibilityResult.ELIGIBLE);
            claim.getLines().getFirst().setAmountRequested(new BigDecimal("80.00"));
            stubLoad(claim);
            stubSaveEcho();

            ClaimResponse response =
                    service.decide(CLAIM_ID, new ClaimDecisionRequest(ClaimDecisionRequest.Action.APPROVE, null));

            assertThat(response.overrodeSuggestion()).isFalse();
            assertThat(response.lines().getFirst().amountApproved()).isEqualByComparingTo("80.00");
            assertThat(response.lines().getFirst().lineDisposition()).isEqualTo(LineDisposition.APPROVED);
            verify(noteRepository, never()).save(any());
        }

        @Test
        void denyDefaultsLinesToDenied() {
            WarrantyClaim claim = claim(ClaimStatus.IN_REVIEW);
            claim.setEligibilityResult(EligibilityResult.INELIGIBLE);
            claim.getLines().getFirst().setAmountRequested(new BigDecimal("80.00"));
            stubLoad(claim);
            stubSaveEcho();

            ClaimResponse response =
                    service.decide(CLAIM_ID, new ClaimDecisionRequest(ClaimDecisionRequest.Action.DENY, "not covered"));

            assertThat(response.lines().getFirst().lineDisposition()).isEqualTo(LineDisposition.DENIED);
            assertThat(response.lines().getFirst().amountApproved()).isNull();
        }

        @Test
        void lineAmountOverrideWithoutReasonIsRejected() {
            WarrantyClaim claim = claim(ClaimStatus.IN_REVIEW);
            claim.setEligibilityResult(EligibilityResult.ELIGIBLE);
            claim.getLines().getFirst().setAmountRequested(new BigDecimal("80.00"));
            stubLoad(claim);

            assertThatThrownBy(() -> service.decide(
                            CLAIM_ID,
                            new ClaimDecisionRequest(
                                    ClaimDecisionRequest.Action.APPROVE,
                                    null,
                                    List.of(new ClaimDecisionRequest.LineDecision(
                                            LINE_ID, new BigDecimal("50.00"), null, null)))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("override reason is required");
            verify(claimRepository, never()).save(any());
        }

        @Test
        void lineAmountOverrideWithReasonPersistsAmountDispositionAndAuditNote() {
            WarrantyClaim claim = claim(ClaimStatus.IN_REVIEW);
            claim.setEligibilityResult(EligibilityResult.ELIGIBLE);
            claim.getLines().getFirst().setAmountRequested(new BigDecimal("80.00"));
            stubLoad(claim);
            stubSaveEcho();

            ClaimResponse response = service.decide(
                    CLAIM_ID,
                    new ClaimDecisionRequest(
                            ClaimDecisionRequest.Action.APPROVE,
                            null,
                            List.of(new ClaimDecisionRequest.LineDecision(
                                    LINE_ID,
                                    new BigDecimal("50.00"),
                                    LineDisposition.PARTIALLY_APPROVED,
                                    "vendor guidance caps sidewall claims"))));

            assertThat(response.status()).isEqualTo(ClaimStatus.APPROVED);
            assertThat(response.overrodeSuggestion()).isTrue();
            assertThat(response.lines().getFirst().amountApproved()).isEqualByComparingTo("50.00");
            assertThat(response.lines().getFirst().lineDisposition()).isEqualTo(LineDisposition.PARTIALLY_APPROVED);

            ArgumentCaptor<ClaimNote> note = ArgumentCaptor.captor();
            verify(noteRepository).save(note.capture());
            assertThat(note.getValue().getNote())
                    .contains("amount override")
                    .contains("vendor guidance caps sidewall claims")
                    .contains("80.00")
                    .contains("50.00");
        }

        @Test
        void lineDecisionMatchingComputedAmountNeedsNoReason() {
            WarrantyClaim claim = claim(ClaimStatus.IN_REVIEW);
            claim.setEligibilityResult(EligibilityResult.ELIGIBLE);
            claim.getLines().getFirst().setAmountRequested(new BigDecimal("80.00"));
            stubLoad(claim);
            stubSaveEcho();

            ClaimResponse response = service.decide(
                    CLAIM_ID,
                    new ClaimDecisionRequest(
                            ClaimDecisionRequest.Action.APPROVE,
                            null,
                            List.of(new ClaimDecisionRequest.LineDecision(
                                    LINE_ID, new BigDecimal("80.00"), null, null))));

            assertThat(response.overrodeSuggestion()).isFalse();
            assertThat(response.lines().getFirst().amountApproved()).isEqualByComparingTo("80.00");
            verify(noteRepository, never()).save(any());
        }

        @Test
        void lineDecisionForUnknownLineIs404() {
            WarrantyClaim claim = claim(ClaimStatus.IN_REVIEW);
            claim.setEligibilityResult(EligibilityResult.ELIGIBLE);
            stubLoad(claim);

            UUID unknownLine = UUID.fromString("018f0000-0000-7000-8000-0000000000fe");
            assertThatThrownBy(() -> service.decide(
                            CLAIM_ID,
                            new ClaimDecisionRequest(
                                    ClaimDecisionRequest.Action.APPROVE,
                                    null,
                                    List.of(new ClaimDecisionRequest.LineDecision(unknownLine, null, null, null)))))
                    .isInstanceOf(WarrantyNotFoundException.class)
                    .satisfies(ex -> assertThat(((WarrantyNotFoundException) ex).getCode())
                            .isEqualTo(ClaimServiceImpl.LINE_NOT_FOUND_CODE));
        }

        @Test
        void lineDecisionsAreRejectedWithRequestInfo() {
            WarrantyClaim claim = claim(ClaimStatus.IN_REVIEW);
            claim.setEligibilityResult(EligibilityResult.INDETERMINATE);
            stubLoad(claim);

            assertThatThrownBy(() -> service.decide(
                            CLAIM_ID,
                            new ClaimDecisionRequest(
                                    ClaimDecisionRequest.Action.REQUEST_INFO,
                                    "need tread depth",
                                    List.of(new ClaimDecisionRequest.LineDecision(LINE_ID, null, null, null)))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("APPROVE or DENY");
        }

        @Test
        void appealReopensReviewAndClearsStandingDecision() {
            WarrantyClaim claim = claim(ClaimStatus.DENIED);
            claim.setDecision(ClaimDecision.DENY);
            claim.setDecisionReason("worn out");
            claim.setDecidedBy("manager");
            claim.setOverrodeSuggestion(true);
            stubLoad(claim);
            stubSaveEcho();

            ClaimResponse response = service.decide(
                    CLAIM_ID, new ClaimDecisionRequest(ClaimDecisionRequest.Action.APPEAL, "customer disputes"));

            assertThat(response.status()).isEqualTo(ClaimStatus.IN_REVIEW);
            assertThat(response.decision()).isNull();
            assertThat(response.decisionReason()).isNull();
            assertThat(response.decidedBy()).isNull();
            assertThat(response.decidedAt()).isNull();
            assertThat(response.overrodeSuggestion()).isFalse();

            ArgumentCaptor<ClaimStatusHistory> history = ArgumentCaptor.captor();
            verify(statusHistoryRepository).save(history.capture());
            assertThat(history.getValue().getReason()).isEqualTo("appeal: customer disputes");
        }

        @Test
        void appealRequiresAReason() {
            stubLoad(claim(ClaimStatus.DENIED));

            assertThatThrownBy(() -> service.decide(
                            CLAIM_ID, new ClaimDecisionRequest(ClaimDecisionRequest.Action.APPEAL, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requires a reason");
        }

        @Test
        void appealIsOnlyLegalFromDenied() {
            stubLoad(claim(ClaimStatus.IN_REVIEW));

            assertThatThrownBy(() -> service.decide(
                            CLAIM_ID, new ClaimDecisionRequest(ClaimDecisionRequest.Action.APPEAL, "please")))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> assertThat(((IllegalClaimStateException) ex).getMessage())
                            .contains("appeal"));
        }
    }

    // ------------------------------------------------------------------ cancel / close

    @Nested
    class Cancel {

        @Test
        void cancelFromDraftRecordsReasonInHistory() {
            stubLoad(claim(ClaimStatus.DRAFT));
            stubSaveEcho();

            ClaimResponse response = service.cancel(CLAIM_ID, new ClaimActionRequest("customer withdrew"));

            assertThat(response.status()).isEqualTo(ClaimStatus.CANCELLED);
            ArgumentCaptor<ClaimStatusHistory> history = ArgumentCaptor.captor();
            verify(statusHistoryRepository).save(history.capture());
            assertThat(history.getValue().getReason()).isEqualTo("customer withdrew");
            verify(claimSnapshotPublisher).publish(CLAIM_ID);
        }

        @Test
        void cancelFromApprovedIsIllegal() {
            stubLoad(claim(ClaimStatus.APPROVED));

            assertThatThrownBy(() -> service.cancel(CLAIM_ID, new ClaimActionRequest(null)))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> assertThat(((IllegalClaimStateException) ex).getNextAction())
                            .contains("SETTLED"));
            verify(claimSnapshotPublisher, never()).publish(any());
        }
    }

    @Nested
    class Close {

        private static VendorReimbursement reimbursement(ReimbursementStatus status) {
            return VendorReimbursement.builder()
                    .claimId(CLAIM_ID)
                    .status(status)
                    .build();
        }

        private static PartReturn partReturn(PartReturnStatus status) {
            return PartReturn.builder().claimId(CLAIM_ID).status(status).build();
        }

        @Test
        void closesSettledClaimWhenAllChildrenTerminal() {
            stubLoad(claim(ClaimStatus.SETTLED));
            when(reimbursementRepository.findByClaimId(CLAIM_ID))
                    .thenReturn(List.of(reimbursement(ReimbursementStatus.CREDIT_RECEIVED)));
            when(partReturnRepository.findByClaimId(CLAIM_ID))
                    .thenReturn(List.of(partReturn(PartReturnStatus.RECEIVED_BY_VENDOR)));
            stubSaveEcho();

            ClaimResponse response = service.close(CLAIM_ID, new ClaimActionRequest(null));

            assertThat(response.status()).isEqualTo(ClaimStatus.CLOSED);
            verify(claimSnapshotPublisher).publish(CLAIM_ID);
        }

        @Test
        void writtenOffAndScrappedChildrenAlsoAllowClose() {
            stubLoad(claim(ClaimStatus.SETTLED));
            when(reimbursementRepository.findByClaimId(CLAIM_ID))
                    .thenReturn(List.of(
                            reimbursement(ReimbursementStatus.WRITTEN_OFF),
                            reimbursement(ReimbursementStatus.NOT_APPLICABLE),
                            reimbursement(ReimbursementStatus.DENIED)));
            when(partReturnRepository.findByClaimId(CLAIM_ID))
                    .thenReturn(List.of(partReturn(PartReturnStatus.SCRAPPED), partReturn(PartReturnStatus.CLOSED)));
            stubSaveEcho();

            assertThat(service.close(CLAIM_ID, new ClaimActionRequest(null)).status())
                    .isEqualTo(ClaimStatus.CLOSED);
            verify(claimSnapshotPublisher).publish(CLAIM_ID);
        }

        @Test
        void openReimbursementBlocksClose() {
            stubLoad(claim(ClaimStatus.SETTLED));
            when(reimbursementRepository.findByClaimId(CLAIM_ID))
                    .thenReturn(List.of(reimbursement(ReimbursementStatus.SUBMITTED)));
            when(partReturnRepository.findByClaimId(CLAIM_ID)).thenReturn(List.of());

            assertThatThrownBy(() -> service.close(CLAIM_ID, new ClaimActionRequest(null)))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> {
                        IllegalClaimStateException e = (IllegalClaimStateException) ex;
                        assertThat(e.getCode()).isEqualTo(ClaimServiceImpl.CLOSE_BLOCKED_CODE);
                        assertThat(e.getNextAction()).contains("CREDIT_RECEIVED");
                    });
            verify(claimRepository, never()).save(any());
            verify(claimSnapshotPublisher, never()).publish(any());
        }

        @Test
        void openPartReturnBlocksClose() {
            stubLoad(claim(ClaimStatus.SETTLED));
            when(reimbursementRepository.findByClaimId(CLAIM_ID))
                    .thenReturn(List.of(reimbursement(ReimbursementStatus.CREDIT_RECEIVED)));
            when(partReturnRepository.findByClaimId(CLAIM_ID))
                    .thenReturn(List.of(partReturn(PartReturnStatus.ON_HOLD)));

            assertThatThrownBy(() -> service.close(CLAIM_ID, new ClaimActionRequest(null)))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> assertThat(((IllegalClaimStateException) ex).getCode())
                            .isEqualTo(ClaimServiceImpl.CLOSE_BLOCKED_CODE));
            verify(claimSnapshotPublisher, never()).publish(any());
        }

        @Test
        void requiresPartReturnPolicyBlocksCloseWhenNoPartReturnExists() {
            WarrantyClaim claim = claim(ClaimStatus.SETTLED);
            claim.setPolicyId(POLICY_ID);
            stubLoad(claim);
            when(reimbursementRepository.findByClaimId(CLAIM_ID))
                    .thenReturn(List.of(reimbursement(ReimbursementStatus.CREDIT_RECEIVED)));
            when(partReturnRepository.findByClaimId(CLAIM_ID)).thenReturn(List.of());
            when(policyRepository.findById(POLICY_ID))
                    .thenReturn(Optional.of(WarrantyPolicy.builder()
                            .id(POLICY_ID)
                            .name("Michelin RMA-mandatory")
                            .requiresPartReturn(true)
                            .build()));

            assertThatThrownBy(() -> service.close(CLAIM_ID, new ClaimActionRequest(null)))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> {
                        IllegalClaimStateException e = (IllegalClaimStateException) ex;
                        assertThat(e.getCode()).isEqualTo(ClaimServiceImpl.CLOSE_BLOCKED_CODE);
                        assertThat(e.getNextAction()).contains("part-returns");
                    });
            verify(claimRepository, never()).save(any());
            verify(claimSnapshotPublisher, never()).publish(any());
        }

        @Test
        void requiresPartReturnPolicyAllowsCloseWithTerminalPartReturn() {
            WarrantyClaim claim = claim(ClaimStatus.SETTLED);
            claim.setPolicyId(POLICY_ID);
            stubLoad(claim);
            when(reimbursementRepository.findByClaimId(CLAIM_ID))
                    .thenReturn(List.of(reimbursement(ReimbursementStatus.CREDIT_RECEIVED)));
            when(partReturnRepository.findByClaimId(CLAIM_ID))
                    .thenReturn(List.of(partReturn(PartReturnStatus.RECEIVED_BY_VENDOR)));
            when(policyRepository.findById(POLICY_ID))
                    .thenReturn(Optional.of(WarrantyPolicy.builder()
                            .id(POLICY_ID)
                            .name("Michelin RMA-mandatory")
                            .requiresPartReturn(true)
                            .build()));
            stubSaveEcho();

            assertThat(service.close(CLAIM_ID, new ClaimActionRequest(null)).status())
                    .isEqualTo(ClaimStatus.CLOSED);
            verify(claimSnapshotPublisher).publish(CLAIM_ID);
        }

        @Test
        void deniedClaimWithNoChildrenCanClose() {
            stubLoad(claim(ClaimStatus.DENIED));
            when(reimbursementRepository.findByClaimId(CLAIM_ID)).thenReturn(List.of());
            when(partReturnRepository.findByClaimId(CLAIM_ID)).thenReturn(List.of());
            stubSaveEcho();

            assertThat(service.close(CLAIM_ID, new ClaimActionRequest("denied and done"))
                            .status())
                    .isEqualTo(ClaimStatus.CLOSED);
            verify(claimSnapshotPublisher).publish(CLAIM_ID);
        }

        @Test
        void closeFromInReviewFailsOnStateMachineBeforeChildGate() {
            stubLoad(claim(ClaimStatus.IN_REVIEW));

            assertThatThrownBy(() -> service.close(CLAIM_ID, new ClaimActionRequest(null)))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> assertThat(((IllegalClaimStateException) ex).getNextAction())
                            .contains("APPROVED")
                            .contains("DENIED"));
            verify(reimbursementRepository, never()).findByClaimId(any());
            verify(partReturnRepository, never()).findByClaimId(any());
            verify(claimSnapshotPublisher, never()).publish(any());
        }
    }

    // ------------------------------------------------------------------ eligibility re-run

    @Nested
    class EvaluateEligibility {

        @Test
        void rerunsOnPreDecisionClaims() {
            for (ClaimStatus status :
                    List.of(ClaimStatus.DRAFT, ClaimStatus.SUBMITTED, ClaimStatus.IN_REVIEW, ClaimStatus.INFO_NEEDED)) {
                stubLoad(claim(status));

                service.evaluateEligibility(CLAIM_ID);
            }

            verify(eligibilityService, times(4)).evaluate(CLAIM_ID);
        }

        @Test
        void rejectedOnceDecidedOrSettled() {
            // Proration inputs and coverage selection are frozen audit evidence after the
            // decision (PRD §6) — recompute must not silently rewrite them.
            for (ClaimStatus status : List.of(ClaimStatus.APPROVED, ClaimStatus.DENIED, ClaimStatus.SETTLED)) {
                stubLoad(claim(status));

                assertThatThrownBy(() -> service.evaluateEligibility(CLAIM_ID))
                        .isInstanceOf(IllegalClaimStateException.class)
                        .satisfies(ex -> assertThat(((IllegalClaimStateException) ex).getMessage())
                                .contains("re-run eligibility"));
            }
            verify(eligibilityService, never()).evaluate(any());
        }

        @Test
        void rejectedOnTerminalClaim() {
            stubLoad(claim(ClaimStatus.CANCELLED));

            assertThatThrownBy(() -> service.evaluateEligibility(CLAIM_ID))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> assertThat(((IllegalClaimStateException) ex).getNextAction())
                            .contains("terminal"));
            verify(eligibilityService, never()).evaluate(any());
        }
    }
}
