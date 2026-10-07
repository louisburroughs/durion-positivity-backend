package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.bankrec.intake.MinorUnit;
import com.positivity.accounting.internal.dto.DepositResponse;
import com.positivity.accounting.internal.dto.UndepositedSessionsResponse;
import com.positivity.accounting.internal.enums.DepositStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.exception.CashSetupException;
import com.positivity.accounting.internal.exception.CurrencyNotSupportedException;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.service.DepositService;
import com.positivity.security.common.LocationScopeDeniedException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The bank deposit endpoints over HTTP (CAP:550 S18, #2514): the permission gates (accounting:deposit:create for the
 * read, Record and the deposit; accounting:deposit:reverse for Reverse), the request checks (400 VALIDATION_ERROR
 * naming the field), 201 for a new deposit and 200 for a replay, and the status and code of every refusal the
 * service raises. The service is a mock here; its behaviour is covered by DepositServiceImplTest and
 * BankDepositPostgresIT.
 */
@DisplayName("Bank deposit endpoints over HTTP (#2514)")
class BankDepositControllerTest extends BaseIntegrationTest {

    private static final UUID SESSION = UUID.fromString("019a0000-0000-7000-8000-00000000c001");
    private static final UUID DEPOSIT = UUID.fromString("019a0000-0000-7000-8000-00000000d001");
    private static final String CREATE = "accounting:deposit:create";
    private static final String REVERSE = "accounting:deposit:reverse";
    private static final String DEPOSITS = "/v1/accounting/deposits";
    private static final String REVERSAL = DEPOSITS + "/" + DEPOSIT + "/reversal";
    private static final String BODY = """
            {"bankGlAccountId":"019a0000-0000-7000-8000-00000000b000","depositDate":"2026-10-08",
             "currencyCode":"USD","sessionIds":["%s"],"requestId":"019a0000-0000-7000-8000-000000000301",
             "depositSlipReference":"DS-20261008-01"}
            """.formatted(SESSION);
    private static final String REVERSAL_BODY = """
            {"reason":"Deposited into the wrong bank account","requestId":"019a0000-0000-7000-8000-000000000302"}
            """;

    @MockitoBean
    private DepositService service;

