package com.positivity.order.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.order.BaseControllerSliceTest;
import com.positivity.order.internal.dto.CashMovementSummary;
import com.positivity.order.internal.exception.CashMovementIdempotencyConflictException;
import com.positivity.order.internal.exception.CashMovementRefusedException;
import com.positivity.order.internal.exception.CashMovementRefusedException.Refusal;
import com.positivity.order.internal.exception.RegisterSessionRequestValidationException;
import com.positivity.order.internal.exception.StepUpUnavailableException;
import com.positivity.order.internal.service.CashMovementApprovalService;
import com.positivity.order.internal.service.RegisterSessionService;
import com.positivity.order.internal.service.model.CashMovementApprovalCommand;
import com.positivity.order.internal.service.model.CashMovementApprovalResult;
import com.positivity.order.internal.service.model.CashMovementCommand;
import com.positivity.order.internal.service.model.CashMovementOptions;
import com.positivity.order.internal.service.model.CashMovementResult;
import com.positivity.security.common.GatewaySecurityConfig;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * CAP:550 S16 (#2512): the drawer cash-movement, step-up and options endpoints answer every code the
 * story lists, with the status it names.
 */
@DisplayName("Drawer movement endpoints (CAP:550 S16)")
@WebMvcTest(RegisterSessionController.class)
@Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class, BaseControllerSliceTest.SliceConfig.class})
class RegisterSessionCashMovementControllerTest extends BaseControllerSliceTest {

    private static final UUID SESSION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b01");
    private static final UUID REQUEST_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c01");
    private static final String CASH_MOVEMENT = "order:session:cash_movement";

    private static final String PETTY_BODY = """
            {"requestId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c01","reason":"PETTY_EXPENSE","amount":25.00,
             "categoryCode":"SHOP_SUPPLIES","receiptReference":"R-1","note":"gloves","approvalToken":"tok",
             "clerkId":"X"}
            """;

    @MockitoBean
    private RegisterSessionService registerSessionService;

    @MockitoBean
    private CashMovementApprovalService cashMovementApprovalService;

    private static CashMovementSummary movement() {
        return new CashMovementSummary(
                UUID.randomUUID(),
                SESSION_ID,
                REQUEST_ID,
                "PETTY_EXPENSE",
                "PAID_OUT",
                new BigDecimal("25.0000"),
                "SHOP_SUPPLIES",
                null,
                null,
                "R-1",
                "gloves",
                "cashier",
                "01900000-0000-7000-8000-00000000b001",
                Instant.parse("2026-10-07T12:00:00Z"));
    }

    @Test
    @DisplayName("201 for a new movement; the body's clerkId never reaches the command (AC4)")
    void recordsMovement() throws Exception {
        when(registerSessionService.recordCashMovement(any())).thenReturn(new CashMovementResult(movement(), false));

        mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/sessions/{sessionId}/cash-movements", SESSION_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(PETTY_BODY),
                        CASH_MOVEMENT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reason").value("PETTY_EXPENSE"))
                .andExpect(jsonPath("$.approvedBy").value("01900000-0000-7000-8000-00000000b001"))
                .andExpect(jsonPath("$.clerkId").value("cashier"));

        ArgumentCaptor<CashMovementCommand> command = ArgumentCaptor.forClass(CashMovementCommand.class);
        verify(registerSessionService).recordCashMovement(command.capture());
        assertThat(command.getValue().requestId()).isEqualTo(REQUEST_ID);
        assertThat(command.getValue().approvalToken()).isEqualTo("tok");
        assertThat(command.getValue().toString()).doesNotContain("\"X\"").doesNotContain("clerkId");
    }

