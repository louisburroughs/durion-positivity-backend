package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.bankrec.intake.MinorUnit;
import com.positivity.accounting.internal.dto.BankOpeningBalanceResponse;
import com.positivity.accounting.internal.enums.BankOpeningItemType;
import com.positivity.accounting.internal.exception.CashSetupException;
import com.positivity.accounting.internal.exception.CurrencyNotSupportedException;
import com.positivity.accounting.internal.exception.GLAccountNotFoundException;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.service.BankOpeningBalanceService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The bank opening balance endpoint over HTTP (#2572): the permission gate (both accounting:je:create and
 * accounting:je:post), the request checks (400 VALIDATION_ERROR naming the field), 201 for a new opening and 200
 * for a replay, and the status and code of each refusal the service raises. The service is a mock here; its
 * behaviour is covered by BankOpeningBalanceServiceImplTest and BankOpeningBalancePostgresIT.
 */
@DisplayName("Bank opening balance endpoint over HTTP (#2572)")
class BankOpeningBalanceControllerTest extends BaseIntegrationTest {

    private static final UUID ACCOUNT = UUID.fromString("019a0000-0000-7000-8000-00000000b000");
    private static final String OPENING = "/v1/accounting/bank-accounts/" + ACCOUNT + "/opening-balance";
    private static final String BOTH = "accounting:je:create,accounting:je:post";
    private static final String BODY = """
            {"asOfDate":"2025-10-31","statementBalance":10000.00,"currencyCode":"USD",
             "outstandingItems":[{"type":"OUTSTANDING_CHECK","reference":"1043","itemDate":"2025-10-28","amount":450.00}],
             "justification":"Opening balance per the October bank statement",
             "requestId":"019a0000-0000-7000-8000-000000000201"}
            """;

    @MockitoBean
    private BankOpeningBalanceService service;

    @Test
    @DisplayName("403 with only one of accounting:je:create and accounting:je:post, or neither")
    void needsBothPermissions() throws Exception {
        for (String authorities : new String[] {"accounting:je:view", "accounting:je:create", "accounting:je:post"}) {
            perform(authorities, BODY).andExpect(status().isForbidden());
        }
        verify(service, never()).establish(any(), any());
    }