    @Test
    @DisplayName("AC8: without accounting:deposit:create the read, Record and the deposit are 403; without"
            + " accounting:deposit:reverse the reversal is 403, accounting:deposit:create notwithstanding")
    void permissions() throws Exception {
        mockMvc.perform(get("/v1/accounting/undeposited-sessions")
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", "accounting:je:view"))
                .andExpect(status().isForbidden());
        post(DEPOSITS, "accounting:je:create,accounting:je:post", BODY).andExpect(status().isForbidden());
        mockMvc.perform(get(DEPOSITS + "/" + DEPOSIT)
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", REVERSE))
                .andExpect(status().isForbidden());
        post(REVERSAL, CREATE + ",accounting:je:reverse", REVERSAL_BODY).andExpect(status().isForbidden());

        verify(service, never()).undeposited(any(), any());
        verify(service, never()).record(any());
        verify(service, never()).get(any());
        verify(service, never()).reverse(any(), any());
    }

    @Test
    @DisplayName(
            "the read passes the selection and the bank account through and answers 200; an unknown session is 400")
    void read() throws Exception {
        UUID bank = UUID.fromString("019a0000-0000-7000-8000-00000000b000");
        when(service.undeposited(List.of(SESSION), bank))
                .thenReturn(new UndepositedSessionsResponse(
                        LocalDate.of(2026, 10, 9),
                        "USD",
                        List.of(),
                        new UndepositedSessionsResponse.Selection(
                                List.of(SESSION),
                                new BigDecimal("1197.00"),
                                new BigDecimal("1240.00"),
                                new BigDecimal("-43.00"),
                                BigDecimal.ZERO,
                                true,
                                List.of(new UndepositedSessionsResponse.PreviewLine(
                                        UUID.randomUUID(),
                                        "1095",
                                        "Register Cash Clearing",
                                        UndepositedSessionsResponse.Side.DEBIT,
                                        new BigDecimal("43.00"))))));

        mockMvc.perform(get("/v1/accounting/undeposited-sessions")
                        .param("sessionId", SESSION.toString())
                        .param("bankGlAccountId", bank.toString())
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", CREATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencyCode").value("USD"))
                .andExpect(jsonPath("$.selection.depositAmount").value(1197.00))
                .andExpect(jsonPath("$.selection.clearingNet").value(-43.00))
                .andExpect(jsonPath("$.selection.balanced").value(true))
                .andExpect(jsonPath("$.selection.lines[0].side").value("DEBIT"));

        when(service.undeposited(List.of(), null))
                .thenThrow(InvalidRequestParameterException.forField("sessionId", "No undeposited session"));
        mockMvc.perform(get("/v1/accounting/undeposited-sessions")
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", CREATE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("sessionId"));
    }

    @Test
    @DisplayName("a missing or invalid field is 400 VALIDATION_ERROR naming it, before the service")
    void bodiesAreValidated() throws Exception {
        List<Map.Entry<String, String>> bodies = List.of(
                Map.entry(
                        "bankGlAccountId",
                        BODY.replace("\"bankGlAccountId\":\"019a0000-0000-7000-8000-00000000b000\",", "")),
                Map.entry("depositDate", BODY.replace("\"depositDate\":\"2026-10-08\",", "")),
                Map.entry("currencyCode", BODY.replace("\"currencyCode\":\"USD\",", "")),
                Map.entry("currencyCode", BODY.replace("\"currencyCode\":\"USD\"", "\"currencyCode\":\"usd\"")),
                Map.entry("sessionIds", BODY.replace("[\"" + SESSION + "\"]", "[]")),
                Map.entry(
                        "sessionIds",
                        BODY.replace("[\"" + SESSION + "\"]", "[\"" + SESSION + "\",\"" + SESSION + "\"]")),
                Map.entry("requestId", BODY.replace("\"requestId\":\"019a0000-0000-7000-8000-000000000301\",", "")),
                Map.entry("depositSlipReference", BODY.replace("DS-20261008-01", "X".repeat(101))));
        for (Map.Entry<String, String> body : bodies) {
            post(DEPOSITS, CREATE, body.getValue())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(body.getKey()));
        }
        for (Map.Entry<String, String> body : List.of(
                Map.entry("reason", REVERSAL_BODY.replace("Deposited into the wrong bank account", "short")),
                Map.entry("reason", REVERSAL_BODY.replace("Deposited into the wrong bank account", "X".repeat(401))),
                Map.entry("requestId", "{\"reason\":\"Deposited into the wrong bank account\"}"))) {
            post(REVERSAL, REVERSE, body.getValue())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(body.getKey()));
        }
        verify(service, never()).record(any());
        verify(service, never()).reverse(any(), any());
    }

