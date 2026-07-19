package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.SettlementManualMatchRequest;
import com.positivity.accounting.internal.dto.SettlementWriteOffRequest;
import com.positivity.accounting.internal.entity.ProcessorSettlementLine;
import com.positivity.accounting.internal.enums.SettlementLineMatchStatus;
import com.positivity.accounting.internal.enums.SettlementLineType;
import com.positivity.accounting.service.SettlementReconciliationService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tests for SettlementReconciliationController (Story F1c, Issue #963,
 * decision D-14): list settlement lines, manual match, and small write-off,
 * including permission enforcement and error status mapping.
 */
@DisplayName("SettlementReconciliationController Tests")
class SettlementReconciliationControllerTest extends BaseIntegrationTest {

    private static final UUID LINE_ID = UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678901234");
    private static final UUID RECEIVABLE_PAYMENT_ID = UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678905678");
    private static final String SETTLEMENT_ID = "stl_1Mx3k2eZvKYlo2C0";

    @MockitoBean
    private SettlementReconciliationService settlementReconciliationService;

    private static ProcessorSettlementLine unmatchedLine() {
        return ProcessorSettlementLine.builder()
                .lineId(LINE_ID)
                .settlementId(SETTLEMENT_ID)
                .lineType(SettlementLineType.CHARGE)
                .grossAmount(new BigDecimal("1000.0000"))
                .feeAmount(new BigDecimal("30.0000"))
                .netAmount(new BigDecimal("970.0000"))
                .matchStatus(SettlementLineMatchStatus.UNMATCHED)
                .build();
    }

    @Nested
    @DisplayName("GET /v1/accounting/settlements/{settlementId}/lines")
    class ListLines {

