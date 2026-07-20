package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.positivity.accounting.internal.dto.AccountingPeriodReopenRequest;
import com.positivity.accounting.internal.dto.AccountingPeriodResponse;
import com.positivity.accounting.internal.dto.HardLockDateUpdateRequest;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodNotFoundException;
import com.positivity.accounting.internal.exception.AccountingPeriodStateException;
import com.positivity.accounting.internal.exception.HardLockDateRegressionException;
import com.positivity.accounting.internal.exception.PeriodCloseBlockedException;
import com.positivity.accounting.service.AccountingConfigurationService;
import com.positivity.accounting.service.AccountingPeriodService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
            when(accountingPeriodService.closePeriod(PERIOD_CODE)).thenReturn(closedJune());

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
            when(accountingPeriodService.closePeriod(PERIOD_CODE))
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
            when(accountingPeriodService.closePeriod(PERIOD_CODE))
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
            when(accountingPeriodService.closePeriod("2099-01"))
                    .thenThrow(new AccountingPeriodNotFoundException("2099-01", "Period 2099-01 not found"));

            mockMvc.perform(withAuth(post("/v1/accounting/periods/{periodCode}/close", "2099-01")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PERIOD_NOT_FOUND"));
        }

        @Test
        @DisplayName("Should return 400 for an invalid period code")
        void shouldReturn400ForInvalidPeriodCode() throws Exception {
            when(accountingPeriodService.closePeriod("not-a-period"))
                    .thenThrow(new IllegalArgumentException("Invalid period code: not-a-period"));

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

            verify(accountingPeriodService, never()).closePeriod(anyString());
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
}
