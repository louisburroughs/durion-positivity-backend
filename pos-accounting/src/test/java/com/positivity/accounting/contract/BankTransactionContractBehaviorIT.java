package com.positivity.accounting.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseContractIntegrationTest;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.repository.BankAccountProfileRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Contract behaviour of the bank transaction endpoints (SPEC-manual-bank-reconciliation §3.8, §4.5,
 * §4.10, §6.1, §8.2, §8.4; story S2, #2301): duplicate flagging and review, exclude and restore with
 * their {@code RECONCILIATION_LINE_INELIGIBLE} and {@code OPTIMISTIC_LOCK} refusals, the served enum
 * values, and a 403 per endpoint — exclude and restore need {@code accounting:reconciliation:approve}.
 */
@DisplayName("Bank transactions — contract behaviour (#2301)")
class BankTransactionContractBehaviorIT extends BaseContractIntegrationTest {

    private static final String STATEMENTS = "/v1/accounting/bank-statements";
    private static final String TRANSACTIONS = "/v1/accounting/bank-transactions";
    private static final String ACK = "First statement reconciled on this account";
    private static final String WHY = "Two separate monthly fees charged on the same day";

    @Autowired
    private GLAccountRepository glAccounts;

    @Autowired
    private BankStatementRepository statements;

    @Autowired
    private BankTransactionRepository transactions;

    @Autowired
    private BankAccountProfileRepository profiles;

    @Autowired
    private BankReconciliationRepository reconciliations;

    @Autowired
    private AccountingAuditLogRepository auditLogs;

    private UUID cash;
    private final List<UUID> reconciliationsCreated = new ArrayList<>();

    @BeforeEach
    void account() {
        String suffix = UUIDv7Generator.generate().toString().substring(24);
        GLAccount account = new GLAccount();
        account.setGlAccountId(UUIDv7Generator.generate());
        account.setAccountCode("T" + suffix);
        account.setAccountName("Cash " + suffix);
        account.setAccountType(AccountType.ASSET);
        account.setAccountSubtype(AccountSubtype.BANK_CASH);
        account.setReconcilable(true);
        account.setActivationDate(LocalDateTime.of(2020, 1, 1, 0, 0));
        account.setCreatedBy(TEST_USER);
        account.setModifiedBy(TEST_USER);
        cash = glAccounts.save(account).getGlAccountId();
    }

    @AfterEach
    void cleanUp() {
        reconciliationsCreated.forEach(reconciliations::deleteById);
        reconciliationsCreated.clear();
        transactions.findAll().stream()
                .filter(t -> cash.equals(t.getGlAccountId()))
                .forEach(transactions::delete);
        statements.findAll().stream()
                .filter(s -> cash.equals(s.getGlAccountId()))
                .forEach(statements::delete);
        profiles.findById(cash).ifPresent(profiles::delete);
        glAccounts.deleteById(cash);
    }

    /** A September statement with two identical $5.00 fees and one deposit; returns the statement id. */
    private UUID statementWithTwinFees() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("glAccountId", cash.toString());
        body.put("requestId", UUIDv7Generator.generate().toString());
        body.put(
                "statement",
                Map.of(
                        "startDate", "2025-09-01",
                        "endDate", "2025-09-30",
                        "openingBalance", "1000.00",
                        "closingBalance", "1490.00"));
        body.put(
                "transactions",
                List.of(
                        Map.of("date", "2025-09-02", "credit", "500.00", "description", "ACH DEPOSIT"),
                        Map.of("date", "2025-09-10", "debit", "5.00", "description", "Monthly fee", "reference", "F1"),
                        Map.of(
                                "date",
                                "2025-09-10",
                                "debit",
                                "5.00",
                                "description",
                                "MONTHLY FEE.",
                                "reference",
                                "F1")));
        body.put("gapAcknowledgement", ACK);
        String response = mockMvc.perform(withAuth(post(STATEMENTS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.possibleDuplicateCount").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(
                objectMapper.readTree(response).get("statementId").asString());
    }

    private List<BankTransaction> rows() {
        return transactions.findAll().stream()
                .filter(t -> cash.equals(t.getGlAccountId()))
                .sorted(java.util.Comparator.comparing(BankTransaction::getSourceRowNumber))
                .toList();
    }

    private ResultActions postJson(String path, Object body, String authorities) throws Exception {
        MockHttpServletRequestBuilder request =
                post(path).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(authorities == null ? withAuth(request) : withAuth(request, authorities));
    }

    private static ResultActions expectError(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void twinFeesAreBothFlaggedAndTheSecondPointsAtTheFirst() throws Exception {
        statementWithTwinFees();
        List<BankTransaction> rows = rows();
        BankTransaction first = rows.get(1);
        BankTransaction second = rows.get(2);

        mockMvc.perform(withAuth(get(TRANSACTIONS))
                        .param("glAccountId", cash.toString())
                        .param("unexplainedOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.transactions[0].status").value("UNMATCHED"))
                .andExpect(jsonPath("$.transactions[1].status").value("POSSIBLE_DUPLICATE"))
                .andExpect(jsonPath("$.transactions[1].settlementState").value("POSTED"))
                .andExpect(jsonPath("$.transactions[1].sourceKind").value("MANUAL_ENTRY"))
                .andExpect(jsonPath("$.transactions[1].feedChange").value("ADDED"))
                .andExpect(jsonPath("$.transactions[2].duplicateOfBankTransactionId")
                        .value(first.getBankTransactionId().toString()));
        assertThat(second.getSignedAmount()).isEqualByComparingTo("-5.00");

        mockMvc.perform(withAuth(get(TRANSACTIONS + "/" + second.getBankTransactionId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signedAmount").value(-5.0))
                .andExpect(jsonPath("$.normalizedDescription").value("MONTHLY FEE"))
                .andExpect(jsonPath("$.sourceRowNumber").value(3))
                .andExpect(jsonPath("$.accountCode").isNotEmpty());
    }

    @Test
    void reviewingBothDistinctReturnsBothToUnmatched() throws Exception {
        statementWithTwinFees();
        List<BankTransaction> rows = rows();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ids", List.of(rows.get(1).getBankTransactionId(), rows.get(2).getBankTransactionId()));
        body.put("decision", "DISTINCT");
        body.put("justification", WHY);

        postJson(TRANSACTIONS + "/duplicate-review", body, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions[0].status").value("UNMATCHED"))
                .andExpect(jsonPath("$.transactions[1].status").value("UNMATCHED"));
        assertThat(rows()).allMatch(t -> t.getStatus() == BankTransactionStatus.UNMATCHED);
    }

    @Test
    void aDuplicateIsExcludedAuditedAndLeavesTheUnexplainedList() throws Exception {
        statementWithTwinFees();
        BankTransaction first = rows().get(1);
        BankTransaction second = rows().get(2);

        postJson(
                        TRANSACTIONS + "/" + second.getBankTransactionId() + "/duplicate-review",
                        Map.of("decision", "DUPLICATE", "justification", WHY, "version", second.getVersion()),
                        null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXCLUDED"))
                .andExpect(jsonPath("$.duplicateOfBankTransactionId")
                        .value(first.getBankTransactionId().toString()))
                .andExpect(jsonPath("$.exclusionReason").value(WHY))
                .andExpect(jsonPath("$.excludedBy").value(TEST_USER));

        List<AccountingAuditLog> audit = auditLogs.findByEntityTypeAndEntityIdOrderByTimestampAsc(
                "BANK_TRANSACTION", second.getBankTransactionId());
        assertThat(audit).singleElement().satisfies(row -> {
            assertThat(row.getOperation()).isEqualTo("BANK_TRANSACTION_DUPLICATE_REVIEW");
            assertThat(row.getUserId()).isEqualTo(TEST_USER);
            assertThat(row.getJustification()).isEqualTo(WHY);
        });

        JsonNode unexplained = objectMapper.readTree(mockMvc.perform(withAuth(get(TRANSACTIONS))
                        .param("glAccountId", cash.toString())
                        .param("unexplainedOnly", "true"))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(unexplained
                        .get("transactions")
                        .valueStream()
                        .map(t -> t.get("bankTransactionId").asString())
                        .toList())
                .doesNotContain(second.getBankTransactionId().toString());
    }

    @Test
    void reviewingAnUnmatchedRowIsIneligibleAndAStaleVersionIsAConflict() throws Exception {
        statementWithTwinFees();
        BankTransaction deposit = rows().get(0);
        BankTransaction fee = rows().get(1);

        expectError(
                postJson(
                        TRANSACTIONS + "/" + deposit.getBankTransactionId() + "/duplicate-review",
                        Map.of("decision", "DISTINCT", "justification", WHY),
                        null),
                409,
                "RECONCILIATION_LINE_INELIGIBLE");
        expectError(
                postJson(
                        TRANSACTIONS + "/" + fee.getBankTransactionId() + "/duplicate-review",
                        Map.of("decision", "DISTINCT", "justification", WHY, "version", fee.getVersion() + 7),
                        null),
                409,
                "OPTIMISTIC_LOCK");
        expectError(
                postJson(
                        TRANSACTIONS + "/" + fee.getBankTransactionId() + "/duplicate-review",
                        Map.of("decision", "DISTINCT", "justification", "short"),
                        null),
                400,
                "JUSTIFICATION_REQUIRED");
        expectError(
                postJson(
                        TRANSACTIONS + "/" + fee.getBankTransactionId() + "/duplicate-review",
                        Map.of("decision", "DISTINCT", "justification", "  "),
                        null),
                400,
                "VALIDATION_ERROR");
    }

    @Test
    void excludeAndRestoreFollowTheStateMachine() throws Exception {
        statementWithTwinFees();
        BankTransaction deposit = rows().get(0);
        String id = deposit.getBankTransactionId().toString();

        postJson(TRANSACTIONS + "/" + id + "/exclude", Map.of("justification", WHY), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXCLUDED"));
        expectError(
                postJson(TRANSACTIONS + "/" + id + "/exclude", Map.of("justification", WHY), null),
                409,
                "RECONCILIATION_LINE_INELIGIBLE");
        postJson(TRANSACTIONS + "/" + id + "/restore", Map.of("justification", WHY), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNMATCHED"))
                .andExpect(jsonPath("$.exclusionReason").doesNotExist());
        expectError(
                postJson(TRANSACTIONS + "/" + id + "/restore", Map.of("justification", WHY), null),
                409,
                "RECONCILIATION_LINE_INELIGIBLE");
        assertThat(auditLogs.findByEntityTypeAndEntityIdOrderByTimestampAsc(
                        "BANK_TRANSACTION", deposit.getBankTransactionId()))
                .extracting(AccountingAuditLog::getOperation)
                .containsExactly("BANK_TRANSACTION_EXCLUDE", "BANK_TRANSACTION_RESTORE");
    }

    @Test
    void aMatchedRowCannotBeExcluded() throws Exception {
        statementWithTwinFees();
        BankTransaction deposit = rows().get(0);
        deposit.setStatus(BankTransactionStatus.MATCHED);
        transactions.save(deposit);

        expectError(
                postJson(
                        TRANSACTIONS + "/" + deposit.getBankTransactionId() + "/exclude",
                        Map.of("justification", WHY),
                        null),
                409,
                "RECONCILIATION_LINE_INELIGIBLE");
    }

    @Test
    void anExcludedRowInsideAFinalizedWindowCannotBeRestored() throws Exception {
        UUID statementId = statementWithTwinFees();
        BankTransaction deposit = rows().get(0);
        postJson(TRANSACTIONS + "/" + deposit.getBankTransactionId() + "/exclude", Map.of("justification", WHY), null)
                .andExpect(status().isOk());

        BankReconciliation finalized = new BankReconciliation();
        finalized.setGlAccountId(cash);
        finalized.setStatementId(statementId);
        finalized.setStatementStartDate(LocalDate.of(2025, 9, 1));
        finalized.setStatementEndDate(LocalDate.of(2025, 9, 30));
        finalized.setCurrency("USD");
        finalized.setStatementClosingBalance(new BigDecimal("1490.00"));
        finalized.setGlEndingBalance(new BigDecimal("1490.00"));
        finalized.setDifference(BigDecimal.ZERO);
        finalized.setStatus(ReconciliationStatus.FINALIZED);
        reconciliationsCreated.add(reconciliations.save(finalized).getReconciliationId());

        expectError(
                postJson(
                        TRANSACTIONS + "/" + deposit.getBankTransactionId() + "/restore",
                        Map.of("justification", WHY),
                        null),
                409,
                "RECONCILIATION_LINE_INELIGIBLE");
    }

    @Test
    void theListNeedsAnAccountAndAnUnknownRowIs404() throws Exception {
        expectError(mockMvc.perform(withAuth(get(TRANSACTIONS))), 400, "VALIDATION_ERROR")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("glAccountId"));
        expectError(
                mockMvc.perform(withAuth(get(TRANSACTIONS + "/" + UUIDv7Generator.generate()))),
                404,
                "BANK_TRANSACTION_NOT_FOUND");
    }

    @Test
    @DisplayName("every endpoint answers 403 without its permission; exclude and restore need approve")
    void everyEndpointIs403WithoutItsPermission() throws Exception {
        UUID any = UUIDv7Generator.generate();
        String viewOnly = "accounting:reconciliation:view";
        String adjustOnly = "accounting:reconciliation:view,accounting:reconciliation:adjust";

        expectError(mockMvc.perform(withAuth(get(TRANSACTIONS), "accounting:je:view")), 403, "FORBIDDEN");
        expectError(mockMvc.perform(withAuth(get(TRANSACTIONS + "/" + any), "accounting:je:view")), 403, "FORBIDDEN");
        expectError(
                postJson(TRANSACTIONS + "/" + any + "/duplicate-review", Map.of("decision", "DISTINCT"), viewOnly),
                403,
                "FORBIDDEN");
        expectError(
                postJson(TRANSACTIONS + "/duplicate-review", Map.of("decision", "DISTINCT"), viewOnly),
                403,
                "FORBIDDEN");
        expectError(
                postJson(TRANSACTIONS + "/" + any + "/exclude", Map.of("justification", WHY), adjustOnly),
                403,
                "FORBIDDEN");
        expectError(
                postJson(TRANSACTIONS + "/" + any + "/restore", Map.of("justification", WHY), adjustOnly),
                403,
                "FORBIDDEN");
    }
}
