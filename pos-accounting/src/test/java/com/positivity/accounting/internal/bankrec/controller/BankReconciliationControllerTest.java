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
import com.positivity.accounting.internal.bankrec.dto.BankReconciliationResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationAdjustmentRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationApiStatus;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationMatchRequest;
import com.positivity.accounting.internal.bankrec.enums.BankAdjustmentType;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.service.BankReconciliationService;
import com.positivity.accounting.internal.exception.AdjustmentSignInvalidException;
import com.positivity.accounting.internal.exception.MatchAmountMismatchException;
import com.positivity.accounting.internal.exception.ReconciliationAlreadyFinalizedException;
import com.positivity.accounting.internal.exception.ReconciliationNotBalancedException;
import com.positivity.accounting.internal.exception.ReconciliationNotFoundException;
import java.math.BigDecimal;
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

    private static BankReconciliationResponse response() {
        return BankReconciliationResponse.builder()
                .reconciliationId(RECON_ID)
                .glAccountId(ACCOUNT_ID)
                .accountCode("1000")
                .currency("USD")
                .status(ReconciliationApiStatus.IN_PROGRESS)
                .statementLines(List.of())
                .adjustments(List.of())
                .build();
    }

    @Nested
    @DisplayName("POST /v1/accounting/reconciliations/import — retired (D14, #2302)")
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
            when(bankReconciliationService.list(eq(ACCOUNT_ID), eq(ReconciliationStatus.IN_PROGRESS), any()))
                    .thenReturn(listResponse);

            mockMvc.perform(withAuth(get("/v1/accounting/reconciliations")
                            .param("glAccountId", ACCOUNT_ID.toString())
                            .param("status", "IN_PROGRESS")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.reconciliations[0].reconciliationId").value(RECON_ID.toString()));

            verify(bankReconciliationService).list(eq(ACCOUNT_ID), eq(ReconciliationStatus.IN_PROGRESS), any());
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

            verify(bankReconciliationService, never()).list(any(), any(), any());
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
    @DisplayName("POST /v1/accounting/reconciliations/{id}/match")
    class Match {

        @Test
        @DisplayName("Should return 422 MATCH_AMOUNT_MISMATCH when sets do not net")
        void shouldReturn422Mismatch() throws Exception {
            when(bankReconciliationService.match(eq(RECON_ID), any()))
                    .thenThrow(new MatchAmountMismatchException("mismatch"));
            ReconciliationMatchRequest req = ReconciliationMatchRequest.builder()
                    .statementLineIds(List.of(UUID.randomUUID()))
                    .glLineIds(List.of(UUID.randomUUID()))
                    .build();

            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/{id}/match", RECON_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("MATCH_AMOUNT_MISMATCH"));
        }

        @Test
        @DisplayName("Should return 400 when statementLineIds is empty")
        void shouldReturn400WhenEmpty() throws Exception {
            ReconciliationMatchRequest req = ReconciliationMatchRequest.builder()
                    .statementLineIds(List.of())
                    .glLineIds(List.of(UUID.randomUUID()))
                    .build();

            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/{id}/match", RECON_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());

            verify(bankReconciliationService, never()).match(any(), any());
        }

        @Test
        @DisplayName("Should reject match without accounting:reconciliation:adjust authority")
        void shouldRejectWithoutPermission() throws Exception {
            ReconciliationMatchRequest req = ReconciliationMatchRequest.builder()
                    .statementLineIds(List.of(UUID.randomUUID()))
                    .glLineIds(List.of(UUID.randomUUID()))
                    .build();

            mockMvc.perform(withAuth(
                                    post("/v1/accounting/reconciliations/{id}/match", RECON_ID),
                                    "accounting:reconciliation:view")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("POST /v1/accounting/reconciliations/{id}/adjustments")
    class Adjustments {

        @Test
        @DisplayName("Should return 422 RECONCILIATION_ADJUSTMENT_SIGN_INVALID for a wrong-sign adjustment")
        void shouldReturn422SignInvalid() throws Exception {
            when(bankReconciliationService.addAdjustment(eq(RECON_ID), any()))
                    .thenThrow(new AdjustmentSignInvalidException(BankAdjustmentType.BANK_FEE));
            ReconciliationAdjustmentRequest req = ReconciliationAdjustmentRequest.builder()
                    .type(BankAdjustmentType.BANK_FEE)
                    .amount(new BigDecimal("12.5000"))
                    .build();

            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/{id}/adjustments", RECON_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("RECONCILIATION_ADJUSTMENT_SIGN_INVALID"));
        }

        @Test
        @DisplayName("Should return 409 RECONCILIATION_ALREADY_FINALIZED when finalized")
        void shouldReturn409WhenFinalized() throws Exception {
            when(bankReconciliationService.addAdjustment(eq(RECON_ID), any()))
                    .thenThrow(new ReconciliationAlreadyFinalizedException("finalized"));
            ReconciliationAdjustmentRequest req = ReconciliationAdjustmentRequest.builder()
                    .type(BankAdjustmentType.BANK_FEE)
                    .amount(new BigDecimal("-12.5000"))
                    .build();

            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/{id}/adjustments", RECON_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("RECONCILIATION_ALREADY_FINALIZED"));
        }

        @Test
        @DisplayName("Should return 400 when type is missing")
        void shouldReturn400WhenTypeMissing() throws Exception {
            ReconciliationAdjustmentRequest req = ReconciliationAdjustmentRequest.builder()
                    .amount(new BigDecimal("-12.5000"))
                    .build();

            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/{id}/adjustments", RECON_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());
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
