package com.positivity.accounting.contract;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseContractIntegrationTest;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.service.JournalEntryService;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract behaviour of the bank reconciliation period-close surface (SPEC-manual-bank-reconciliation §5.2, §5.3,
 * §5.9; story S6, #2305): the readiness shape with its top-level {@code checks[]} and per-account {@code
 * baselineDate}, both new codes, the close body and response fields, the six-field policy body and its 400s — and
 * the EXISTING close codes as regressions.
 */
@DisplayName("Accounting period bank reconciliation close — contract behaviour (#2305)")
class AccountingPeriodBankRecContractBehaviorIT extends BaseContractIntegrationTest {

    private static final String PERIODS = "/v1/accounting/periods";
    private static final String POLICY = PERIODS + "/bank-reconciliation-policy";
    private static final String CLOSER = "accounting:period:close,accounting:period:view";
    private static final String OVERRIDER = "accounting:period:close,accounting:period:override,accounting:period:view";
    /** Keeps {@code null} map values (the module's mapper drops them), so an explicit null threshold is sent. */
    private static final JsonMapper PLAIN_JSON = JsonMapper.builder().build();

    private static final String EXCEPTION = """
            {"bankReconciliationException":{"justification":"Bank statement delayed; controller approved"}}
            """;

    @Autowired
    private GLAccountRepository glAccounts;

    @Autowired
    private JournalEntryService journalEntries;

    @Autowired
    private DataSource dataSource;

    private UUID cash;
    private UUID revenue;

    @BeforeEach
    void accounts() {
        String suffix = UUIDv7Generator.generate().toString().substring(24);
        cash = account("YC" + suffix, AccountType.ASSET, AccountSubtype.BANK_CASH, true);
        revenue = account("YR" + suffix, AccountType.REVENUE, null, false);
        clearPolicy();
    }