    @Test
    @DisplayName("a short justification, an itemDate after asOfDate, a non-positive or oversized amount, or a missing"
            + " or non-ISO currencyCode is 400 VALIDATION_ERROR naming the field")
    void bodyIsValidated() throws Exception {
        // Each malformed body and the field its fieldErrors names.
        List<Map.Entry<String, String>> bodies = List.of(
                Map.entry("justification", BODY.replace("Opening balance per the October bank statement", "short")),
                Map.entry(
                        "outstandingItems[0].itemDate",
                        BODY.replace("\"itemDate\":\"2025-10-28\"", "\"itemDate\":\"2025-11-01\"")),
                Map.entry("outstandingItems[0].amount", BODY.replace("\"amount\":450.00", "\"amount\":0")),
                Map.entry(
                        "outstandingItems[0].amount", BODY.replace("\"amount\":450.00", "\"amount\":100000000000000")),
                Map.entry(
                        "statementBalance",
                        BODY.replace("\"statementBalance\":10000.00", "\"statementBalance\":-100000000000000")),
                Map.entry("currencyCode", BODY.replace("\"currencyCode\":\"USD\",", "")),
                Map.entry("currencyCode", BODY.replace("\"currencyCode\":\"USD\"", "\"currencyCode\":\"XYZ\"")),
                Map.entry("currencyCode", BODY.replace("\"currencyCode\":\"USD\"", "\"currencyCode\":\"usd\"")));
        for (Map.Entry<String, String> body : bodies) {
            perform(BOTH, body.getValue())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(body.getKey()));
        }
        verify(service, never()).establish(any(), any());
    }

    @Test
    @DisplayName("a new opening is 201 and a replay 200, each with the stored result")
    void createdThenReplayed() throws Exception {
        when(service.establish(eq(ACCOUNT), any()))
                .thenReturn(new BankOpeningBalanceService.Outcome(response(false), false))
                .thenReturn(new BankOpeningBalanceService.Outcome(response(true), true));

        perform(BOTH, BODY)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountCode").value("1000"))
                .andExpect(jsonPath("$.bookBalance").value(9550.00))
                .andExpect(jsonPath("$.currencyCode").value("USD"))
                .andExpect(jsonPath("$.journalEntryNumber").value("JE-202510-1"))
                .andExpect(jsonPath("$.outstandingItems[0].reference").value("1043"))
                .andExpect(jsonPath("$.replayed").value(false));
        perform(BOTH, BODY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.journalEntryNumber").value("JE-202510-1"))
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    @DisplayName("each refusal keeps its status and code: 404, 409, 422 and the 400 the service raises")
    void refusalsMapToTheirStatus() throws Exception {
        Map<String, String> precision = new LinkedHashMap<>();
        precision.put("statementBalance", MinorUnit.detail("USD"));
        precision.put("outstandingItems[0].amount", MinorUnit.detail("USD"));
        List<Object[]> cases = List.of(
                new Object[] {new GLAccountNotFoundException("GL account not found"), 404, "GL_ACCOUNT_NOT_FOUND"},
                new Object[] {
                    cash(CashSetupException.Code.BANK_OPENING_BALANCE_ALREADY_ESTABLISHED),
                    409,
                    "BANK_OPENING_BALANCE_ALREADY_ESTABLISHED"
                },
                new Object[] {cash(CashSetupException.Code.IDEMPOTENCY_CONFLICT), 409, "IDEMPOTENCY_CONFLICT"},
                new Object[] {
                    cash(CashSetupException.Code.BANK_OPENING_BALANCE_ACCOUNT_NOT_ELIGIBLE),
                    422,
                    "BANK_OPENING_BALANCE_ACCOUNT_NOT_ELIGIBLE"
                },
                new Object[] {
                    cash(CashSetupException.Code.BANK_OPENING_BALANCE_NOT_FIRST), 422, "BANK_OPENING_BALANCE_NOT_FIRST"
                },
                new Object[] {
                    cash(CashSetupException.Code.BANK_OPENING_BALANCE_EMPTY), 422, "BANK_OPENING_BALANCE_EMPTY"
                },
                new Object[] {new CurrencyNotSupportedException("EUR"), 422, "CURRENCY_NOT_SUPPORTED"},
                new Object[] {MinorUnit.exceeded(precision), 422, "AMOUNT_PRECISION_EXCEEDS_CURRENCY"},
                new Object[] {
                    InvalidRequestParameterException.forField("asOfDate", "asOfDate is after today"),
                    400,
                    "VALIDATION_ERROR"
                });
        for (Object[] refusal : cases) {
            // doThrow: re-stubbing with when() would invoke the previous stub, which throws.
            doThrow((RuntimeException) refusal[0]).when(service).establish(eq(ACCOUNT), any());
            perform(BOTH, BODY)
                    .andExpect(status().is((Integer) refusal[1]))
                    .andExpect(jsonPath("$.code").value((String) refusal[2]));
        }
        doThrow(MinorUnit.exceeded(precision)).when(service).establish(eq(ACCOUNT), any());
        perform(BOTH, BODY)
                .andExpect(jsonPath("$.fieldErrors[0].field").value("statementBalance"))
                .andExpect(jsonPath("$.fieldErrors[1].field").value("outstandingItems[0].amount"));
    }

    private ResultActions perform(String authorities, String body) throws Exception {
        return mockMvc.perform(post(OPENING)
                .header("X-User", TEST_USER)
                .header("X-Authorities", authorities)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static CashSetupException cash(CashSetupException.Code code) {
        return new CashSetupException(code, "refused");
    }

    private static BankOpeningBalanceResponse response(boolean replayed) {
        return new BankOpeningBalanceResponse(
                ACCOUNT,
                "1000",
                LocalDate.of(2025, 10, 31),
                new BigDecimal("10000.00"),
                new BigDecimal("9550.00"),
                "USD",
                List.of(new BankOpeningBalanceResponse.Item(
                        BankOpeningItemType.OUTSTANDING_CHECK,
                        "1043",
                        LocalDate.of(2025, 10, 28),
                        new BigDecimal("450.00"),
                        UUID.fromString("019a0000-0000-7000-8000-00000000c001"))),
                UUID.fromString("019a0000-0000-7000-8000-00000000d001"),
                "JE-202510-1",
                replayed);
    }
}
