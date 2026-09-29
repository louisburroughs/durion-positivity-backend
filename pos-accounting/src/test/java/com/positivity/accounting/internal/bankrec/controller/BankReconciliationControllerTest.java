package com.positivity.accounting.internal.bankrec.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.bankrec.dto.AutoMatchResponse;
import com.positivity.accounting.internal.bankrec.dto.BankReconciliationAdjustmentResponse;
import com.positivity.accounting.internal.bankrec.dto.BankReconciliationResponse;
import com.positivity.accounting.internal.bankrec.dto.OutstandingItemResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationApiStatus;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationCandidatesResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationMatchResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationReviewResponse;
import com.positivity.accounting.internal.bankrec.enums.BankAdjustmentType;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.service.BankReconciliationService;
import com.positivity.accounting.internal.bankrec.service.ReconciliationAdjustmentService;
import com.positivity.accounting.internal.bankrec.service.ReconciliationListFilter;
import com.positivity.accounting.internal.bankrec.service.ReconciliationMatchingService;
import com.positivity.accounting.internal.bankrec.service.ReconciliationOutstandingItemService;
import com.positivity.accounting.internal.bankrec.service.ReconciliationReviewService;
import com.positivity.accounting.internal.exception.ReconciliationNotBalancedException;
import com.positivity.accounting.internal.exception.ReconciliationNotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Tests for {@link BankReconciliationController} (Story F2, issue #965): permission
 * enforcement (view vs adjust), request validation, and ADR-0017 error-code mapping.
 */
@DisplayName("BankReconciliationController Tests")
class BankReconciliationControllerTest extends BaseIntegrationTest {

    private static final UUID RECON_ID = UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678900001");
    private static final UUID ACCOUNT_ID = UUID.fromString("5eed0acc-0000-4000-8000-000000001000");

    @MockitoBean
    private BankReconciliationService bankReconciliationService;

    @MockitoBean
    private ReconciliationMatchingService matchingService;

    @MockitoBean
    private ReconciliationOutstandingItemService itemService;

    @MockitoBean
    private ReconciliationAdjustmentService adjustmentService;

    @MockitoBean
    private ReconciliationReviewService reviewService;

    private static BankReconciliationResponse response() {
        return BankReconciliationResponse.builder()
                .reconciliationId(RECON_ID)
                .glAccountId(ACCOUNT_ID)
                .accountCode("1000")
                .currency("USD")
                .status(ReconciliationApiStatus.IN_PROGRESS)
                .build();
    }

    @Nested
    @DisplayName("POST /v1/accounting/reconciliations/import (retired, D14)")
    class RetiredImport {

        @Test
        @DisplayName("Should no longer serve the F2 CSV import")
        void theRetiredRouteIsNotServed() throws Exception {
            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/import"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"csv\":\"2026-06-15,ACH DEPOSIT,1500.00\"}"))
                    .andExpect(status().is4xxClientError());
        }
    }

    @Nested
    @DisplayName("GET /v1/accounting/reconciliations/{id}")
    class Get {

        @Test
        @DisplayName("Should get a reconciliation")
        void shouldGet() throws Exception {
            when(bankReconciliationService.get(RECON_ID)).thenReturn(response());

            mockMvc.perform(withAuth(get("/v1/accounting/reconciliations/{id}", RECON_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reconciliationId").value(RECON_ID.toString()));
        }

        @Test
        @DisplayName("Should return 404 RECONCILIATION_NOT_FOUND for an unknown id")
        void shouldReturn404() throws Exception {
            when(bankReconciliationService.get(RECON_ID)).thenThrow(new ReconciliationNotFoundException("not found"));

            mockMvc.perform(withAuth(get("/v1/accounting/reconciliations/{id}", RECON_ID)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RECONCILIATION_NOT_FOUND"));
        }

        @Test
        @DisplayName("Should reject get without accounting:reconciliation:view authority")
        void shouldRejectWithoutPermission() throws Exception {
            mockMvc.perform(withAuth(get("/v1/accounting/reconciliations/{id}", RECON_ID), "accounting:je:view"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("GET /v1/accounting/reconciliations")
    class ListReconciliations {

        @Test
        @DisplayName("Should list reconciliations and pass filters through")
        void shouldList() throws Exception {
            com.positivity.accounting.internal.bankrec.dto.BankReconciliationListResponse listResponse =
                    new com.positivity.accounting.internal.bankrec.dto.BankReconciliationListResponse(
                            List.of(response()), 1L, 0, 20, 1);
            when(bankReconciliationService.list(any(), any())).thenReturn(listResponse);

            mockMvc.perform(withAuth(get("/v1/accounting/reconciliations")
                            .param("glAccountId", ACCOUNT_ID.toString())
                            .param("status", "IN_PROGRESS")
                            .param("periodCode", "2026-09")
                            .param("from", "2026-01-01")
                            .param("to", "2026-12-31")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.reconciliations[0].reconciliationId").value(RECON_ID.toString()));

            verify(bankReconciliationService)
                    .list(
                            eq(new ReconciliationListFilter(
                                    ACCOUNT_ID,
                                    ReconciliationStatus.IN_PROGRESS,
                                    "2026-09",
                                    LocalDate.of(2026, 1, 1),
                                    LocalDate.of(2026, 12, 31))),
                            any());
        }

        @Test
        @DisplayName("Should refuse a status the F2 API does not serve yet (story S1 keeps the contract, #2300)")
        void shouldRejectStatusNotServedYet() throws Exception {
            // SUBMITTED exists in the stored value set from story S1 but no transition reaches it and the
            // API does not serve it until story S5, so the published enum (IN_PROGRESS, FINALIZED,
            // CANCELLED) is unchanged.
            mockMvc.perform(withAuth(get("/v1/accounting/reconciliations").param("status", "SUBMITTED")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            verify(bankReconciliationService, never()).list(any(), any());
        }

        @Test
        @DisplayName("Should reject listing without accounting:reconciliation:view authority")
        void shouldRejectWithoutPermission() throws Exception {
            mockMvc.perform(withAuth(get("/v1/accounting/reconciliations"), "accounting:je:view"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("GET /v1/accounting/reconciliations/adjustment-types")
    class AdjustmentTypes {

        @Test
        @DisplayName("Should list the D-6 adjustment types")
        void shouldListTypes() throws Exception {
            mockMvc.perform(withAuth(get("/v1/accounting/reconciliations/adjustment-types")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(BankAdjustmentType.values().length))
                    .andExpect(jsonPath("$[0].code").value(BankAdjustmentType.values()[0].name()));
        }

        @Test
        @DisplayName("Should reject listing types without accounting:reconciliation:view authority")
        void shouldRejectWithoutPermission() throws Exception {
            mockMvc.perform(withAuth(get("/v1/accounting/reconciliations/adjustment-types"), "accounting:je:view"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Should not serve FLOAT_ADJUSTMENT (removed in v1)")
        void shouldNotServeFloatAdjustment() throws Exception {
            mockMvc.perform(withAuth(get("/v1/accounting/reconciliations/adjustment-types")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.code == 'FLOAT_ADJUSTMENT')]").isEmpty());
        }
    }

    @Nested
    @DisplayName("POST /v1/accounting/reconciliations")
    class Create {

        private String body() throws Exception {
            return objectMapper.writeValueAsString(ReconciliationCreateRequest.builder()
                    .glAccountId(ACCOUNT_ID)
                    .requestId(UUID.fromString("019a0000-0000-7000-8000-000000000001"))
                    .statementId(UUID.fromString("019a0000-0000-7000-8000-000000000002"))
                    .build());
        }

        @Test
        @DisplayName("Should answer 201 on a create and 200 on a replay")
        void shouldCreateAndReplay() throws Exception {
            when(bankReconciliationService.create(any())).thenReturn(response());
            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.reconciliationId").value(RECON_ID.toString()))
                    .andExpect(jsonPath("$.replayed").value(false));

            BankReconciliationResponse replayed = response();
            replayed.setReplayed(true);
            when(bankReconciliationService.create(any())).thenReturn(replayed);
            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.replayed").value(true));
        }

        @Test
        @DisplayName("Should answer 409 RECONCILIATION_WINDOW_ALREADY_RECONCILED naming the reconciliation")
        void shouldReturn409AlreadyReconciled() throws Exception {
            when(bankReconciliationService.create(any()))
                    .thenThrow(BankRecException.field(
                            BankRecErrorCode.RECONCILIATION_WINDOW_ALREADY_RECONCILED,
                            "already",
                            "reconciliationId",
                            RECON_ID.toString()));
            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("RECONCILIATION_WINDOW_ALREADY_RECONCILED"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("reconciliationId"));
        }

        @Test
        @DisplayName("Should answer 400 without a requestId and 403 without adjust")
        void shouldValidateAndAuthorize() throws Exception {
            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"glAccountId\":\"" + ACCOUNT_ID + "\"}"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations"), "accounting:reconciliation:view")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body()))
                    .andExpect(status().isForbidden());
            verify(bankReconciliationService, never()).create(any());
        }
    }

    @Nested
    @DisplayName("matching (#2303)")
    class Matching {

        private final UUID matchId = UUID.fromString("019a0000-0000-7000-8000-0000000000aa");

        @Test
        @DisplayName("the replaced F2 routes /match and /unmatch are gone (404)")
        void replacedRoutesAreGone() throws Exception {
            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/{id}/match", RECON_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/{id}/unmatch", RECON_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("POST /matches answers 201, maps MATCH_REQUIRES_REVIEW with the reasons, and needs adjust")
        void createMatch() throws Exception {
            String body = "{\"bankTransactionIds\":[\"" + UUID.randomUUID() + "\"],\"glLineIds\":[\""
                    + UUID.randomUUID() + "\"],\"requestId\":\"" + UUID.randomUUID() + "\"}";
            when(matchingService.createMatch(eq(RECON_ID), any())).thenReturn(new ReconciliationMatchResponse());
            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/{id}/matches", RECON_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());

            when(matchingService.createMatch(eq(RECON_ID), any()))
                    .thenThrow(BankRecException.field(
                            BankRecErrorCode.MATCH_REQUIRES_REVIEW,
                            "needs review",
                            "justification",
                            "CARDINALITY_NOT_ONE_TO_ONE"));
            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/{id}/matches", RECON_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("MATCH_REQUIRES_REVIEW"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("justification"))
                    .andExpect(jsonPath("$.fieldErrors[0].message").value("CARDINALITY_NOT_ONE_TO_ONE"));

            mockMvc.perform(withAuth(
                                    post("/v1/accounting/reconciliations/{id}/matches", RECON_ID),
                                    "accounting:reconciliation:view")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an empty side is 400 before the service is called")
        void emptySideIs400() throws Exception {
            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/{id}/matches", RECON_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"bankTransactionIds\":[],\"glLineIds\":[],\"requestId\":\"" + UUID.randomUUID()
                                    + "\"}"))
                    .andExpect(status().isBadRequest());
            verify(matchingService, never()).createMatch(any(), any());
        }

        @Test
        @DisplayName("accept, reject and unmatch map MATCH_STATE_INVALID to 409 and need adjust")
        void decisions() throws Exception {
            when(matchingService.accept(eq(RECON_ID), eq(matchId), any()))
                    .thenThrow(new BankRecException(BankRecErrorCode.MATCH_STATE_INVALID, "not proposed"));
            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/{id}/matches/{m}/accept", RECON_ID, matchId)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MATCH_STATE_INVALID"));
            when(matchingService.reject(eq(RECON_ID), eq(matchId), any()))
                    .thenReturn(new ReconciliationMatchResponse());
            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/{id}/matches/{m}/reject", RECON_ID, matchId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());
            when(matchingService.unmatch(eq(RECON_ID), eq(matchId), any()))
                    .thenReturn(new ReconciliationMatchResponse());
            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/{id}/matches/{m}/unmatch", RECON_ID, matchId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Paired the wrong deposit\"}"))
                    .andExpect(status().isOk());
            for (String action : List.of("accept", "reject", "unmatch")) {
                mockMvc.perform(withAuth(
                                        post(
                                                "/v1/accounting/reconciliations/{id}/matches/{m}/" + action,
                                                RECON_ID,
                                                matchId),
                                        "accounting:reconciliation:view")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"Paired the wrong deposit\"}"))
                        .andExpect(status().isForbidden());
            }
        }

        @Test
        @DisplayName("candidates need view; auto-match needs adjust")
        void candidatesAndAutoMatch() throws Exception {
            when(matchingService.candidates(eq(RECON_ID), any(), any(), any()))
                    .thenReturn(new ReconciliationCandidatesResponse());
            mockMvc.perform(withAuth(
                            get("/v1/accounting/reconciliations/{id}/candidates", RECON_ID)
                                    .param(
                                            "bankTransactionId",
                                            UUID.randomUUID().toString()),
                            "accounting:reconciliation:view"))
                    .andExpect(status().isOk());
            when(matchingService.autoMatch(RECON_ID)).thenReturn(new AutoMatchResponse(1, 0));
            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/{id}/auto-match", RECON_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.proposedCount").value(1));
            mockMvc.perform(withAuth(
                            post("/v1/accounting/reconciliations/{id}/auto-match", RECON_ID),
                            "accounting:reconciliation:view"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withAuth(
                            get("/v1/accounting/reconciliations/{id}/candidates", RECON_ID), "accounting:je:view"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("outstanding items and adjustments (#2303)")
    class ItemsAndAdjustments {

        private static final String ADJUST_ONLY = "accounting:reconciliation:view,accounting:reconciliation:adjust";

        @Test
        @DisplayName("register answers 201; OUTSTANDING_ITEM_NOT_ELIGIBLE is 422")
        void register() throws Exception {
            String body = "{\"glLineId\":\"" + UUID.randomUUID() + "\",\"itemKind\":\"DEPOSIT_IN_TRANSIT\"}";
            when(itemService.register(eq(RECON_ID), any())).thenReturn(new OutstandingItemResponse());
            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/{id}/outstanding-items", RECON_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
            when(itemService.register(eq(RECON_ID), any()))
                    .thenThrow(new BankRecException(BankRecErrorCode.OUTSTANDING_ITEM_NOT_ELIGIBLE, "matched"));
            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/{id}/outstanding-items", RECON_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("OUTSTANDING_ITEM_NOT_ELIGIBLE"));
        }

        @Test
        @DisplayName("clear-in-gap and reverse need accounting:reconciliation:approve")
        void approveOnly() throws Exception {
            UUID id = UUID.randomUUID();
            mockMvc.perform(withAuth(
                                    post(
                                            "/v1/accounting/reconciliations/{id}/outstanding-items/{i}/clear-in-gap",
                                            RECON_ID,
                                            id),
                                    ADJUST_ONLY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"justification\":\"Cleared while we changed banks\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withAuth(
                                    post("/v1/accounting/reconciliations/{id}/adjustments/{a}/reverse", RECON_ID, id),
                                    ADJUST_ONLY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Bank refunded the fee\"}"))
                    .andExpect(status().isForbidden());
            verify(itemService, never()).clearInGap(any(), any(), any());
            verify(adjustmentService, never()).reverse(any(), any(), any());

            when(adjustmentService.reverse(eq(RECON_ID), eq(id), any()))
                    .thenThrow(new BankRecException(BankRecErrorCode.ADJUSTMENT_ALREADY_REVERSED, "twice"));
            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/{id}/adjustments/{a}/reverse", RECON_ID, id))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Bank refunded the fee\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ADJUSTMENT_ALREADY_REVERSED"));
        }

        @Test
        @DisplayName("adjustments answer 201, 200 on replay, 403 APPROVAL_REQUIRED, and need a requestId")
        void adjustments() throws Exception {
            String body = "{\"type\":\"BANK_FEE\",\"amount\":-15.00,\"requestId\":\"" + UUID.randomUUID() + "\"}";
            when(adjustmentService.addAdjustment(eq(RECON_ID), any()))
                    .thenReturn(new BankReconciliationAdjustmentResponse());
            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/{id}/adjustments", RECON_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
            BankReconciliationAdjustmentResponse replayed = new BankReconciliationAdjustmentResponse();
            replayed.setReplayed(true);
            when(adjustmentService.addAdjustment(eq(RECON_ID), any())).thenReturn(replayed);
            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/{id}/adjustments", RECON_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.replayed").value(true));
            when(adjustmentService.addAdjustment(eq(RECON_ID), any()))
                    .thenThrow(new BankRecException(
                            BankRecErrorCode.RECONCILIATION_ADJUSTMENT_APPROVAL_REQUIRED, "approve needed"));
            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/{id}/adjustments", RECON_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("RECONCILIATION_ADJUSTMENT_APPROVAL_REQUIRED"));
            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/{id}/adjustments", RECON_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"BANK_FEE\",\"amount\":-15.00}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("the review needs view and serves the read model")
        void review() throws Exception {
            when(reviewService.review(RECON_ID)).thenReturn(new ReconciliationReviewResponse());
            mockMvc.perform(withAuth(
                            get("/v1/accounting/reconciliations/{id}/review", RECON_ID),
                            "accounting:reconciliation:view"))
                    .andExpect(status().isOk());
            mockMvc.perform(withAuth(get("/v1/accounting/reconciliations/{id}/review", RECON_ID), "accounting:je:view"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("the served adjustment types include TRANSFER")
        void transferIsServed() throws Exception {
            mockMvc.perform(withAuth(get("/v1/accounting/reconciliations/adjustment-types")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.code == 'TRANSFER')]").isNotEmpty());
        }
    }

    @Nested
    @DisplayName("POST /v1/accounting/reconciliations/{id}/finalize")
    class Finalize {

        @Test
        @DisplayName("Should return 422 RECONCILIATION_NOT_BALANCED with the difference")
        void shouldReturn422NotBalanced() throws Exception {
            when(bankReconciliationService.finalizeReconciliation(RECON_ID))
                    .thenThrow(new ReconciliationNotBalancedException("not balanced", new BigDecimal("500.0000")));

            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/{id}/finalize", RECON_ID)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("RECONCILIATION_NOT_BALANCED"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("difference"));
        }

        @Test
        @DisplayName("Should answer 409 OPTIMISTIC_LOCK when the reconciliation changed concurrently (§6.3, #2300)")
        void shouldMapStaleVersionTo409() throws Exception {
            when(bankReconciliationService.finalizeReconciliation(RECON_ID))
                    .thenThrow(new ObjectOptimisticLockingFailureException(Object.class, RECON_ID));

            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/{id}/finalize", RECON_ID)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK"))
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("Should finalize a balanced reconciliation")
        void shouldFinalize() throws Exception {
            BankReconciliationResponse finalized = response();
            finalized.setStatus(ReconciliationApiStatus.FINALIZED);
            when(bankReconciliationService.finalizeReconciliation(RECON_ID)).thenReturn(finalized);

            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/{id}/finalize", RECON_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("FINALIZED"));
        }

        @Test
        @DisplayName("Should reject finalize without accounting:reconciliation:adjust authority")
        void shouldRejectWithoutPermission() throws Exception {
            mockMvc.perform(withAuth(
                            post("/v1/accounting/reconciliations/{id}/finalize", RECON_ID),
                            "accounting:reconciliation:view"))
                    .andExpect(status().isForbidden());
        }
    }
}