    @Test
    @DisplayName("AC13: a replay answers 200 with the first result")
    void replayIs200() throws Exception {
        when(registerSessionService.recordCashMovement(any())).thenReturn(new CashMovementResult(movement(), true));

        mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/sessions/{sessionId}/cash-movements", SESSION_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(PETTY_BODY),
                        CASH_MOVEMENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID.toString()));
    }

    @ParameterizedTest
    @EnumSource(Refusal.class)
    @DisplayName("every drawer refusal answers its own code and status")
    void refusalsAnswerTheirCodes(Refusal refusal) throws Exception {
        when(registerSessionService.recordCashMovement(any()))
                .thenThrow(new CashMovementRefusedException(refusal, "refused"));

        mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/sessions/{sessionId}/cash-movements", SESSION_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(PETTY_BODY),
                        CASH_MOVEMENT))
                .andExpect(status().is(refusal.status()))
                .andExpect(jsonPath("$.code").value(refusal.code()));
    }

    @Test
    @DisplayName("400 REGISTER_SESSION_INVALID_ARGUMENT and 409 IDEMPOTENCY_CONFLICT")
    void validationAndIdempotency() throws Exception {
        when(registerSessionService.recordCashMovement(any()))
                .thenThrow(new RegisterSessionRequestValidationException("bagNumber is required for BANK_DROP"))
                .thenThrow(new CashMovementIdempotencyConflictException("requestId reused"));

        mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/sessions/{sessionId}/cash-movements", SESSION_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(PETTY_BODY),
                        CASH_MOVEMENT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REGISTER_SESSION_INVALID_ARGUMENT"));
        mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/sessions/{sessionId}/cash-movements", SESSION_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(PETTY_BODY),
                        CASH_MOVEMENT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    @DisplayName("a caller without order:session:cash_movement is refused before the service")
    void cashMovementPermissionRequired() throws Exception {
        mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/sessions/{sessionId}/cash-movements", SESSION_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(PETTY_BODY),
                        "order:session:view"))
                .andExpect(status().isForbidden());
        verify(registerSessionService, never()).recordCashMovement(any());
    }

    @Test
    @DisplayName("step-up: 201 with the token and its expiry")
    void stepUpIssuesToken() throws Exception {
        when(cashMovementApprovalService.approve(any()))
                .thenReturn(new CashMovementApprovalResult("tok-123", Instant.parse("2026-10-07T12:05:00Z")));

        mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/sessions/{sessionId}/cash-movement-approvals", SESSION_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                                {"managerUsername":"jane","managerPassword":"s3cret",
                                                 "reason":"PETTY_EXPENSE","amount":25.00,"categoryCode":"SHOP_SUPPLIES"}
                                                """),
                        CASH_MOVEMENT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.approvalToken").value("tok-123"))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-07T12:05:00Z"));

        ArgumentCaptor<CashMovementApprovalCommand> command =
                ArgumentCaptor.forClass(CashMovementApprovalCommand.class);
        verify(cashMovementApprovalService).approve(command.capture());
        assertThat(command.getValue().managerUsername()).isEqualTo("jane");
        assertThat(command.getValue().amount()).isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("AC14: a denied step-up answers 403 CASH_MOVEMENT_APPROVAL_DENIED, never 401; unavailable is 503")
    void stepUpDeniedIs403() throws Exception {
        when(cashMovementApprovalService.approve(any()))
                .thenThrow(new CashMovementRefusedException(
                        Refusal.APPROVAL_DENIED, "The manager's credentials could not be verified for this approval"))
                .thenThrow(new StepUpUnavailableException("down", null));
        String body = """
                {"managerUsername":"jane","managerPassword":"wrong","reason":"FLOAT_INCREASE","amount":50.00}
                """;

        String denied = mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/sessions/{sessionId}/cash-movement-approvals", SESSION_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        CASH_MOVEMENT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CASH_MOVEMENT_APPROVAL_DENIED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(denied).doesNotContain("wrong");
        mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/sessions/{sessionId}/cash-movement-approvals", SESSION_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        CASH_MOVEMENT))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CASH_MOVEMENT_APPROVAL_UNAVAILABLE"));
    }

    @Test
    @DisplayName("options: reasons with limits and running totals, and the ACTIVE categories")
    void options() throws Exception {
        when(registerSessionService.cashMovementOptions(SESSION_ID))
                .thenReturn(new CashMovementOptions(
                        SESSION_ID,
                        List.of(new CashMovementOptions.ReasonOption(
                                "PETTY_EXPENSE",
                                "PAID_OUT",
                                true,
                                new BigDecimal("50.0000"),
                                new BigDecimal("55.0000"),
                                false,
                                List.of("categoryCode", "receiptReference", "note"))),
                        List.of(new CashMovementOptions.CategoryOption("SHOP_SUPPLIES", "Shop supplies", "rags"))));

        mockMvc.perform(withGatewayAuth(
                        get("/v1/orders/sessions/{sessionId}/cash-movement-options", SESSION_ID), CASH_MOVEMENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reasons[0].runningTotal").value(55.0))
                .andExpect(jsonPath("$.categories[0].code").value("SHOP_SUPPLIES"));
    }
}
