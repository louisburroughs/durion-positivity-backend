package com.positivity.accounting.internal.controller;

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
import com.positivity.accounting.internal.dto.BankReconciliationImportRequest;
import com.positivity.accounting.internal.dto.BankReconciliationResponse;
import com.positivity.accounting.internal.dto.ReconciliationAdjustmentRequest;
import com.positivity.accounting.internal.dto.ReconciliationMatchRequest;
import com.positivity.accounting.internal.enums.BankAdjustmentType;
import com.positivity.accounting.internal.enums.ReconciliationStatus;
import com.positivity.accounting.internal.exception.AccountNotReconcilableException;
import com.positivity.accounting.internal.exception.AdjustmentSignInvalidException;
import com.positivity.accounting.internal.exception.MatchAmountMismatchException;
import com.positivity.accounting.internal.exception.ReconciliationAlreadyFinalizedException;
import com.positivity.accounting.internal.exception.ReconciliationNotBalancedException;
import com.positivity.accounting.internal.exception.ReconciliationNotFoundException;
import com.positivity.accounting.service.BankReconciliationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
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
                .status(ReconciliationStatus.IN_PROGRESS)
                .statementLines(List.of())
                .adjustments(List.of())
                .build();
    }

    private static BankReconciliationImportRequest importRequest() {
        return BankReconciliationImportRequest.builder()
                .glAccountId(ACCOUNT_ID)
                .periodStartDate(LocalDate.of(2026, 6, 1))
                .periodEndDate(LocalDate.of(2026, 6, 30))
                .statementDate(LocalDate.of(2026, 6, 30))
                .statementEndingBalance(new BigDecimal("2000.0000"))
                .currency("USD")
                .csv("2026-06-15,ACH DEPOSIT,1500.00,REF-1")
                .build();
    }

    @Nested
    @DisplayName("POST /v1/accounting/reconciliations/import")
    class Import {

        @Test
        @DisplayName("Should import a reconciliation")
        void shouldImport() throws Exception {
            when(bankReconciliationService.importStatement(any())).thenReturn(response());

            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/import"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(importRequest())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reconciliationId").value(RECON_ID.toString()));
        }

        @Test
        @DisplayName("Should return 422 ACCOUNT_NOT_RECONCILABLE for a non-reconcilable account")
        void shouldReturn422NotReconcilable() throws Exception {
            when(bankReconciliationService.importStatement(any()))
                    .thenThrow(new AccountNotReconcilableException("not reconcilable"));

            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/import"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(importRequest())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_RECONCILABLE"));
        }

        @Test
        @DisplayName("Should return 400 when csv is missing")
        void shouldReturn400WhenCsvMissing() throws Exception {
            BankReconciliationImportRequest req = importRequest();
            req.setCsv(null);

            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/import"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());

            verify(bankReconciliationService, never()).importStatement(any());
        }

        @Test
        @DisplayName("Should reject import without accounting:reconciliation:adjust authority")
        void shouldRejectWithoutPermission() throws Exception {
            mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/import"), "accounting:reconciliation:view")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(importRequest())))
                    .andExpect(status().isForbidden());
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
            com.positivity.accounting.internal.dto.BankReconciliationListResponse listResponse =
                    new com.positivity.accounting.internal.dto.BankReconciliationListResponse(
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
        @DisplayName("Should finalize a balanced reconciliation")
        void shouldFinalize() throws Exception {
            BankReconciliationResponse finalized = response();
            finalized.setStatus(ReconciliationStatus.FINALIZED);
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
