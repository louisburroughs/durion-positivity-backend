package com.positivity.workorder.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.dto.ApproveEstimateRequest;
import com.positivity.workorder.internal.dto.CreateEstimateRequest;
import com.positivity.workorder.internal.dto.EstimateItemResponse;
import com.positivity.workorder.internal.dto.EstimateResponse;
import com.positivity.workorder.internal.dto.EstimateSnapshotResponse;
import com.positivity.workorder.internal.dto.EstimateSummaryResponse;
import com.positivity.workorder.internal.dto.WorkorderResponse;
import com.positivity.workorder.internal.exception.CustomerRequirementsNotMetException;
import com.positivity.workorder.internal.exception.EstimateItemNotFoundException;
import com.positivity.workorder.internal.exception.EstimateNotFoundException;
import com.positivity.workorder.internal.exception.PromotionIdempotencyInconsistencyException;
import com.positivity.workorder.internal.exception.PromotionValidationException;
import com.positivity.workorder.internal.exception.WorkorderRequestValidationException;
import com.positivity.workorder.internal.exception.WorkorderResourceConflictException;
import com.positivity.workorder.internal.service.EstimateService;
import com.positivity.workorder.internal.service.IdempotencyService;
import com.positivity.workorder.internal.service.WorkorderService;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * Estimate endpoints: the controller owns status mapping and the Idempotency-Key replay contract,
 * while every business rule lives in {@code EstimateService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EstimateController")
class EstimateControllerTest {

    private static final UUID ESTIMATE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a01");
    private static final UUID CUSTOMER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a02");
    private static final UUID VEHICLE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a03");
    private static final UUID LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a04");
    private static final UUID ITEM_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a05");
    private static final UUID WORKORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a06");
    private static final UUID OTHER_WORKORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a07");
    private static final String IDEMPOTENCY_KEY = "estimate-key-1";
    private static final String CREATE_OPERATION = "estimate.create";
    private static final String PROMOTE_OPERATION = "estimate.promote";

    @Mock
    private EstimateService estimateService;

    @Mock
    private WorkorderService workorderService;

    @Mock
    private IdempotencyService idempotencyService;

    @InjectMocks
    private EstimateController controller;

    private WorkorderResponse workorder;

    @BeforeEach
    void setUp() {
        workorder = WorkorderResponse.builder()
                .id(WORKORDER_ID)
                .estimateId(ESTIMATE_ID)
                .customerId(CUSTOMER_ID)
                .build();
    }

    private static EstimateResponse estimate() {
        return EstimateResponse.builder()
                .id(ESTIMATE_ID)
                .estimateNumber("EST-0001")
                .customerId(CUSTOMER_ID)
                .vehicleId(VEHICLE_ID)
                .locationId(LOCATION_ID)
                .status("DRAFT")
                .subtotal(new BigDecimal("100.00"))
                .taxAmount(new BigDecimal("8.00"))
                .total(new BigDecimal("108.00"))
                .build();
    }

    private static CreateEstimateRequest createRequest() {
        return CreateEstimateRequest.builder()
                .customerId(CUSTOMER_ID)
                .vehicleId(VEHICLE_ID)
                .locationId(LOCATION_ID)
                .build();
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        void listsEveryEstimate() {
            when(estimateService.getAllEstimates()).thenReturn(List.of(estimate()));

            assertThat(controller.getAllEstimates()).hasSize(1);
        }

        @Test
        void servesOneEstimateById() {
            when(estimateService.getEstimateById(ESTIMATE_ID)).thenReturn(Optional.of(estimate()));

            ResponseEntity<EstimateResponse> response = controller.getEstimateById(ESTIMATE_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getEstimateNumber()).isEqualTo("EST-0001");
        }

        @Test
        void reportsNotFoundForAnUnknownEstimate() {
            when(estimateService.getEstimateById(ESTIMATE_ID)).thenReturn(Optional.empty());

            assertThat(controller.getEstimateById(ESTIMATE_ID).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void listsACustomersEstimates() {
            when(estimateService.getEstimatesByCustomer(CUSTOMER_ID)).thenReturn(List.of(estimate()));

            assertThat(controller.getEstimatesByCustomer(CUSTOMER_ID)).hasSize(1);
        }

        @Test
        void servesTheShopAliasFromTheSameLocationLookup() {
            when(estimateService.getEstimatesByLocation(LOCATION_ID)).thenReturn(List.of(estimate()));

            assertThat(controller.getEstimatesByShop(LOCATION_ID)).hasSize(1);
            assertThat(controller.getEstimatesByLocation(LOCATION_ID)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("createEstimate")
    class CreateEstimate {

        @Test
        void createsTheEstimateAndReportsCreated() {
            when(estimateService.createEstimate(any(), anyString())).thenReturn(estimate());

            ResponseEntity<Object> response = controller.createEstimate(createRequest(), null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody()).isInstanceOf(EstimateResponse.class);
            verify(idempotencyService, never()).registerKey(anyString(), anyString(), any());
        }

        @Test
        void registersTheIdempotencyKeyAgainstTheNewEstimate() {
            when(estimateService.createEstimate(any(), anyString())).thenReturn(estimate());

            controller.createEstimate(createRequest(), IDEMPOTENCY_KEY);

            verify(idempotencyService).registerKey(CREATE_OPERATION, IDEMPOTENCY_KEY, ESTIMATE_ID);
        }

        @Test
        void toleratesAConcurrentRegistrationOfTheSameKey() {
            when(estimateService.createEstimate(any(), anyString())).thenReturn(estimate());
            doThrow(new DataIntegrityViolationException("duplicate key"))
                    .when(idempotencyService)
                    .registerKey(CREATE_OPERATION, IDEMPOTENCY_KEY, ESTIMATE_ID);

            assertThat(controller
                            .createEstimate(createRequest(), IDEMPOTENCY_KEY)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }

        @Test
        void replaysTheOriginalEstimateForAKnownIdempotencyKey() {
            when(idempotencyService.getExistingWorkorderId(CREATE_OPERATION, IDEMPOTENCY_KEY))
                    .thenReturn(Optional.of(ESTIMATE_ID));
            when(estimateService.getEstimateById(ESTIMATE_ID)).thenReturn(Optional.of(estimate()));

            ResponseEntity<Object> response = controller.createEstimate(createRequest(), IDEMPOTENCY_KEY);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            verify(estimateService, never()).createEstimate(any(), anyString());
        }

        @Test
        void reportsAConflictWhenTheReplayedEstimateNoLongerExists() {
            when(idempotencyService.getExistingWorkorderId(CREATE_OPERATION, IDEMPOTENCY_KEY))
                    .thenReturn(Optional.of(ESTIMATE_ID));
            when(estimateService.getEstimateById(ESTIMATE_ID)).thenReturn(Optional.empty());

            ResponseEntity<Object> response = controller.createEstimate(createRequest(), IDEMPOTENCY_KEY);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isEqualTo(Map.of("code", "CONFLICT"));
        }

        @Test
        void ignoresABlankIdempotencyKey() {
            when(estimateService.createEstimate(any(), anyString())).thenReturn(estimate());

            controller.createEstimate(createRequest(), "   ");

            verify(idempotencyService, never()).getExistingWorkorderId(anyString(), anyString());
            verify(idempotencyService, never()).registerKey(anyString(), anyString(), any());
        }

        @Test
        void reportsAValidationErrorForAWorkorderRequestValidationException() {
            when(estimateService.createEstimate(any(), anyString()))
                    .thenThrow(new WorkorderRequestValidationException("customerId is required"));

            ResponseEntity<Object> response = controller.createEstimate(createRequest(), null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isEqualTo(Map.of("code", "VALIDATION_ERROR"));
        }

        @Test
        void reportsAConflictForAnIllegalStateOrIntegrityFailure() {
            doThrow(new IllegalStateException("totals do not add up"))
                    .when(estimateService)
                    .createEstimate(any(), anyString());
            assertThat(controller.createEstimate(createRequest(), null).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);

            doThrow(new DataIntegrityViolationException("duplicate estimate number"))
                    .when(estimateService)
                    .createEstimate(any(), anyString());
            assertThat(controller.createEstimate(createRequest(), null).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        void reportsAnInternalErrorForAnythingElse() {
            when(estimateService.createEstimate(any(), anyString())).thenThrow(new RuntimeException("boom"));

            assertThat(controller.createEstimate(createRequest(), null).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("patchEstimateStatus")
    class PatchEstimateStatus {

        @Test
        void delegatesADeclineTargetToTheDeclineFlowWithNoReason() {
            when(estimateService.declineEstimate(ESTIMATE_ID, null)).thenReturn(estimate());

            ResponseEntity<Object> response = controller.patchEstimateStatus(ESTIMATE_ID, Map.of("status", "DECLINED"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(estimateService).declineEstimate(ESTIMATE_ID, null);
        }

        @Test
        void delegatesADraftTargetToTheReopenFlow() {
            when(estimateService.reopenEstimate(ESTIMATE_ID)).thenReturn(estimate());

            assertThat(controller
                            .patchEstimateStatus(ESTIMATE_ID, Map.of("status", "DRAFT"))
                            .getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        void refusesAnyOtherTargetStatus() {
            ResponseEntity<Object> response = controller.patchEstimateStatus(ESTIMATE_ID, Map.of("status", "APPROVED"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isEqualTo(Map.of("code", "CONFLICT"));
        }

        @Test
        void rejectsAMissingOrNonStringStatus() {
            assertThat(controller.patchEstimateStatus(ESTIMATE_ID, Map.of()).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(controller
                            .patchEstimateStatus(ESTIMATE_ID, Map.of("status", 42))
                            .getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(controller
                            .patchEstimateStatus(ESTIMATE_ID, Map.of("status", "  "))
                            .getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void rejectsAStatusThatIsNotAKnownValue() {
            ResponseEntity<Object> response =
                    controller.patchEstimateStatus(ESTIMATE_ID, Map.of("status", "NOT_A_STATUS"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isEqualTo(Map.of("code", "VALIDATION_ERROR"));
        }

        @Test
        void mapsADelegatedFailureOntoTheRightStatus() {
            doThrow(new EstimateNotFoundException(ESTIMATE_ID))
                    .when(estimateService)
                    .declineEstimate(ESTIMATE_ID, null);
            assertThatThrownBy(() -> controller.patchEstimateStatus(ESTIMATE_ID, Map.of("status", "DECLINED")))
                    .isInstanceOf(EstimateNotFoundException.class);

            doThrow(new IllegalStateException("expired")).when(estimateService).reopenEstimate(ESTIMATE_ID);
            assertThat(controller
                            .patchEstimateStatus(ESTIMATE_ID, Map.of("status", "DRAFT"))
                            .getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Nested
    @DisplayName("decline, reopen, submit and approve")
    class Transitions {

        @Test
        void declinesWithTheSuppliedReason() {
            when(estimateService.declineEstimate(ESTIMATE_ID, "too expensive")).thenReturn(estimate());

            assertThat(controller.declineEstimate(ESTIMATE_ID, "too expensive").getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        void reportsBadRequestWhenADeclineIsNotAllowed() {
            when(estimateService.declineEstimate(any(), any()))
                    .thenThrow(new IllegalStateException("already declined"));

            assertThat(controller.declineEstimate(ESTIMATE_ID, null).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void reopensADeclinedEstimate() {
            when(estimateService.reopenEstimate(ESTIMATE_ID)).thenReturn(estimate());

            assertThat(controller.reopenEstimate(ESTIMATE_ID).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void reportsBadRequestWhenAReopenIsNotAllowed() {
            when(estimateService.reopenEstimate(ESTIMATE_ID)).thenThrow(new IllegalStateException("expired"));

            assertThat(controller.reopenEstimate(ESTIMATE_ID).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void submitsForApprovalUnderTheAuthenticatedUser() {
            when(estimateService.submitForApproval(eq(ESTIMATE_ID), anyString()))
                    .thenReturn(estimate());

            assertThat(controller.submitForApproval(ESTIMATE_ID).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            verify(estimateService).submitForApproval(ESTIMATE_ID, "SYSTEM");
        }

        @Test
        void mapsSubmissionFailuresOntoNotFoundOrBadRequest() {
            // #1713: the controller no longer catches a not-found to build a bodiless 404. The
            // module's own type propagates to GlobalExceptionHandler, which answers the enveloped,
            // correlated 404 required by ADR-0017 §3/§4.
            doThrow(new EstimateNotFoundException(ESTIMATE_ID))
                    .when(estimateService)
                    .submitForApproval(any(), anyString());
            assertThatThrownBy(() -> controller.submitForApproval(ESTIMATE_ID))
                    .isInstanceOf(EstimateNotFoundException.class);

            doThrow(new IllegalStateException("not a draft"))
                    .when(estimateService)
                    .submitForApproval(any(), anyString());
            assertThat(controller.submitForApproval(ESTIMATE_ID).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void passesEverySignatureFieldThroughOnApproval() {
            ApproveEstimateRequest request = ApproveEstimateRequest.builder()
                    .customerId(CUSTOMER_ID)
                    .signatureData("base64-signature")
                    .signerName("Jane Customer")
                    .notes("approved")
                    .purchaseOrderNumber("PO-12345")
                    .build();
            when(estimateService.approveEstimate(
                            ESTIMATE_ID,
                            CUSTOMER_ID,
                            "base64-signature",
                            "image/png",
                            "Jane Customer",
                            "approved",
                            "PO-12345",
                            null))
                    .thenReturn(estimate());

            assertThat(controller.approveEstimate(ESTIMATE_ID, request).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        void propagatesApprovalFailuresInsteadOfCatchingThem() {
            ApproveEstimateRequest request =
                    ApproveEstimateRequest.builder().customerId(CUSTOMER_ID).build();
            // The call is direct, so no advice runs: these assertions cover propagation out of
            // the controller only, and the resulting envelopes are asserted over the wire in the
            // contract ITs.
            //
            // #1713: propagates instead of being caught here and answered as a bodiless 404.
            doThrow(new EstimateNotFoundException(ESTIMATE_ID))
                    .when(estimateService)
                    .approveEstimate(any(), any(), any(), any(), any(), any(), any(), any());
            assertThatThrownBy(() -> controller.approveEstimate(ESTIMATE_ID, request))
                    .isInstanceOf(EstimateNotFoundException.class);

            // #1753: the invalid-state refusal propagates too. It was previously caught here and
            // answered as a bodiless 400 that the OpenAPI contract nonetheless documented as an
            // ApiError.
            doThrow(new WorkorderResourceConflictException("Estimate cannot be approved in current state: DRAFT"))
                    .when(estimateService)
                    .approveEstimate(any(), any(), any(), any(), any(), any(), any(), any());
            assertThatThrownBy(() -> controller.approveEstimate(ESTIMATE_ID, request))
                    .isInstanceOf(WorkorderResourceConflictException.class);
        }
    }

    @Nested
    @DisplayName("promoteEstimateToWorkorder")
    class PromoteEstimateToWorkorder {

        @Test
        void promotesAnApprovedEstimateIntoANewWorkorder() {
            when(workorderService.createWorkorder(ESTIMATE_ID, null)).thenReturn(workorder);

            ResponseEntity<WorkorderResponse> response = controller.promoteEstimateToWorkorder(ESTIMATE_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getId()).isEqualTo(WORKORDER_ID);
        }

        @Test
        void replaysTheOriginalWorkorderForAKnownIdempotencyKey() {
            when(idempotencyService.getExistingWorkorderId(PROMOTE_OPERATION, IDEMPOTENCY_KEY))
                    .thenReturn(Optional.of(WORKORDER_ID));
            when(workorderService.getWorkorderById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

            ResponseEntity<WorkorderResponse> response =
                    controller.promoteEstimateToWorkorder(ESTIMATE_ID, IDEMPOTENCY_KEY);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(workorderService, never()).createWorkorder(any(UUID.class), any());
        }

        @Test
        void reportsAnInternalErrorWhenTheReplayedWorkorderIsMissing() {
            when(idempotencyService.getExistingWorkorderId(PROMOTE_OPERATION, IDEMPOTENCY_KEY))
                    .thenReturn(Optional.of(WORKORDER_ID));
            when(workorderService.getWorkorderById(WORKORDER_ID)).thenReturn(Optional.empty());

            // #1477: an enveloped 500 from the advice, not a bodiless one built here.
            assertThatThrownBy(() -> controller.promoteEstimateToWorkorder(ESTIMATE_ID, IDEMPOTENCY_KEY))
                    .isInstanceOf(PromotionIdempotencyInconsistencyException.class);
        }

        @Test
        void registersTheIdempotencyKeyAgainstTheNewWorkorder() {
            when(workorderService.createWorkorder(ESTIMATE_ID, null)).thenReturn(workorder);

            controller.promoteEstimateToWorkorder(ESTIMATE_ID, IDEMPOTENCY_KEY);

            verify(idempotencyService).registerKey(PROMOTE_OPERATION, IDEMPOTENCY_KEY, WORKORDER_ID);
        }

        @Test
        void servesTheWinnersWorkorderWhenTwoRequestsRaceOnTheSameKey() {
            when(workorderService.createWorkorder(ESTIMATE_ID, null)).thenReturn(workorder);
            doThrow(new DataIntegrityViolationException("duplicate key"))
                    .when(idempotencyService)
                    .registerKey(PROMOTE_OPERATION, IDEMPOTENCY_KEY, WORKORDER_ID);
            when(idempotencyService.getExistingWorkorderId(PROMOTE_OPERATION, IDEMPOTENCY_KEY))
                    .thenReturn(Optional.empty(), Optional.of(OTHER_WORKORDER_ID));
            WorkorderResponse winner =
                    WorkorderResponse.builder().id(OTHER_WORKORDER_ID).build();
            when(workorderService.getWorkorderById(OTHER_WORKORDER_ID)).thenReturn(Optional.of(winner));

            ResponseEntity<WorkorderResponse> response =
                    controller.promoteEstimateToWorkorder(ESTIMATE_ID, IDEMPOTENCY_KEY);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getId()).isEqualTo(OTHER_WORKORDER_ID);
        }

        @Test
        void keepsItsOwnWorkorderWhenTheDuplicateKeyPointsAtTheSameOne() {
            when(workorderService.createWorkorder(ESTIMATE_ID, null)).thenReturn(workorder);
            doThrow(new DataIntegrityViolationException("duplicate key"))
                    .when(idempotencyService)
                    .registerKey(PROMOTE_OPERATION, IDEMPOTENCY_KEY, WORKORDER_ID);
            when(idempotencyService.getExistingWorkorderId(PROMOTE_OPERATION, IDEMPOTENCY_KEY))
                    .thenReturn(Optional.empty(), Optional.of(WORKORDER_ID));

            ResponseEntity<WorkorderResponse> response =
                    controller.promoteEstimateToWorkorder(ESTIMATE_ID, IDEMPOTENCY_KEY);

            assertThat(response.getBody().getId()).isEqualTo(WORKORDER_ID);
        }

        @Test
        void answersAnAlreadyPromotedEstimateWithItsExistingWorkorder() {
            when(workorderService.createWorkorder(ESTIMATE_ID, null))
                    .thenThrow(new PromotionValidationException(
                            PromotionValidationException.PromotionErrorCode.ALREADY_PROMOTED,
                            "already promoted",
                            WORKORDER_ID));
            when(workorderService.getWorkorderById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

            ResponseEntity<WorkorderResponse> response = controller.promoteEstimateToWorkorder(ESTIMATE_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getId()).isEqualTo(WORKORDER_ID);
        }

        @Test
        void rethrowsAnAlreadyPromotedWhoseWorkorderCannotBeLoaded() {
            PromotionValidationException unresolvable = new PromotionValidationException(
                    PromotionValidationException.PromotionErrorCode.ALREADY_PROMOTED, "already promoted", WORKORDER_ID);
            when(workorderService.createWorkorder(ESTIMATE_ID, null)).thenThrow(unresolvable);
            when(workorderService.getWorkorderById(WORKORDER_ID)).thenReturn(Optional.empty());

            // #1477: rethrown so the advice answers with ALREADY_PROMOTED and the unresolvable
            // workorder id, instead of a 409 that carries neither.
            assertThatThrownBy(() -> controller.promoteEstimateToWorkorder(ESTIMATE_ID, null))
                    .isSameAs(unresolvable);
        }

        @Test
        void rethrowsAnyOtherPromotionValidationFailureForTheAdviceToEnvelope() {
            PromotionValidationException expired = new PromotionValidationException(
                    PromotionValidationException.PromotionErrorCode.APPROVAL_EXPIRED, "approval expired");
            when(workorderService.createWorkorder(ESTIMATE_ID, null)).thenThrow(expired);

            assertThatThrownBy(() -> controller.promoteEstimateToWorkorder(ESTIMATE_ID, null))
                    .isSameAs(expired);
        }

        /**
         * #1477: every failing promotion leaves the endpoint as a typed exception the advice
         * envelopes, rather than as a bodiless status built here. The three conditions that used to
         * share an empty 400 are three distinct types now.
         */
        @Test
        void surfacesEachFailureAsItsOwnTypedException() {
            // #1713: the translation `catch (EntityNotFoundException) -> EstimateNotFoundException`
            // is gone. Nothing in this module throws the JPA type any more, so the only way to see
            // one here is a genuine persistence fault (a broken lazy proxy, say) — which must not
            // be relabelled as a client 404. It propagates as itself and the platform catch-all
            // answers a generic, correlated 500 (ADR-0056 §1, the #1694 principle).
            doThrow(new EntityNotFoundException("gone")).when(workorderService).createWorkorder(ESTIMATE_ID, null);
            assertThatThrownBy(() -> controller.promoteEstimateToWorkorder(ESTIMATE_ID, null))
                    .isInstanceOf(EntityNotFoundException.class)
                    .isNotInstanceOf(EstimateNotFoundException.class);

            doThrow(new EstimateNotFoundException(ESTIMATE_ID))
                    .when(workorderService)
                    .createWorkorder(ESTIMATE_ID, null);
            assertThatThrownBy(() -> controller.promoteEstimateToWorkorder(ESTIMATE_ID, null))
                    .isInstanceOf(EstimateNotFoundException.class);

            doThrow(CustomerRequirementsNotMetException.verdictUnavailable(CUSTOMER_ID))
                    .when(workorderService)
                    .createWorkorder(ESTIMATE_ID, null);
            assertThatThrownBy(() -> controller.promoteEstimateToWorkorder(ESTIMATE_ID, null))
                    .isInstanceOf(CustomerRequirementsNotMetException.class)
                    .asInstanceOf(type(CustomerRequirementsNotMetException.class))
                    .returns(true, CustomerRequirementsNotMetException::isRetryable);

            doThrow(CustomerRequirementsNotMetException.requirementsNotMet(CUSTOMER_ID))
                    .when(workorderService)
                    .createWorkorder(ESTIMATE_ID, null);
            assertThatThrownBy(() -> controller.promoteEstimateToWorkorder(ESTIMATE_ID, null))
                    .asInstanceOf(type(CustomerRequirementsNotMetException.class))
                    .returns(false, CustomerRequirementsNotMetException::isRetryable);

            doThrow(new RuntimeException("boom")).when(workorderService).createWorkorder(ESTIMATE_ID, null);
            assertThatThrownBy(() -> controller.promoteEstimateToWorkorder(ESTIMATE_ID, null))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("items, totals and documents")
    class ItemsAndDocuments {

        private static EstimateItemResponse item() {
            return EstimateItemResponse.builder()
                    .id(ITEM_ID)
                    .estimateId(ESTIMATE_ID)
                    .description("Mount and balance")
                    .build();
        }

        @Test
        void deletesAnEstimate() {
            assertThat(controller.deleteEstimate(ESTIMATE_ID).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(estimateService).deleteEstimate(ESTIMATE_ID);
        }

        @Test
        void addsAnItemUnderTheAuthenticatedUser() {
            when(estimateService.addEstimateItem(eq(ESTIMATE_ID), any(), anyString()))
                    .thenReturn(item());

            assertThat(controller.addEstimateItem(ESTIMATE_ID, null).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        void translatesANotFoundResponseStatusExceptionSoTheAdviceCanEnvelopeIt() {
            // #1713: this endpoint documents its 404 as an ApiError, so the not-found branch must
            // not answer a bodiless ResponseEntity. It re-throws as EstimateNotFoundException, which
            // GlobalExceptionHandler maps to a 404 ESTIMATE_NOT_FOUND envelope — the same shape the
            // sibling test below pins for a service-thrown EstimateNotFoundException.
            doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "no estimate"))
                    .when(estimateService)
                    .addEstimateItem(any(), any(), anyString());
            assertThatThrownBy(() -> controller.addEstimateItem(ESTIMATE_ID, null))
                    .isInstanceOf(EstimateNotFoundException.class);

            // The 400 branch is unchanged and still answers directly; it is out of #1713's scope.
            doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad item"))
                    .when(estimateService)
                    .addEstimateItem(any(), any(), anyString());
            assertThat(controller.addEstimateItem(ESTIMATE_ID, null).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void mapsTheOtherAddFailuresOntoNotFoundBadRequestAndConflict() {
            // #1713: propagates for the enveloped 404 (ADR-0017 §3/§4).
            doThrow(new EstimateNotFoundException(ESTIMATE_ID))
                    .when(estimateService)
                    .addEstimateItem(any(), any(), anyString());
            assertThatThrownBy(() -> controller.addEstimateItem(ESTIMATE_ID, null))
                    .isInstanceOf(EstimateNotFoundException.class);

            doThrow(new WorkorderRequestValidationException("bad quantity"))
                    .when(estimateService)
                    .addEstimateItem(any(), any(), anyString());
            assertThatThrownBy(() -> controller.addEstimateItem(ESTIMATE_ID, null))
                    .isInstanceOf(WorkorderRequestValidationException.class);

            doThrow(new IllegalStateException("estimate is locked"))
                    .when(estimateService)
                    .addEstimateItem(any(), any(), anyString());
            assertThat(controller.addEstimateItem(ESTIMATE_ID, null).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        void updatesAnItem() {
            when(estimateService.updateEstimateItem(eq(ESTIMATE_ID), eq(ITEM_ID), any()))
                    .thenReturn(item());

            assertThat(controller.updateEstimateItem(ESTIMATE_ID, ITEM_ID, null).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        void mapsUpdateFailuresOntoNotFoundBadRequestAndConflict() {
            // #1713: a missing estimate and a missing line are distinct enveloped 404s now
            // (ESTIMATE_NOT_FOUND / ESTIMATE_ITEM_NOT_FOUND), not one shared bodiless status.
            doThrow(new EstimateItemNotFoundException(ITEM_ID, ESTIMATE_ID))
                    .when(estimateService)
                    .updateEstimateItem(any(), any(), any());
            assertThatThrownBy(() -> controller.updateEstimateItem(ESTIMATE_ID, ITEM_ID, null))
                    .isInstanceOf(EstimateItemNotFoundException.class);

            doThrow(new WorkorderRequestValidationException("bad quantity"))
                    .when(estimateService)
                    .updateEstimateItem(any(), any(), any());
            assertThatThrownBy(() -> controller.updateEstimateItem(ESTIMATE_ID, ITEM_ID, null))
                    .isInstanceOf(WorkorderRequestValidationException.class);

            doThrow(new IllegalStateException("estimate is locked"))
                    .when(estimateService)
                    .updateEstimateItem(any(), any(), any());
            assertThat(controller.updateEstimateItem(ESTIMATE_ID, ITEM_ID, null).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        void deletesAnItem() {
            assertThat(controller.deleteEstimateItem(ESTIMATE_ID, ITEM_ID).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            verify(estimateService).deleteEstimateItem(ESTIMATE_ID, ITEM_ID);
        }

        @Test
        void mapsItemDeleteFailuresOntoNotFoundAndConflict() {
            doThrow(new EstimateItemNotFoundException(ITEM_ID, ESTIMATE_ID))
                    .when(estimateService)
                    .deleteEstimateItem(ESTIMATE_ID, ITEM_ID);
            assertThatThrownBy(() -> controller.deleteEstimateItem(ESTIMATE_ID, ITEM_ID))
                    .isInstanceOf(EstimateItemNotFoundException.class);

            doThrow(new IllegalStateException("estimate is locked"))
                    .when(estimateService)
                    .deleteEstimateItem(ESTIMATE_ID, ITEM_ID);
            assertThat(controller.deleteEstimateItem(ESTIMATE_ID, ITEM_ID).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        void returnsTheRecalculatedTotalsAlongsideTheEstimate() {
            when(estimateService.calculateEstimateTaxesAndTotals(eq(ESTIMATE_ID), anyString()))
                    .thenReturn(estimate());

            ResponseEntity<Map<String, Object>> response = controller.calculateEstimateTotals(ESTIMATE_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .containsKey("estimate")
                    .containsEntry("subtotal", new BigDecimal("100.00"))
                    .containsEntry("taxAmount", new BigDecimal("8.00"))
                    .containsEntry("total", new BigDecimal("108.00"));
        }

        @Test
        void mapsCalculationFailuresOntoNotFoundAndConflict() {
            doThrow(new EstimateNotFoundException(ESTIMATE_ID))
                    .when(estimateService)
                    .calculateEstimateTaxesAndTotals(any(), anyString());
            assertThatThrownBy(() -> controller.calculateEstimateTotals(ESTIMATE_ID))
                    .isInstanceOf(EstimateNotFoundException.class);

            doThrow(new IllegalStateException("no items"))
                    .when(estimateService)
                    .calculateEstimateTaxesAndTotals(any(), anyString());
            assertThat(controller.calculateEstimateTotals(ESTIMATE_ID).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        void servesTheCustomerFacingSummary() {
            when(estimateService.getEstimateSummary(ESTIMATE_ID))
                    .thenReturn(EstimateSummaryResponse.builder().build());

            assertThat(controller.getEstimateSummary(ESTIMATE_ID).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        void reportsNotFoundWhenTheSummaryHasNoEstimate() {
            when(estimateService.getEstimateSummary(ESTIMATE_ID)).thenThrow(new EstimateNotFoundException(ESTIMATE_ID));

            assertThatThrownBy(() -> controller.getEstimateSummary(ESTIMATE_ID))
                    .isInstanceOf(EstimateNotFoundException.class);
        }

        @Test
        void servesThePdfAsAnAttachment() {
            when(estimateService.generateEstimatePdf(ESTIMATE_ID)).thenReturn(new byte[] {1, 2, 3});

            ResponseEntity<byte[]> response = controller.generateEstimatePdf(ESTIMATE_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getFirst("Content-Disposition"))
                    .contains("estimate-" + ESTIMATE_ID + ".pdf");
            assertThat(response.getBody()).hasSize(3);
        }

        @Test
        void reportsNotFoundForAPdfOfAnUnknownEstimateAndBadGatewayOnRenderFailure() {
            doThrow(new EstimateNotFoundException(ESTIMATE_ID))
                    .when(estimateService)
                    .generateEstimatePdf(ESTIMATE_ID);
            assertThat(controller.generateEstimatePdf(ESTIMATE_ID).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);

            doThrow(new RuntimeException("renderer down")).when(estimateService).generateEstimatePdf(ESTIMATE_ID);
            assertThat(controller.generateEstimatePdf(ESTIMATE_ID).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_GATEWAY);
        }

        @Test
        void createsASnapshotUnderTheAuthenticatedUser() {
            when(estimateService.createEstimateSnapshot(eq(ESTIMATE_ID), anyString(), eq("before revision")))
                    .thenReturn(EstimateSnapshotResponse.builder().build());

            assertThat(controller
                            .createEstimateSnapshot(ESTIMATE_ID, "before revision")
                            .getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        void mapsSnapshotFailuresOntoNotFoundAndConflict() {
            doThrow(new EstimateNotFoundException(ESTIMATE_ID))
                    .when(estimateService)
                    .createEstimateSnapshot(any(), anyString(), any());
            assertThatThrownBy(() -> controller.createEstimateSnapshot(ESTIMATE_ID, null))
                    .isInstanceOf(EstimateNotFoundException.class);

            doThrow(new IllegalStateException("snapshot exists"))
                    .when(estimateService)
                    .createEstimateSnapshot(any(), anyString(), any());
            assertThat(controller.createEstimateSnapshot(ESTIMATE_ID, null).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);
        }
    }
}
