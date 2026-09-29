package com.positivity.accounting.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.bankrec.dto.CloseReadinessAccount;
import com.positivity.accounting.internal.bankrec.dto.CloseReadinessCheck;
import com.positivity.accounting.internal.bankrec.dto.CloseReadinessResponse;
import com.positivity.accounting.internal.bankrec.enums.BankRecClosePolicy;
import com.positivity.accounting.internal.bankrec.enums.BankRecCloseScope;
import com.positivity.accounting.internal.bankrec.enums.ReadinessCheckCode;
import com.positivity.accounting.internal.bankrec.enums.ReadinessSeverity;
import com.positivity.accounting.internal.dto.AccountingPeriodReopenRequest;
import com.positivity.accounting.internal.dto.AccountingPeriodResponse;
import com.positivity.accounting.internal.dto.BankReconciliationPolicyRequest;
import com.positivity.accounting.internal.dto.BankReconciliationPolicyResponse;
import com.positivity.accounting.internal.dto.HardLockDateUpdateRequest;
import com.positivity.accounting.internal.dto.PeriodCloseRequest;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodNotFoundException;
import com.positivity.accounting.internal.exception.AccountingPeriodStateException;
import com.positivity.accounting.internal.exception.HardLockDateRegressionException;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.exception.PeriodBankReconciliationIncompleteException;
import com.positivity.accounting.internal.exception.PeriodCloseBlockedException;
import com.positivity.accounting.internal.exception.PeriodCloseExceptionNotPermittedException;
import com.positivity.accounting.internal.service.AccountingConfigurationService;
import com.positivity.accounting.internal.service.AccountingPeriodService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Tests for AccountingPeriodController (Story B1, Issue #937): period
 * listing plus close/reopen lifecycle transitions, including permission
 * enforcement and the ApiError contract for 400/404/409/422 outcomes.
 */
@DisplayName("AccountingPeriodController Tests")
class AccountingPeriodControllerTest extends BaseIntegrationTest {

    private static final UUID PERIOD_ID = UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678901234");
    private static final String PERIOD_CODE = "2026-06";

    @MockitoBean
    private AccountingPeriodService accountingPeriodService;

    @MockitoBean
    private AccountingConfigurationService accountingConfigurationService;

    private static AccountingPeriodResponse openJune() {
        return AccountingPeriodResponse.builder()
                .periodId(PERIOD_ID)
                .periodCode(PERIOD_CODE)
                .startDate(LocalDate.of(2026, 6, 1))
                .endDate(LocalDate.of(2026, 6, 30))
                .status(AccountingPeriodStatus.OPEN)
                .build();
    }

    private static AccountingPeriodResponse closedJune() {
        return AccountingPeriodResponse.builder()
                .periodId(PERIOD_ID)
                .periodCode(PERIOD_CODE)
                .startDate(LocalDate.of(2026, 6, 1))
                .endDate(LocalDate.of(2026, 6, 30))
                .status(AccountingPeriodStatus.CLOSED)
                .closedAt(Instant.parse("2026-07-01T09:00:00Z"))
                .closedBy("testuser")
                .build();
    }

    private String reopenBody(String justification) {
        return objectMapper.writeValueAsString(AccountingPeriodReopenRequest.builder()
                .justification(justification)
                .build());
    }

    @Nested
    @DisplayName("GET /v1/accounting/periods")
    class ListPeriods {

        @Test
        @DisplayName("Should list periods most recent first")
        void shouldListPeriods() throws Exception {
            when(accountingPeriodService.listPeriods()).thenReturn(List.of(closedJune(), openJune()));

            mockMvc.perform(withAuth(get("/v1/accounting/periods")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].periodId").value(PERIOD_ID.toString()))
                    .andExpect(jsonPath("$[0].periodCode").value(PERIOD_CODE))
                    .andExpect(jsonPath("$[0].status").value("CLOSED"))
                    .andExpect(jsonPath("$[1].status").value("OPEN"));
        }

        @Test
        @DisplayName("Should reject listing without accounting:period:view authority")
        void shouldRejectWithoutPermission() throws Exception {
            mockMvc.perform(withAuth(get("/v1/accounting/periods"), "accounting:je:view"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("POST /v1/accounting/periods/{periodCode}/close")
    class ClosePeriod {

        @Test
        @DisplayName("Should close an open period and return the closed period")
        void shouldClosePeriod() throws Exception {
            when(accountingPeriodService.closePeriod(eq(PERIOD_CODE), isNull())).thenReturn(closedJune());

            mockMvc.perform(withAuth(post("/v1/accounting/periods/{periodCode}/close", PERIOD_CODE)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.periodCode").value(PERIOD_CODE))
                    .andExpect(jsonPath("$.status").value("CLOSED"))
                    .andExpect(jsonPath("$.closedBy").value("testuser"));
        }

        @Test
        @DisplayName("Should return 422 with draft entry IDs when drafts block the close")
        void shouldReturn422WithDraftEntryIds() throws Exception {
            UUID draftA = UUID.fromString("01936e5e-0000-7000-8000-00000000000a");
            UUID draftB = UUID.fromString("01936e5e-0000-7000-8000-00000000000b");
            when(accountingPeriodService.closePeriod(eq(PERIOD_CODE), isNull()))
                    .thenThrow(new PeriodCloseBlockedException(PERIOD_CODE, List.of(draftA, draftB)));

            mockMvc.perform(withAuth(post("/v1/accounting/periods/{periodCode}/close", PERIOD_CODE)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("PERIOD_HAS_DRAFT_ENTRIES"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("draftJournalEntryIds"))
                    .andExpect(jsonPath("$.fieldErrors[0].message").value(draftA.toString()))
                    .andExpect(jsonPath("$.fieldErrors[1].message").value(draftB.toString()))
                    .andExpect(jsonPath("$.correlationId").exists());
        }

        @Test
        @DisplayName("Should return 409 when the period is already closed")
        void shouldReturn409WhenAlreadyClosed() throws Exception {
            when(accountingPeriodService.closePeriod(eq(PERIOD_CODE), isNull()))
                    .thenThrow(new AccountingPeriodStateException(
                            PERIOD_CODE,
                            AccountingPeriodStatus.CLOSED,
                            "Period " + PERIOD_CODE + " is already CLOSED"));

            mockMvc.perform(withAuth(post("/v1/accounting/periods/{periodCode}/close", PERIOD_CODE)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PERIOD_ALREADY_CLOSED"));
        }

        @Test
        @DisplayName("Should return 404 for an unknown period")
        void shouldReturn404ForUnknownPeriod() throws Exception {
            when(accountingPeriodService.closePeriod(eq("2099-01"), isNull()))
                    .thenThrow(new AccountingPeriodNotFoundException("2099-01", "Period 2099-01 not found"));

            mockMvc.perform(withAuth(post("/v1/accounting/periods/{periodCode}/close", "2099-01")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PERIOD_NOT_FOUND"));
        }

        @Test
        @DisplayName("Should return 400 for an invalid period code")
        void shouldReturn400ForInvalidPeriodCode() throws Exception {
            when(accountingPeriodService.closePeriod(eq("not-a-period"), isNull()))
                    .thenThrow(new InvalidRequestParameterException("Invalid period code: not-a-period"));

            mockMvc.perform(withAuth(post("/v1/accounting/periods/{periodCode}/close", "not-a-period")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("Should reject close without accounting:period:close authority")
        void shouldRejectWithoutPermission() throws Exception {
            mockMvc.perform(withAuth(
                            post("/v1/accounting/periods/{periodCode}/close", PERIOD_CODE), "accounting:period:view"))
                    .andExpect(status().isForbidden());

            verify(accountingPeriodService, never()).closePeriod(anyString(), any());
        }
    }

    @Nested
    @DisplayName("POST /v1/accounting/periods/{periodCode}/reopen")
    class ReopenPeriod {

        @Test
        @DisplayName("Should reopen a closed period with a justification")
        void shouldReopenPeriod() throws Exception {
            AccountingPeriodResponse reopened = openJune();
            reopened.setReopenedAt(Instant.parse("2026-07-02T10:00:00Z"));
            reopened.setReopenedBy("testuser");
            reopened.setReopenJustification("Late vendor bill");
            when(accountingPeriodService.reopenPeriod(PERIOD_CODE, "Late vendor bill"))
                    .thenReturn(reopened);

            mockMvc.perform(withAuth(post("/v1/accounting/periods/{periodCode}/reopen", PERIOD_CODE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(reopenBody("Late vendor bill")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.periodCode").value(PERIOD_CODE))
                    .andExpect(jsonPath("$.status").value("OPEN"))
                    .andExpect(jsonPath("$.reopenedBy").value("testuser"))
                    .andExpect(jsonPath("$.reopenJustification").value("Late vendor bill"));
        }

        @Test
        @DisplayName("Should return 400 when justification is blank")
        void shouldReturn400ForBlankJustification() throws Exception {
            mockMvc.perform(withAuth(post("/v1/accounting/periods/{periodCode}/reopen", PERIOD_CODE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(reopenBody("   ")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ARGUMENT_NOT_VALID"));

            verify(accountingPeriodService, never()).reopenPeriod(anyString(), anyString());
        }

        @Test
        @DisplayName("Should return 400 when justification is missing")
        void shouldReturn400ForMissingJustification() throws Exception {
            mockMvc.perform(withAuth(post("/v1/accounting/periods/{periodCode}/reopen", PERIOD_CODE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ARGUMENT_NOT_VALID"));

            verify(accountingPeriodService, never()).reopenPeriod(anyString(), anyString());
        }

        @Test
        @DisplayName("Should return 409 when the period is already open")
        void shouldReturn409WhenAlreadyOpen() throws Exception {
            when(accountingPeriodService.reopenPeriod(eq(PERIOD_CODE), anyString()))
                    .thenThrow(new AccountingPeriodStateException(
                            PERIOD_CODE, AccountingPeriodStatus.OPEN, "Period " + PERIOD_CODE + " is already OPEN"));

            mockMvc.perform(withAuth(post("/v1/accounting/periods/{periodCode}/reopen", PERIOD_CODE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(reopenBody("Late vendor bill")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PERIOD_ALREADY_OPEN"));
        }

        @Test
        @DisplayName("Should return 404 for an unknown period")
        void shouldReturn404ForUnknownPeriod() throws Exception {
            when(accountingPeriodService.reopenPeriod(eq("2099-01"), anyString()))
                    .thenThrow(new AccountingPeriodNotFoundException("2099-01", "Period 2099-01 not found"));

            mockMvc.perform(withAuth(post("/v1/accounting/periods/{periodCode}/reopen", "2099-01"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(reopenBody("Late vendor bill")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PERIOD_NOT_FOUND"));
        }

        @Test
        @DisplayName("Should reject reopen without accounting:period:reopen authority")
        void shouldRejectWithoutPermission() throws Exception {
            mockMvc.perform(withAuth(
                                    post("/v1/accounting/periods/{periodCode}/reopen", PERIOD_CODE),
                                    "accounting:period:close")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(reopenBody("Late vendor bill")))
                    .andExpect(status().isForbidden());

            verify(accountingPeriodService, never()).reopenPeriod(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("GET /v1/accounting/periods/hard-lock")
    class GetHardLockDate {

        @Test
        @DisplayName("Should return the configured hard-lock date")
        void shouldReturnHardLockDate() throws Exception {
            when(accountingConfigurationService.getHardLockDate()).thenReturn(Optional.of(LocalDate.of(2026, 6, 30)));

            mockMvc.perform(withAuth(get("/v1/accounting/periods/hard-lock")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hardLockDate").value("2026-06-30"));
        }

        @Test
        @DisplayName("Should return an explicit null hardLockDate when no hard lock is set")
        void shouldReturnExplicitNullWhenUnset() throws Exception {
            when(accountingConfigurationService.getHardLockDate()).thenReturn(Optional.empty());

            mockMvc.perform(withAuth(get("/v1/accounting/periods/hard-lock")))
                    .andExpect(status().isOk())
                    // Include.ALWAYS on the DTO: the property must be present with an explicit null value
                    .andExpect(content().json("{\"hardLockDate\":null}"));
        }

        @Test
        @DisplayName("Should reject viewing without accounting:period:view authority")
        void shouldRejectWithoutPermission() throws Exception {
            mockMvc.perform(withAuth(get("/v1/accounting/periods/hard-lock"), "accounting:je:view"))
                    .andExpect(status().isForbidden());

            verify(accountingConfigurationService, never()).getHardLockDate();
        }
    }

    @Nested
    @DisplayName("PUT /v1/accounting/periods/hard-lock")
    class SetHardLockDate {

        private static final LocalDate NEW_LOCK = LocalDate.of(2026, 6, 30);

        private String hardLockBody(LocalDate date, String justification) {
            return objectMapper.writeValueAsString(HardLockDateUpdateRequest.builder()
                    .hardLockDate(date)
                    .justification(justification)
                    .build());
        }

        @Test
        @DisplayName("Should set the hard-lock date and return the stored value")
        void shouldSetHardLockDate() throws Exception {
            when(accountingConfigurationService.setHardLockDate(NEW_LOCK, "FY close complete"))
                    .thenReturn(NEW_LOCK);

            mockMvc.perform(withAuth(put("/v1/accounting/periods/hard-lock"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(hardLockBody(NEW_LOCK, "FY close complete")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hardLockDate").value("2026-06-30"));

            verify(accountingConfigurationService).setHardLockDate(NEW_LOCK, "FY close complete");
        }

        @Test
        @DisplayName("Should return 400 when justification is blank")
        void shouldReturn400ForBlankJustification() throws Exception {
            mockMvc.perform(withAuth(put("/v1/accounting/periods/hard-lock"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(hardLockBody(NEW_LOCK, "   ")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ARGUMENT_NOT_VALID"));

            verify(accountingConfigurationService, never()).setHardLockDate(any(LocalDate.class), anyString());
        }

        @Test
        @DisplayName("Should return 400 when hardLockDate is missing")
        void shouldReturn400ForMissingDate() throws Exception {
            mockMvc.perform(withAuth(put("/v1/accounting/periods/hard-lock"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(hardLockBody(null, "FY close complete")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ARGUMENT_NOT_VALID"));

            verify(accountingConfigurationService, never()).setHardLockDate(any(LocalDate.class), anyString());
        }

        @Test
        @DisplayName("Should map a backward date move to 422 HARD_LOCK_DATE_REGRESSION")
        void shouldReturn422ForBackwardMove() throws Exception {
            LocalDate earlier = LocalDate.of(2026, 1, 31);
            when(accountingConfigurationService.setHardLockDate(earlier, "trying to unwind"))
                    .thenThrow(new HardLockDateRegressionException(NEW_LOCK, earlier));

            mockMvc.perform(withAuth(put("/v1/accounting/periods/hard-lock"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(hardLockBody(earlier, "trying to unwind")))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("HARD_LOCK_DATE_REGRESSION"))
                    .andExpect(jsonPath("$.status").value(422))
                    .andExpect(jsonPath("$.correlationId").exists());
        }

        @Test
        @DisplayName("Should reject setting without accounting:period:hard_lock authority")
        void shouldRejectWithoutPermission() throws Exception {
            mockMvc.perform(withAuth(put("/v1/accounting/periods/hard-lock"), "accounting:period:close")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(hardLockBody(NEW_LOCK, "FY close complete")))
                    .andExpect(status().isForbidden());

            verify(accountingConfigurationService, never()).setHardLockDate(any(LocalDate.class), anyString());
        }
    }

    @Nested
    @DisplayName("bank reconciliation close readiness, policy and exception (#2305)")
    class BankReconciliationClose {

        private final UUID cash = UUID.fromString("01936e5e-0000-7000-8000-000000001000");

        @Test
        @DisplayName("GET close-readiness returns the read model with top-level checks and per-account baselineDate")
        void readiness() throws Exception {
            CloseReadinessCheck check = new CloseReadinessCheck(
                    ReadinessCheckCode.RECONCILIATION_IN_FLIGHT,
                    ReadinessSeverity.BLOCKING,
                    "1 IN_PROGRESS reconciliation",
                    Map.of("reconciliationIds", List.of()));
            when(accountingPeriodService.getCloseReadiness(PERIOD_CODE))
                    .thenReturn(new CloseReadinessResponse(
                            PERIOD_CODE,
                            LocalDate.of(2026, 6, 1),
                            LocalDate.of(2026, 6, 30),
                            AccountingPeriodStatus.OPEN,
                            BankRecClosePolicy.REQUIRED_WITH_EXCEPTION,
                            0,
                            false,
                            1,
                            0,
                            List.of(),
                            List.of(new CloseReadinessAccount(
                                    cash,
                                    "1000",
                                    "Cash",
                                    null,
                                    LocalDate.of(2026, 6, 30),
                                    null,
                                    List.of(),
                                    BigDecimal.ZERO,
                                    List.of(check)))));

            mockMvc.perform(withAuth(
                            get("/v1/accounting/periods/{periodCode}/close-readiness", PERIOD_CODE),
                            "accounting:period:view"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.policy").value("REQUIRED_WITH_EXCEPTION"))
                    .andExpect(jsonPath("$.ready").value(false))
                    .andExpect(jsonPath("$.checks").isArray())
                    .andExpect(jsonPath("$.accounts[0].accountCode").value("1000"))
                    .andExpect(jsonPath("$.accounts[0].baselineDate").isEmpty())
                    .andExpect(jsonPath("$.accounts[0].checks[0].code").value("RECONCILIATION_IN_FLIGHT"))
                    .andExpect(jsonPath("$.accounts[0].checks[0].severity").value("BLOCKING"));
        }

        @Test
        @DisplayName("close passes the exception body to the service")
        void closeWithException() throws Exception {
            when(accountingPeriodService.closePeriod(eq(PERIOD_CODE), any())).thenReturn(closedJune());

            mockMvc.perform(withAuth(post("/v1/accounting/periods/{periodCode}/close", PERIOD_CODE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"bankReconciliationException\":{\"justification\":\"Statement delayed\"}}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<PeriodCloseRequest> body = ArgumentCaptor.forClass(PeriodCloseRequest.class);
            verify(accountingPeriodService).closePeriod(eq(PERIOD_CODE), body.capture());
            assertThat(body.getValue().getBankReconciliationException().getJustification())
                    .isEqualTo("Statement delayed");
        }

        @Test
        @DisplayName("close answers 422 PERIOD_BANK_RECONCILIATION_INCOMPLETE with unreconciledGlAccountIds")
        void closeRefused() throws Exception {
            when(accountingPeriodService.closePeriod(eq(PERIOD_CODE), isNull()))
                    .thenThrow(new PeriodBankReconciliationIncompleteException(
                            PERIOD_CODE,
                            List.of(new PeriodBankReconciliationIncompleteException.UnreconciledAccount(
                                    cash, "1000", List.of("RECONCILIATION_IN_FLIGHT"))),
                            null));

            mockMvc.perform(withAuth(post("/v1/accounting/periods/{periodCode}/close", PERIOD_CODE)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("PERIOD_BANK_RECONCILIATION_INCOMPLETE"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("unreconciledGlAccountIds"))
                    .andExpect(jsonPath("$.fieldErrors[0].message").value(cash + " 1000: RECONCILIATION_IN_FLIGHT"));
        }

        @Test
        @DisplayName("close answers 403 PERIOD_CLOSE_EXCEPTION_NOT_PERMITTED")
        void closeExceptionNotPermitted() throws Exception {
            when(accountingPeriodService.closePeriod(eq(PERIOD_CODE), any()))
                    .thenThrow(new PeriodCloseExceptionNotPermittedException("needs override"));

            mockMvc.perform(withAuth(post("/v1/accounting/periods/{periodCode}/close", PERIOD_CODE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"bankReconciliationException\":{\"justification\":\"Statement delayed\"}}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERIOD_CLOSE_EXCEPTION_NOT_PERMITTED"));
        }

        @Test
        @DisplayName("GET policy is readable with accounting:period:view")
        void getPolicy() throws Exception {
            when(accountingConfigurationService.getBankReconciliationPolicy())
                    .thenReturn(BankReconciliationPolicyResponse.builder()
                            .closePolicy(BankRecClosePolicy.REQUIRED_WITH_EXCEPTION)
                            .closeScope(BankRecCloseScope.BANK_CASH_SUBTYPE)
                            .closeCoverageLagDays(0)
                            .allowSelfApproval(false)
                            .currency("USD")
                            .build());

            mockMvc.perform(withAuth(
                            get("/v1/accounting/periods/bank-reconciliation-policy"), "accounting:period:view"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.closePolicy").value("REQUIRED_WITH_EXCEPTION"))
                    .andExpect(jsonPath("$.otherApprovalThreshold").isEmpty());
        }

        @Test
        @DisplayName("AC14: PUT policy needs accounting:period:hard_lock; a view-only caller is refused")
        void putPolicyNeedsHardLock() throws Exception {
            mockMvc.perform(withAuth(put("/v1/accounting/periods/bank-reconciliation-policy"), "accounting:period:view")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(POLICY_BODY))
                    .andExpect(status().isForbidden());

            verify(accountingConfigurationService, never()).setBankReconciliationPolicy(any());
        }

        @Test
        @DisplayName("PUT policy passes an explicit null threshold through as sent")
        void putPolicy() throws Exception {
            when(accountingConfigurationService.setBankReconciliationPolicy(any()))
                    .thenReturn(BankReconciliationPolicyResponse.builder().build());

            mockMvc.perform(withAuth(
                                    put("/v1/accounting/periods/bank-reconciliation-policy"),
                                    "accounting:period:hard_lock")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(POLICY_BODY))
                    .andExpect(status().isOk());

            ArgumentCaptor<BankReconciliationPolicyRequest> body =
                    ArgumentCaptor.forClass(BankReconciliationPolicyRequest.class);
            verify(accountingConfigurationService).setBankReconciliationPolicy(body.capture());
            assertThat(body.getValue().isOtherApprovalThresholdPresent()).isTrue();
            assertThat(body.getValue().getOtherApprovalThreshold()).isNull();
            assertThat(body.getValue().getCloseCoverageLagDays()).isEqualTo(31);
        }

        @Test
        @DisplayName("PUT policy with an unknown enum value is 400 VALIDATION_ERROR")
        void putPolicyUnknownEnum() throws Exception {
            mockMvc.perform(withAuth(
                                    put("/v1/accounting/periods/bank-reconciliation-policy"),
                                    "accounting:period:hard_lock")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(POLICY_BODY.replace("REQUIRED_WITH_EXCEPTION", "SOMETIMES")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    private static final String POLICY_BODY = """
            {"closePolicy":"REQUIRED_WITH_EXCEPTION","closeScope":"BANK_CASH_SUBTYPE","closeCoverageLagDays":31,
             "allowSelfApproval":false,"otherApprovalThreshold":null,"justification":"Bank cycles end mid-month"}
            """;
}