    @AfterEach
    void cleanUp() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        clearPolicy();
        jdbc.update("DELETE FROM accounting_period WHERE period_code LIKE '2017-%'");
        for (UUID account : List.of(cash, revenue)) {
            List<UUID> entries = jdbc.queryForList(
                    "SELECT DISTINCT journal_entry_id FROM journal_entry_line WHERE gl_account_id = ?",
                    UUID.class,
                    account);
            for (UUID entry : entries) {
                jdbc.update("DELETE FROM journal_entry_line WHERE journal_entry_id = ?", entry);
                jdbc.update("DELETE FROM journal_entry WHERE journal_entry_id = ?", entry);
            }
        }
        jdbc.update("DELETE FROM gl_account WHERE gl_account_id IN (?, ?)", cash, revenue);
    }

    private void clearPolicy() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM accounting_configuration WHERE config_key LIKE 'BANK_REC_%'");
        // GET's updatedAt / updatedBy come from the latest BANK_REC_POLICY_SET audit row.
        jdbc.update("DELETE FROM accounting_audit_log WHERE operation = 'BANK_REC_POLICY_SET'");
    }

    private UUID account(String code, AccountType type, AccountSubtype subtype, boolean reconcilable) {
        GLAccount account = new GLAccount();
        account.setGlAccountId(UUIDv7Generator.generate());
        account.setAccountCode(code);
        account.setAccountName("Close " + code);
        account.setAccountType(type);
        account.setAccountSubtype(subtype);
        account.setReconcilable(reconcilable);
        account.setActivationDate(LocalDateTime.of(2015, 1, 1, 0, 0));
        account.setCreatedBy(TEST_USER);
        account.setModifiedBy(TEST_USER);
        return glAccounts.save(account).getGlAccountId();
    }

    private static ResultActions expectError(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    private ResultActions close(String month, String authorities, String body) throws Exception {
        var request = withAuth(post(PERIODS + "/{periodCode}/close", month), authorities);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    private ResultActions putPolicy(Map<String, Object> body) throws Exception {
        return mockMvc.perform(withAuth(put(POLICY), "accounting:period:hard_lock,accounting:period:view")
                .contentType(MediaType.APPLICATION_JSON)
                .content(PLAIN_JSON.writeValueAsString(body)));
    }

    private static Map<String, Object> policyBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("closePolicy", "REQUIRED_WITH_EXCEPTION");
        body.put("closeScope", "BANK_CASH_SUBTYPE");
        body.put("closeCoverageLagDays", 31);
        body.put("allowSelfApproval", false);
        body.put("otherApprovalThreshold", null);
        body.put("justification", "Bank statements end mid-month here");
        return body;
    }

    @Nested
    @DisplayName("GET /{periodCode}/close-readiness")
    class Readiness {

        @Test
        @DisplayName("the read model: policy, counts, top-level checks[] and accounts[] with baselineDate")
        void shape() throws Exception {
            mockMvc.perform(withAuth(get(PERIODS + "/2017-02/close-readiness"), "accounting:period:view"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.periodCode").value("2017-02"))
                    .andExpect(jsonPath("$.periodStatus").value("OPEN"))
                    .andExpect(jsonPath("$.policy").value("REQUIRED_WITH_EXCEPTION"))
                    .andExpect(jsonPath("$.ready").value(false))
                    .andExpect(jsonPath("$.blockingCount").isNumber())
                    .andExpect(jsonPath("$.warningCount").isNumber())
                    .andExpect(jsonPath("$.checks").isArray())
                    .andExpect(jsonPath("$.accounts[?(@.glAccountId == '" + cash + "')].baselineDate")
                            .value(hasItem((Object) null)))
                    .andExpect(jsonPath("$.accounts[?(@.glAccountId == '" + cash + "')].checks[0].code")
                            .value(hasItem("STATEMENT_COVERAGE")))
                    .andExpect(jsonPath("$.accounts[?(@.glAccountId == '" + cash + "')].checks[0].severity")
                            .value(hasItem("BLOCKING")));
        }

        @Test
        @DisplayName("400 VALIDATION_ERROR for a malformed period code; 403 without accounting:period:view")
        void refusals() throws Exception {
            expectError(
                    mockMvc.perform(withAuth(get(PERIODS + "/2017-13/close-readiness"), "accounting:period:view")),
                    400,
                    "VALIDATION_ERROR");
            mockMvc.perform(withAuth(get(PERIODS + "/2017-02/close-readiness"), "accounting:je:view"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("POST /{periodCode}/close")
    class Close {

        @Test
        @DisplayName("422 PERIOD_BANK_RECONCILIATION_INCOMPLETE lists the unreconciled account")
        void incomplete() throws Exception {
            expectError(close("2017-03", CLOSER, null), 422, "PERIOD_BANK_RECONCILIATION_INCOMPLETE")
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'unreconciledGlAccountIds')].message")
                            .value(hasItem(startsWith(cash.toString()))));
        }

        @Test
        @DisplayName("403 PERIOD_CLOSE_EXCEPTION_NOT_PERMITTED without override; with it 200 and the new fields")
        void exception() throws Exception {
            expectError(close("2017-04", CLOSER, EXCEPTION), 403, "PERIOD_CLOSE_EXCEPTION_NOT_PERMITTED");

            close("2017-04", OVERRIDER, EXCEPTION)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CLOSED"))
                    .andExpect(jsonPath("$.bankReconciliationReady").value(false))
                    .andExpect(jsonPath("$.bankReconciliationException").value(true));
        }

        @Test
        @DisplayName("400 JUSTIFICATION_REQUIRED for a short exception justification")
        void shortJustification() throws Exception {
            expectError(
                    close("2017-05", OVERRIDER, "{\"bankReconciliationException\":{\"justification\":\"late\"}}"),
                    400,
                    "JUSTIFICATION_REQUIRED");
        }

        @Test
        @DisplayName("422 under REQUIRED: the exception is reported as not permitted by policy")
        void requiredRefusesException() throws Exception {
            Map<String, Object> body = policyBody();
            body.put("closePolicy", "REQUIRED");
            putPolicy(body).andExpect(status().isOk());

            expectError(close("2017-06", OVERRIDER, EXCEPTION), 422, "PERIOD_BANK_RECONCILIATION_INCOMPLETE")
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'bankReconciliationException')].message")
                            .value(hasItem("not permitted by policy REQUIRED")));
        }

        @Test
        @DisplayName("regressions: PERIOD_ALREADY_CLOSED, PERIOD_NOT_FOUND, PERIOD_HAS_DRAFT_ENTRIES")
        void existingCodes() throws Exception {
            Map<String, Object> body = policyBody();
            body.put("closePolicy", "ADVISORY");
            putPolicy(body).andExpect(status().isOk());

            close("2017-07", CLOSER, null).andExpect(status().isOk());
            expectError(close("2017-07", CLOSER, null), 409, "PERIOD_ALREADY_CLOSED");
            expectError(close("2099-01", CLOSER, null), 404, "PERIOD_NOT_FOUND");

            BigDecimal amount = new BigDecimal("10.00");
            journalEntries.createJournalEntry(JournalEntryCreateRequest.builder()
                    .transactionDate(LocalDateTime.of(2017, 8, 5, 12, 0))
                    .sourceEventId(UUIDv7Generator.generate())
                    .description("Close contract draft")
                    .lines(List.of(
                            JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                    .glAccountId(cash)
                                    .debitAmount(amount)
                                    .creditAmount(BigDecimal.ZERO)
                                    .build(),
                            JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                    .glAccountId(revenue)
                                    .debitAmount(BigDecimal.ZERO)
                                    .creditAmount(amount)
                                    .build()))
                    .build());
            expectError(close("2017-08", CLOSER, null), 422, "PERIOD_HAS_DRAFT_ENTRIES")
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("draftJournalEntryIds"));
        }
    }

    @Nested
    @DisplayName("GET|PUT /bank-reconciliation-policy")
    class Policy {

        @Test
        @DisplayName("GET returns the five effective values, the threshold explicitly null while unset")
        void getDefaults() throws Exception {
            mockMvc.perform(withAuth(get(POLICY), "accounting:period:view"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.closePolicy").value("REQUIRED_WITH_EXCEPTION"))
                    .andExpect(jsonPath("$.closeScope").value("BANK_CASH_SUBTYPE"))
                    .andExpect(jsonPath("$.closeCoverageLagDays").value(0))
                    .andExpect(jsonPath("$.allowSelfApproval").value(false))
                    .andExpect(jsonPath("$.otherApprovalThreshold").isEmpty())
                    .andExpect(jsonPath("$.updatedAt").isEmpty());
        }

        @Test
        @DisplayName("PUT stores the policy; the next readiness read uses it")
        void putStores() throws Exception {
            Map<String, Object> body = policyBody();
            body.put("otherApprovalThreshold", 250);
            putPolicy(body)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.closeCoverageLagDays").value(31))
                    .andExpect(jsonPath("$.otherApprovalThreshold").value(250.00))
                    .andExpect(jsonPath("$.updatedBy").value(TEST_USER));

            mockMvc.perform(withAuth(get(PERIODS + "/2017-02/close-readiness"), "accounting:period:view"))
                    .andExpect(jsonPath("$.coverageLagDays").value(31));
        }

        @Test
        @DisplayName("400 VALIDATION_ERROR: a missing field, an absent threshold, an unknown value, a negative lag")
        void validationErrors() throws Exception {
            Map<String, Object> missing = policyBody();
            missing.remove("closeScope");
            expectError(putPolicy(missing), 400, "VALIDATION_ERROR");

            Map<String, Object> noThreshold = policyBody();
            noThreshold.remove("otherApprovalThreshold");
            expectError(putPolicy(noThreshold), 400, "VALIDATION_ERROR");

            Map<String, Object> unknown = policyBody();
            unknown.put("closePolicy", "SOMETIMES");
            expectError(putPolicy(unknown), 400, "VALIDATION_ERROR");

            Map<String, Object> negative = policyBody();
            negative.put("closeCoverageLagDays", -1);
            expectError(putPolicy(negative), 400, "VALIDATION_ERROR");

            Map<String, Object> negativeThreshold = policyBody();
            negativeThreshold.put("otherApprovalThreshold", -5);
            expectError(putPolicy(negativeThreshold), 400, "VALIDATION_ERROR");

            Map<String, Object> blank = policyBody();
            blank.put("justification", "   ");
            expectError(putPolicy(blank), 400, "VALIDATION_ERROR");
        }

        @Test
        @DisplayName("400 JUSTIFICATION_REQUIRED for 1-9 characters; 403 for a view-only caller")
        void justificationAndAuthority() throws Exception {
            Map<String, Object> shortWhy = policyBody();
            shortWhy.put("justification", "too short");
            expectError(putPolicy(shortWhy), 400, "JUSTIFICATION_REQUIRED");

            mockMvc.perform(withAuth(put(POLICY), "accounting:period:view")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(PLAIN_JSON.writeValueAsString(policyBody())))
                    .andExpect(status().isForbidden());
        }
    }
}
