package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.PaymentApplicationListRow;
import com.positivity.accounting.internal.exception.InvalidDateRangeException;
import com.positivity.accounting.internal.service.PaymentApplicationQueryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Controller tests for {@link PaymentApplicationController} (Wave 2 E10, issue #1598): happy
 * path, includeReversed pass-through, input validation (400), and authorization (403).
 */
@DisplayName("PaymentApplicationController Tests")
class PaymentApplicationControllerTest extends BaseIntegrationTest {

    private static final String LIST_PATH = "/v1/accounting/payment-applications";

    @MockitoBean
    private PaymentApplicationQueryService paymentApplicationQueryService;

    private PaymentApplicationListRow stubRow() {
        return PaymentApplicationListRow.builder()
                .applicationId(UUID.fromString("018f0000-0000-7000-8000-0000000000aa"))
                .paymentId(UUID.fromString("018f0000-0000-7000-8000-0000000000bb"))
                .invoiceId(UUID.fromString("018f0000-0000-7000-8000-0000000000cc"))
                .appliedAt(Instant.parse("2026-06-15T14:32:00Z"))
                .amount(new BigDecimal("450.00"))
                .reversed(false)
                .build();
    }

    @Test
    @DisplayName("GET / returns 200 with a page of applications for an authorized request")
    void listReturns200() throws Exception {
        when(paymentApplicationQueryService.listByAppliedDateWindow(
                        eq(LocalDate.of(2026, 6, 1)), eq(LocalDate.of(2026, 6, 30)), eq(false), any()))
                .thenReturn(new PageImpl<>(List.of(stubRow())));

        mockMvc.perform(withAuth(
                        get(LIST_PATH).param("appliedFrom", "2026-06-01").param("appliedTo", "2026-06-30")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].amount").value(450.00))
                .andExpect(jsonPath("$.content[0].reversed").value(false));
    }

    @Test
    @DisplayName("GET / defaults includeReversed to false when omitted")
    void listDefaultsIncludeReversedToFalse() throws Exception {
        when(paymentApplicationQueryService.listByAppliedDateWindow(any(), any(), eq(false), any()))
                .thenReturn(new PageImpl<>(List.of(stubRow())));

        mockMvc.perform(withAuth(
                        get(LIST_PATH).param("appliedFrom", "2026-06-01").param("appliedTo", "2026-06-30")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET / passes includeReversed=true through to the service")
    void listPassesIncludeReversed() throws Exception {
        when(paymentApplicationQueryService.listByAppliedDateWindow(any(), any(), eq(true), any()))
                .thenReturn(new PageImpl<>(List.of(stubRow())));

        mockMvc.perform(withAuth(get(LIST_PATH)
                        .param("appliedFrom", "2026-06-01")
                        .param("appliedTo", "2026-06-30")
                        .param("includeReversed", "true")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET / rejects appliedTo before appliedFrom with 400 VALIDATION_ERROR")
    void listRejectsInvalidRange() throws Exception {
        when(paymentApplicationQueryService.listByAppliedDateWindow(
                        any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenThrow(new InvalidDateRangeException("appliedTo cannot be before appliedFrom"));

        mockMvc.perform(withAuth(
                        get(LIST_PATH).param("appliedFrom", "2026-06-30").param("appliedTo", "2026-06-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET / rejects a window wider than 366 days with 400 VALIDATION_ERROR")
    void listRejectsWindowTooWide() throws Exception {
        when(paymentApplicationQueryService.listByAppliedDateWindow(
                        any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenThrow(new InvalidDateRangeException("Applied-date window cannot exceed 366 days"));

        mockMvc.perform(withAuth(
                        get(LIST_PATH).param("appliedFrom", "2025-01-01").param("appliedTo", "2026-06-30")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET / returns 403 when the caller lacks accounting:analytics:view")
    void listReturns403WithoutPermission() throws Exception {
        mockMvc.perform(withAuth(
                        get(LIST_PATH).param("appliedFrom", "2026-06-01").param("appliedTo", "2026-06-30"),
                        "accounting:je:view"))
                .andExpect(status().isForbidden());
    }
}