    @Test
    @DisplayName("AC4: a new deposit is 201 and a replay 200 with replayed true; the deposit GET and the reversal 200")
    void createdReplayedReadAndReversed() throws Exception {
        when(service.record(any()))
                .thenReturn(new DepositService.Outcome(response(DepositStatus.RECORDED, false), false))
                .thenReturn(new DepositService.Outcome(response(DepositStatus.RECORDED, true), true));

        post(DEPOSITS, CREATE, BODY)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.journalEntryNumber").value("JE-202610-41"))
                .andExpect(jsonPath("$.amount").value(1197.00))
                .andExpect(jsonPath("$.currencyCode").value("USD"))
                .andExpect(jsonPath("$.sessions[0].bagNumbers[0]").value("B-0912"))
                .andExpect(jsonPath("$.replayed").value(false));
        post(DEPOSITS, CREATE, BODY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        when(service.get(DEPOSIT)).thenReturn(response(DepositStatus.RECORDED, false));
        mockMvc.perform(get(DEPOSITS + "/" + DEPOSIT)
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", CREATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECORDED"));

        when(service.reverse(eq(DEPOSIT), any()))
                .thenReturn(new DepositService.Outcome(response(DepositStatus.REVERSED, false), false));
        post(REVERSAL, REVERSE, REVERSAL_BODY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"));
    }

    @Test
    @DisplayName("every refusal of Record keeps its status and code")
    void recordRefusals() throws Exception {
        List<Object[]> cases = List.of(
                new Object[] {cash(CashSetupException.Code.DEPOSIT_SESSION_ALREADY_DEPOSITED), 409},
                new Object[] {cash(CashSetupException.Code.IDEMPOTENCY_CONFLICT), 409},
                new Object[] {cash(CashSetupException.Code.DEPOSIT_UNBALANCED), 422},
                new Object[] {cash(CashSetupException.Code.DEPOSIT_BANK_ACCOUNT_NOT_ELIGIBLE), 422},
                new Object[] {new CurrencyNotSupportedException("CAD"), 422, "CURRENCY_NOT_SUPPORTED"},
                new Object[] {
                    MinorUnit.exceeded(Map.of("selection.depositAmount", MinorUnit.detail("USD"))),
                    422,
                    "AMOUNT_PRECISION_EXCEEDS_CURRENCY"
                },
                new Object[] {
                    new AccountingPeriodClosedException("2026-09", "Period 2026-09 is closed"), 422, "PERIOD_CLOSED"
                },
                new Object[] {
                    new AccountingPeriodHardLockedException(LocalDate.of(2026, 9, 1), "Before the hard-lock date"),
                    422,
                    "PERIOD_HARD_LOCKED"
                },
                new Object[] {new LocationScopeDeniedException(CREATE, "x"), 403, "LOCATION_SCOPE_DENIED"});
        for (Object[] refusal : cases) {
            doThrow((RuntimeException) refusal[0]).when(service).record(any());
            post(DEPOSITS, CREATE, BODY)
                    .andExpect(status().is((Integer) refusal[1]))
                    .andExpect(jsonPath("$.code").value(code(refusal)));
        }
    }

    @Test
    @DisplayName("every refusal of Reverse and of the deposit GET keeps its status and code")
    void reversalRefusals() throws Exception {
        List<Object[]> cases = List.of(
                new Object[] {cash(CashSetupException.Code.DEPOSIT_NOT_FOUND), 404},
                new Object[] {cash(CashSetupException.Code.DEPOSIT_ALREADY_REVERSED), 409},
                new Object[] {cash(CashSetupException.Code.IDEMPOTENCY_CONFLICT), 409},
                new Object[] {
                    new AccountingPeriodClosedException("2026-09", "Period 2026-09 is closed"), 422, "PERIOD_CLOSED"
                },
                new Object[] {
                    new AccountingPeriodHardLockedException(LocalDate.of(2026, 9, 1), "Before the hard-lock date"),
                    422,
                    "PERIOD_HARD_LOCKED"
                });
        for (Object[] refusal : cases) {
            doThrow((RuntimeException) refusal[0]).when(service).reverse(eq(DEPOSIT), any());
            post(REVERSAL, REVERSE, REVERSAL_BODY)
                    .andExpect(status().is((Integer) refusal[1]))
                    .andExpect(jsonPath("$.code").value(code(refusal)));
        }
        doThrow(cash(CashSetupException.Code.DEPOSIT_NOT_FOUND)).when(service).get(DEPOSIT);
        mockMvc.perform(get(DEPOSITS + "/" + DEPOSIT)
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", CREATE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEPOSIT_NOT_FOUND"));
    }

    private ResultActions post(String path, String authorities, String body) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header("X-User", TEST_USER)
                .header("X-Authorities", authorities)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String code(Object[] refusal) {
        return refusal.length > 2
                ? (String) refusal[2]
                : ((CashSetupException) refusal[0]).getCode().name();
    }

    private static CashSetupException cash(CashSetupException.Code code) {
        return new CashSetupException(code, "refused");
    }

    private static DepositResponse response(DepositStatus status, boolean replayed) {
        boolean reversed = status == DepositStatus.REVERSED;
        return new DepositResponse(
                DEPOSIT,
                status,
                UUID.fromString("019a0000-0000-7000-8000-00000000b000"),
                "1000",
                LocalDate.of(2026, 10, 8),
                new BigDecimal("1197.00"),
                new BigDecimal("1240.00"),
                new BigDecimal("-43.00"),
                "USD",
                "DS-20261008-01",
                UUID.fromString("019a0000-0000-7000-8000-00000000e001"),
                "JE-202610-41",
                List.of(new DepositResponse.Session(
                        SESSION,
                        "T-7",
                        UUID.fromString("019a0000-0000-7000-8000-00000000a001"),
                        Instant.parse("2026-10-07T22:00:00Z"),
                        new BigDecimal("1197.00"),
                        new BigDecimal("1240.00"),
                        new BigDecimal("-43.00"),
                        List.of("B-0912"))),
                reversed ? UUID.fromString("019a0000-0000-7000-8000-00000000e002") : null,
                reversed ? "JE-202610-44" : null,
                reversed ? LocalDate.of(2026, 10, 9) : null,
                reversed ? "Deposited into the wrong bank account" : null,
                "clerk.ann",
                reversed ? "controller.cfo" : null,
                reversed ? Instant.parse("2026-10-09T12:00:00Z") : null,
                replayed);
    }
}