        @Test
        @DisplayName("Should list settlement lines")
        void shouldListLines() throws Exception {
            when(settlementReconciliationService.listLines(eq(SETTLEMENT_ID), anyBoolean()))
                    .thenReturn(List.of(unmatchedLine()));

            mockMvc.perform(withAuth(get("/v1/accounting/settlements/{settlementId}/lines", SETTLEMENT_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].lineId").value(LINE_ID.toString()))
                    .andExpect(jsonPath("$[0].settlementId").value(SETTLEMENT_ID))
                    .andExpect(jsonPath("$[0].matchStatus").value("UNMATCHED"));
        }

        @Test
        @DisplayName("Should pass unmatchedOnly through to the service")
        void shouldFilterUnmatchedOnly() throws Exception {
            when(settlementReconciliationService.listLines(SETTLEMENT_ID, true)).thenReturn(List.of());

            mockMvc.perform(withAuth(
                            get("/v1/accounting/settlements/{settlementId}/lines?unmatchedOnly=true", SETTLEMENT_ID)))
                    .andExpect(status().isOk());

            verify(settlementReconciliationService).listLines(SETTLEMENT_ID, true);
        }

        @Test
        @DisplayName("Should reject listing without accounting:reconciliation:view authority")
        void shouldRejectWithoutPermission() throws Exception {
            mockMvc.perform(withAuth(
                            get("/v1/accounting/settlements/{settlementId}/lines", SETTLEMENT_ID),
                            "accounting:je:view"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("POST /v1/accounting/settlements/lines/{lineId}/match")
    class MatchLine {

        @Test
        @DisplayName("Should match a line to a receivable payment")
        void shouldMatchLine() throws Exception {
            when(settlementReconciliationService.manualMatchToReceivable(LINE_ID, RECEIVABLE_PAYMENT_ID))
                    .thenReturn(unmatchedLine());
            SettlementManualMatchRequest req = SettlementManualMatchRequest.builder()
                    .receivablePaymentId(RECEIVABLE_PAYMENT_ID)
                    .build();

            mockMvc.perform(withAuth(post("/v1/accounting/settlements/lines/{lineId}/match", LINE_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lineId").value(LINE_ID.toString()));

            verify(settlementReconciliationService).manualMatchToReceivable(LINE_ID, RECEIVABLE_PAYMENT_ID);
        }

        @Test
        @DisplayName("Should return 400 when receivablePaymentId is missing")
        void shouldReturn400WhenPaymentIdMissing() throws Exception {
            mockMvc.perform(withAuth(post("/v1/accounting/settlements/lines/{lineId}/match", LINE_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());

            verify(settlementReconciliationService, never()).manualMatchToReceivable(any(), any());
        }

        @Test
        @DisplayName("Should return 409 when the line is not UNMATCHED")
        void shouldReturn409WhenNotUnmatched() throws Exception {
            when(settlementReconciliationService.manualMatchToReceivable(LINE_ID, RECEIVABLE_PAYMENT_ID))
                    .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "not UNMATCHED"));
            SettlementManualMatchRequest req = SettlementManualMatchRequest.builder()
                    .receivablePaymentId(RECEIVABLE_PAYMENT_ID)
                    .build();

            mockMvc.perform(withAuth(post("/v1/accounting/settlements/lines/{lineId}/match", LINE_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("Should reject matching without accounting:reconciliation:adjust authority")
        void shouldRejectWithoutPermission() throws Exception {
            SettlementManualMatchRequest req = SettlementManualMatchRequest.builder()
                    .receivablePaymentId(RECEIVABLE_PAYMENT_ID)
                    .build();

            mockMvc.perform(withAuth(
                                    post("/v1/accounting/settlements/lines/{lineId}/match", LINE_ID),
                                    "accounting:reconciliation:view")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("POST /v1/accounting/settlements/lines/{lineId}/write-off")
    class WriteOffLine {

        @Test
        @DisplayName("Should write off a small unmatched line")
        void shouldWriteOff() throws Exception {
            when(settlementReconciliationService.writeOffLine(eq(LINE_ID), anyString()))
                    .thenReturn(unmatchedLine());
            SettlementWriteOffRequest req = SettlementWriteOffRequest.builder()
                    .reason("Unidentifiable micro-deposit")
                    .build();

            mockMvc.perform(withAuth(post("/v1/accounting/settlements/lines/{lineId}/write-off", LINE_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk());

            verify(settlementReconciliationService).writeOffLine(LINE_ID, "Unidentifiable micro-deposit");
        }

        @Test
        @DisplayName("Should return 400 when reason is blank")
        void shouldReturn400WhenReasonBlank() throws Exception {
            SettlementWriteOffRequest req =
                    SettlementWriteOffRequest.builder().reason("  ").build();

            mockMvc.perform(withAuth(post("/v1/accounting/settlements/lines/{lineId}/write-off", LINE_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());

            verify(settlementReconciliationService, never()).writeOffLine(any(), anyString());
        }

        @Test
        @DisplayName("Should return 422 when the gross exceeds the write-off threshold")
        void shouldReturn422WhenAboveThreshold() throws Exception {
            when(settlementReconciliationService.writeOffLine(eq(LINE_ID), anyString()))
                    .thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "exceeds threshold"));
            SettlementWriteOffRequest req =
                    SettlementWriteOffRequest.builder().reason("too big").build();

            mockMvc.perform(withAuth(post("/v1/accounting/settlements/lines/{lineId}/write-off", LINE_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isUnprocessableContent());
        }

        @Test
        @DisplayName("Should reject write-off without accounting:reconciliation:adjust authority")
        void shouldRejectWithoutPermission() throws Exception {
            SettlementWriteOffRequest req = SettlementWriteOffRequest.builder()
                    .reason("Unidentifiable micro-deposit")
                    .build();

            mockMvc.perform(withAuth(
                                    post("/v1/accounting/settlements/lines/{lineId}/write-off", LINE_ID),
                                    "accounting:reconciliation:view")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isForbidden());
        }
    }
}
