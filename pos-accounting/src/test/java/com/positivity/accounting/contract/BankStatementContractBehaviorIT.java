package com.positivity.accounting.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseContractIntegrationTest;
import com.positivity.accounting.internal.bankrec.repository.BankAccountProfileRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Contract behaviour of the bank statement and bank account endpoints (SPEC-manual-bank-reconciliation
 * §4.1–§4.4, §4.10, §6.1, §8.2, §8.4; story S2, #2301): every new code with its status and envelope
 * ({@code fieldErrors[openingBalance]}, {@code fieldErrors[statementId]}), the enum values served, the
 * baseline on the bank-account read, and a 403 per endpoint.
 */
@DisplayName("Bank statements and accounts — contract behaviour (#2301)")
class BankStatementContractBehaviorIT extends BaseContractIntegrationTest {

    private static final String STATEMENTS = "/v1/accounting/bank-statements";
    private static final String ACCOUNTS = "/v1/accounting/bank-accounts";
    private static final String ACK = "First statement reconciled on this account";

    @Autowired
    private GLAccountRepository glAccounts;

    @Autowired
    private BankStatementRepository statements;

    @Autowired
    private BankTransactionRepository transactions;

    @Autowired
    private BankAccountProfileRepository profiles;

    @Autowired
    private AccountingAuditLogRepository auditLogs;

    private UUID cash;
    private UUID undeposited;
    private UUID revenue;
    private final List<UUID> accountsCreated = new ArrayList<>();

    @BeforeEach
    void accounts() {
        String suffix = UUIDv7Generator.generate().toString().substring(24);
        cash = account("C" + suffix, "Cash " + suffix, AccountType.ASSET, AccountSubtype.BANK_CASH, true);
        undeposited = account(
                "U" + suffix, "Undeposited " + suffix, AccountType.ASSET, AccountSubtype.UNDEPOSITED_FUNDS, true);
        revenue = account("R" + suffix, "Revenue " + suffix, AccountType.REVENUE, null, false);
    }

    @AfterEach
    void cleanUp() {
        for (UUID id : accountsCreated) {
            transactions.findAll().stream()
                    .filter(t -> id.equals(t.getGlAccountId()))
                    .forEach(transactions::delete);
            statements.findAll().stream()
                    .filter(s -> id.equals(s.getGlAccountId()))
                    .forEach(statements::delete);
            profiles.findById(id).ifPresent(profiles::delete);
            glAccounts.deleteById(id);
        }
        accountsCreated.clear();
    }

    private UUID account(String code, String name, AccountType type, AccountSubtype subtype, boolean reconcilable) {
        GLAccount account = new GLAccount();
        account.setGlAccountId(UUIDv7Generator.generate());
        account.setAccountCode(code);
        account.setAccountName(name);
        account.setAccountType(type);
        account.setAccountSubtype(subtype);
        account.setReconcilable(reconcilable);
        account.setActivationDate(LocalDateTime.of(2020, 1, 1, 0, 0));
        account.setCreatedBy(TEST_USER);
        account.setModifiedBy(TEST_USER);
        UUID id = glAccounts.save(account).getGlAccountId();
        accountsCreated.add(id);
        return id;
    }

    // ---- request fixtures ------------------------------------------------------------------------

    static Map<String, Object> transaction(String date, String signedAmount, String description) {
        return Map.of("date", date, "signedAmount", signedAmount, "description", description);
    }

    private Map<String, Object> statement(
            UUID account,
            String start,
            String end,
            String opening,
            String closing,
            List<Map<String, Object>> rows,
            String ack) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("glAccountId", account.toString());
        body.put("requestId", UUIDv7Generator.generate().toString());
        body.put(
                "statement",
                Map.of("startDate", start, "endDate", end, "openingBalance", opening, "closingBalance", closing));
        body.put("transactions", rows);
        if (ack != null) {
            body.put("gapAcknowledgement", ack);
        }
        return body;
    }

    private Map<String, Object> september(UUID account, String ack) {
        return statement(
                account,
                "2025-09-01",
                "2025-09-30",
                "12000.00",
                "12345.67",
                List.of(
                        transaction("2025-09-02", "500.00", "ACH DEPOSIT"),
                        transaction("2025-09-15", "-154.33", "FEE")),
                ack);
    }

    private ResultActions postStatement(Map<String, Object> body) throws Exception {
        return mockMvc.perform(withAuth(post(STATEMENTS))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static ResultActions expectError(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    // ---- manual entry --------------------------------------------------------------------------

    @Nested
    @DisplayName("POST /bank-statements")
    class Create {

        @Test
        void theFirstStatementWithoutAnAcknowledgementIs422NotContiguous() throws Exception {
            expectError(postStatement(september(cash, null)), 422, "STATEMENT_NOT_CONTIGUOUS")
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("gapAcknowledgement"));
        }

        @Test
        void theFirstStatementWithAnAcknowledgementCommitsAndSetsTheBaseline() throws Exception {
            JsonNode body = json(postStatement(september(cash, ACK))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("COMMITTED"))
                    .andExpect(jsonPath("$.sourceKind").value("MANUAL_ENTRY"))
                    .andExpect(jsonPath("$.currency").value("USD"))
                    .andExpect(jsonPath("$.gapAcknowledgement").value(ACK))
                    .andExpect(jsonPath("$.gapAcknowledgedBy").value(TEST_USER))
                    .andExpect(jsonPath("$.bankTransactionCount").value(2))
                    .andExpect(jsonPath("$.possibleDuplicateCount").value(0))
                    .andExpect(jsonPath("$.replayed").value(false)));
            UUID statementId = UUID.fromString(body.get("statementId").asString());

            List<AccountingAuditLog> baseline =
                    auditLogs.findByEntityTypeAndEntityIdOrderByTimestampAsc("BANK_ACCOUNT_PROFILE", cash);
            assertThat(baseline).singleElement().satisfies(row -> {
                assertThat(row.getOperation()).isEqualTo("BANK_ACCOUNT_BASELINE_SET");
                assertThat(row.getOldValue()).isNull();
                assertThat(row.getNewValue()).isEqualTo("2025-09-01");
                assertThat(row.getJustification()).isEqualTo(ACK);
                assertThat(row.getUserId()).isEqualTo(TEST_USER);
            });
            assertThat(auditLogs.findByEntityTypeAndEntityIdOrderByTimestampAsc("BANK_STATEMENT", statementId))
                    .extracting(AccountingAuditLog::getOperation)
                    .containsExactly("BANK_STATEMENT_CREATE");

            mockMvc.perform(withAuth(get(STATEMENTS + "/" + statementId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statementId").value(statementId.toString()))
                    .andExpect(jsonPath("$.gapAcknowledgement").value(ACK))
                    .andExpect(jsonPath("$.reconciliations").isArray());
        }

        @Test
        void aReplayReturnsTheOriginalAndADifferentPayloadIsAConflict() throws Exception {
            Map<String, Object> body = september(cash, ACK);
            String first = json(postStatement(body).andExpect(status().isCreated()))
                    .get("statementId")
                    .asString();
            postStatement(body)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.statementId").value(first))
                    .andExpect(jsonPath("$.replayed").value(true));

            Map<String, Object> changed = new java.util.LinkedHashMap<>(body);
            changed.put("gapAcknowledgement", ACK + " (changed)");
            expectError(postStatement(changed), 409, "IDEMPOTENCY_CONFLICT");
        }

        @Test
        void theSameWindowIs409AndAnOverlapIs422NamingTheStatement() throws Exception {
            String first =
                    json(postStatement(september(cash, ACK))).get("statementId").asString();

            expectError(postStatement(september(cash, ACK)), 409, "STATEMENT_ALREADY_IMPORTED");
            expectError(
                            postStatement(statement(
                                    cash,
                                    "2025-09-15",
                                    "2025-10-14",
                                    "0",
                                    "10",
                                    List.of(transaction("2025-09-20", "10", "DEP")),
                                    ACK)),
                            422,
                            "STATEMENT_PERIOD_OVERLAP")
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("statementId"))
                    .andExpect(jsonPath("$.fieldErrors[0].message").value(first));
        }

        @Test
        void aDiscontinuousOpeningBalanceNamesTheExpectedValue() throws Exception {
            postStatement(september(cash, ACK)).andExpect(status().isCreated());
            expectError(
                            postStatement(statement(
                                    cash,
                                    "2025-10-01",
                                    "2025-10-31",
                                    "12300.00",
                                    "12310.00",
                                    List.of(transaction("2025-10-02", "10", "DEP")),
                                    null)),
                            422,
                            "STATEMENT_NOT_CONTIGUOUS")
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("openingBalance"))
                    .andExpect(jsonPath("$.fieldErrors[0].message").value("expected 12345.67"));
        }

        @Test
        void theAcknowledgementRulesOnAContiguousStatement() throws Exception {
            postStatement(september(cash, ACK)).andExpect(status().isCreated());
            List<Map<String, Object>> rows = List.of(transaction("2025-10-02", "10", "DEP"));

            expectError(
                    postStatement(statement(cash, "2025-10-01", "2025-10-31", "12345.67", "12355.67", rows, "short")),
                    400,
                    "JUSTIFICATION_REQUIRED");
            expectError(
                    postStatement(statement(cash, "2025-10-01", "2025-10-31", "12345.67", "12355.67", rows, "   ")),
                    400,
                    "VALIDATION_ERROR");
            expectError(
                    postStatement(statement(cash, "2025-10-01", "2025-10-31", "12345.67", "12355.67", rows, ACK)),
                    422,
                    "STATEMENT_GAP_ACKNOWLEDGEMENT_NOT_APPLICABLE");

            postStatement(statement(cash, "2025-10-01", "2025-10-31", "12345.67", "12355.67", rows, null))
                    .andExpect(status().isCreated());
            mockMvc.perform(withAuth(get(ACCOUNTS)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accounts[?(@.glAccountId=='" + cash + "')].reconciliationBaselineDate")
                            .value("2025-09-01"))
                    .andExpect(jsonPath("$.accounts[?(@.glAccountId=='" + cash + "')].coverageFrontier")
                            .value("2025-10-31"));
            assertThat(auditLogs.findByEntityTypeAndEntityIdOrderByTimestampAsc("BANK_ACCOUNT_PROFILE", cash))
                    .as("the contiguous statement wrote no baseline row")
                    .hasSize(1);
        }

        @Test
        void aManualTransactionOutsideTheWindowIsNamedByIndex() throws Exception {
            expectError(
                            postStatement(statement(
                                    cash,
                                    "2025-10-01",
                                    "2025-10-31",
                                    "0",
                                    "10",
                                    List.of(
                                            transaction("2025-10-02", "15", "DEP"),
                                            transaction("2025-09-28", "-5", "FEE")),
                                    ACK)),
                            422,
                            "STATEMENT_TRANSACTION_OUT_OF_WINDOW")
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("transactions[1]"));
        }

        @Test
        void rowsThatDoNotTieToTheClosingBalanceAreRefusedAndNothingPersists() throws Exception {
            expectError(
                            postStatement(statement(
                                    cash,
                                    "2025-09-01",
                                    "2025-09-30",
                                    "10000.00",
                                    "10000.00",
                                    List.of(transaction("2025-09-02", "-15.00", "FEE")),
                                    ACK)),
                            422,
                            "STATEMENT_ACTIVITY_MISMATCH")
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("activityTotal"))
                    .andExpect(jsonPath("$.fieldErrors[0].message")
                            .value("opening + activity = 9985.00, closing = 10000.00"));
            mockMvc.perform(withAuth(get(STATEMENTS).param("glAccountId", cash.toString())))
                    .andExpect(jsonPath("$.totalElements").value(0));
            assertThat(profiles.findById(cash)).as("no profile either").isEmpty();
        }

        @Test
        void aForeignCurrencyIs422() throws Exception {
            Map<String, Object> body = september(cash, ACK);
            body.put("currency", "EUR");
            expectError(postStatement(body), 422, "CURRENCY_NOT_SUPPORTED");
        }

        @Test
        void anAccountThatIsNotABankCashAccountIs422() throws Exception {
            expectError(postStatement(september(undeposited, ACK)), 422, "ACCOUNT_NOT_RECONCILABLE");
            expectError(postStatement(september(revenue, ACK)), 422, "ACCOUNT_NOT_RECONCILABLE");
        }

        @Test
        void aMalformedRequestIs400NamingTheFields() throws Exception {
            Map<String, Object> body = september(cash, ACK);
            body.put("transactions", List.of(Map.of("date", "2025-09-02", "description", "NO AMOUNT")));
            expectError(postStatement(body), 400, "VALIDATION_ERROR")
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("transactions[0]"));
        }
    }

    @Test
    void anUnknownStatementIs404() throws Exception {
        expectError(
                mockMvc.perform(withAuth(get(STATEMENTS + "/" + UUIDv7Generator.generate()))),
                404,
                "BANK_STATEMENT_NOT_FOUND");
    }

    // ---- bank accounts and profile ---------------------------------------------------------------

    @Nested
    @DisplayName("GET /bank-accounts and PUT /bank-accounts/{id}/profile")
    class Accounts {

        @Test
        void bankCashAccountsAreListedAndOtherSubtypesAreNot() throws Exception {
            mockMvc.perform(withAuth(get(ACCOUNTS).param("size", "200")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accounts[?(@.glAccountId=='" + cash + "')].feedLinkState")
                            .value("NONE"))
                    .andExpect(jsonPath("$.accounts[?(@.glAccountId=='" + cash + "')].profileExists")
                            .value(false))
                    .andExpect(jsonPath("$.accounts[?(@.glAccountId=='" + undeposited + "')]")
                            .isEmpty())
                    .andExpect(jsonPath("$.accounts[?(@.glAccountId=='" + revenue + "')]")
                            .isEmpty());
        }

        @Test
        void theProfileTakesTheLedgerCurrencyOnlyAndNeverMovesTheBaseline() throws Exception {
            postStatement(september(cash, ACK)).andExpect(status().isCreated());

            expectError(
                    mockMvc.perform(withAuth(put(ACCOUNTS + "/" + cash + "/profile"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currency\":\"CAD\",\"bankName\":\"North\"}")),
                    422,
                    "CURRENCY_NOT_SUPPORTED");

            mockMvc.perform(withAuth(put(ACCOUNTS + "/" + cash + "/profile"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currency\":\"USD\",\"bankName\":\"First National\",\"accountMask\":\"4321\","
                                    + "\"reconciliationBaselineDate\":\"2020-01-01\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bankName").value("First National"))
                    .andExpect(jsonPath("$.accountMask").value("4321"))
                    .andExpect(jsonPath("$.reconciliationBaselineDate").value("2025-09-01"));
            assertThat(profiles.findById(cash).orElseThrow().getReconciliationBaselineDate())
                    .hasToString("2025-09-01");
            assertThat(auditLogs.findByEntityTypeAndEntityIdOrderByTimestampAsc("BANK_ACCOUNT_PROFILE", cash))
                    .extracting(AccountingAuditLog::getOperation)
                    .containsExactly("BANK_ACCOUNT_BASELINE_SET", "BANK_ACCOUNT_PROFILE_SET");
        }

        @Test
        void aProfileForAnUnknownAccountIs404AndForANonBankAccount422() throws Exception {
            expectError(
                    mockMvc.perform(withAuth(put(ACCOUNTS + "/" + UUIDv7Generator.generate() + "/profile"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currency\":\"USD\"}")),
                    404,
                    "GL_ACCOUNT_NOT_FOUND");
            expectError(
                    mockMvc.perform(withAuth(put(ACCOUNTS + "/" + revenue + "/profile"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currency\":\"USD\"}")),
                    422,
                    "ACCOUNT_NOT_RECONCILABLE");
        }
    }

    // ---- 403 per endpoint --------------------------------------------------------------------------

    @Test
    @DisplayName("every endpoint answers 403 without its permission, revealing nothing")
    void everyEndpointIs403WithoutItsPermission() throws Exception {
        String unrelated = "accounting:je:view";
        List<MockHttpServletRequestBuilder> calls = List.of(
                post(STATEMENTS).contentType(MediaType.APPLICATION_JSON).content("{}"),
                get(STATEMENTS),
                get(STATEMENTS + "/" + UUIDv7Generator.generate()),
                get(ACCOUNTS),
                put(ACCOUNTS + "/" + cash + "/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"USD\"}"));
        for (MockHttpServletRequestBuilder call : calls) {
            expectError(mockMvc.perform(withAuth(call, unrelated)), 403, "FORBIDDEN");
        }
        // View does not grant entry or the profile.
        expectError(
                mockMvc.perform(withAuth(post(STATEMENTS), "accounting:reconciliation:view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")),
                403,
                "FORBIDDEN");
    }
}
