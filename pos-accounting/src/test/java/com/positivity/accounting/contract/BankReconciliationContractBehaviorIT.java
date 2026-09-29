package com.positivity.accounting.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseContractIntegrationTest;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import com.positivity.accounting.internal.service.JournalEntryService;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Contract behaviour of the reconciliation core (SPEC-manual-bank-reconciliation §4.10, §6.1, §8.3; story S4,
 * #2303): every new code with its status and envelope ({@code fieldErrors[justification]} listing the M5
 * reasons, {@code fieldErrors[reconciliationId]}, {@code fieldErrors[difference]}), the enums served, a 403 per
 * new endpoint, and a 404 for the replaced F2 {@code /match} and {@code /unmatch} routes. Postings here use
 * {@code TRANSFER}, which needs no mapping; the mapped {@code OTHER} paths run on Postgres
 * ({@code BankReconciliationWorkflowPostgresIT}).
 */
@DisplayName("Bank reconciliation core — contract behaviour (#2303)")
class BankReconciliationContractBehaviorIT extends BaseContractIntegrationTest {

    private static final String RECONCILIATIONS = "/v1/accounting/reconciliations";
    private static final String ACK = "First statement reconciled on this account";
    private static final String VIEW_ONLY = "accounting:reconciliation:view";
    private static final String ADJUST_ONLY = "accounting:reconciliation:view,accounting:reconciliation:adjust";
    private static final LocalDate DAY = LocalDate.of(2021, 9, 12);

    @Autowired
    private GLAccountRepository glAccounts;

    @Autowired
    private JournalEntryService journalEntries;

    @Autowired
    private JournalEntryLineRepository lines;

    @Autowired
    private DataSource dataSource;

    private UUID cash;
    private UUID otherBank;
    private UUID undeposited;
    private UUID revenue;
    private final List<UUID> accountsCreated = new ArrayList<>();

    @BeforeEach
    void accounts() {
        String suffix = UUIDv7Generator.generate().toString().substring(24);
        cash = account("ZC" + suffix, AccountType.ASSET, AccountSubtype.BANK_CASH, true);
        otherBank = account("ZB" + suffix, AccountType.ASSET, AccountSubtype.BANK_CASH, true);
        undeposited = account("ZU" + suffix, AccountType.ASSET, AccountSubtype.UNDEPOSITED_FUNDS, true);
        revenue = account("ZR" + suffix, AccountType.REVENUE, null, false);
    }

    @AfterEach
    void cleanUp() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (UUID account : accountsCreated) {
            String recons = "SELECT reconciliation_id FROM bank_reconciliation WHERE gl_account_id = '" + account + "'";
            jdbc.update("DELETE FROM bank_reconciliation_adjustment WHERE reconciliation_id IN (" + recons + ")");
            jdbc.update("DELETE FROM bank_reconciliation_outstanding_item WHERE gl_account_id = ?", account);
            jdbc.update("DELETE FROM bank_reconciliation_gl_match WHERE reconciliation_id IN (" + recons + ")");
            jdbc.update("DELETE FROM bank_reconciliation_bank_match WHERE match_id IN (SELECT match_id FROM"
                    + " bank_reconciliation_match WHERE reconciliation_id IN (" + recons + "))");
            jdbc.update("DELETE FROM bank_reconciliation_match WHERE reconciliation_id IN (" + recons + ")");
            jdbc.update("DELETE FROM bank_reconciliation WHERE gl_account_id = ?", account);
            jdbc.update("DELETE FROM bank_transaction WHERE gl_account_id = ?", account);
            jdbc.update("DELETE FROM bank_statement WHERE gl_account_id = ?", account);
            jdbc.update("DELETE FROM bank_account_profile WHERE gl_account_id = ?", account);
        }
        accountsCreated.clear();
    }

    private UUID account(String code, AccountType type, AccountSubtype subtype, boolean reconcilable) {
        GLAccount account = new GLAccount();
        account.setGlAccountId(UUIDv7Generator.generate());
        account.setAccountCode(code);
        account.setAccountName("Recon " + code);
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

    // ---- fixtures --------------------------------------------------------------------------------

    /** Posts Dr {@code debit} / Cr {@code credit} of {@code amount} on {@code day}; returns the debit account's line. */
    private UUID postEntry(UUID debit, UUID credit, String amount, LocalDate day) {
        BigDecimal value = new BigDecimal(amount);
        UUID entry = journalEntries
                .createJournalEntry(JournalEntryCreateRequest.builder()
                        .transactionDate(day.atTime(12, 0))
                        .sourceEventId(UUIDv7Generator.generate())
                        .description("Contract IT")
                        .lines(List.of(
                                JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                        .glAccountId(debit)
                                        .debitAmount(value)
                                        .creditAmount(BigDecimal.ZERO)
                                        .build(),
                                JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                        .glAccountId(credit)
                                        .debitAmount(BigDecimal.ZERO)
                                        .creditAmount(value)
                                        .build()))
                        .build())
                .getJournalEntryId();
        journalEntries.postJournalEntry(entry, null);
        return lines.findByJournalEntry_JournalEntryId(entry).stream()
                .filter(l -> debit.equals(l.getGlAccountId()))
                .map(JournalEntryLine::getLineId)
                .findFirst()
                .orElseThrow();
    }

    /** Commits a manual statement of September 2021 with the given row amounts; returns its id. */
    private UUID statement(UUID account, String opening, String closing, String... amounts) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        int n = 0;
        for (String amount : amounts) {
            rows.add(Map.of("date", DAY.toString(), "signedAmount", amount, "description", "ROW " + (++n)));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("glAccountId", account.toString());
        body.put("requestId", UUIDv7Generator.generate().toString());
        body.put(
                "statement",
                Map.of(
                        "startDate",
                        "2021-09-01",
                        "endDate",
                        "2021-09-30",
                        "openingBalance",
                        opening,
                        "closingBalance",
                        closing));
        body.put("transactions", rows);
        body.put("gapAcknowledgement", ACK);
        JsonNode created = json(mockMvc.perform(withAuth(post("/v1/accounting/bank-statements"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated()));
        return UUID.fromString(created.get("statementId").asString());
    }

    private List<UUID> bankRows(UUID account) throws Exception {
        JsonNode page = json(mockMvc.perform(
                        withAuth(get("/v1/accounting/bank-transactions").param("glAccountId", account.toString())))
                .andExpect(status().isOk()));
        List<UUID> ids = new ArrayList<>();
        page.get("transactions")
                .forEach(t -> ids.add(UUID.fromString(t.get("bankTransactionId").asString())));
        return ids;
    }

    private UUID reconcile(UUID account, UUID statementId) throws Exception {
        return UUID.fromString(
                json(create(account, statementId, UUIDv7Generator.generate()).andExpect(status().isCreated()))
                        .get("reconciliationId")
                        .asString());
    }

    private ResultActions create(UUID account, UUID statementId, UUID requestId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("glAccountId", account.toString());
        body.put("requestId", requestId.toString());
        if (statementId != null) {
            body.put("statementId", statementId.toString());
        }
        return postJson(RECONCILIATIONS, body);
    }

    private ResultActions postJson(String path, Object body) throws Exception {
        return postJson(path, body, null);
    }

    private ResultActions postJson(String path, Object body, String authorities) throws Exception {
        MockHttpServletRequestBuilder request =
                authorities == null ? withAuth(post(path)) : withAuth(post(path), authorities);
        return mockMvc.perform(
                request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private static Map<String, Object> match(List<UUID> bank, List<UUID> gl, String justification) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bankTransactionIds", bank.stream().map(UUID::toString).toList());
        body.put("glLineIds", gl.stream().map(UUID::toString).toList());
        body.put("requestId", UUIDv7Generator.generate().toString());
        if (justification != null) {
            body.put("justification", justification);
        }
        return body;
    }

    private static Map<String, Object> adjustment(String type, String amount, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        if (amount != null) {
            body.put("amount", amount);
        }
        body.put("requestId", UUIDv7Generator.generate().toString());
        body.putAll(extra);
        return body;
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static ResultActions expectError(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    // ---- create ----------------------------------------------------------------------------------

    @Nested
    @DisplayName("POST /reconciliations")
    class Create {

        @Test
        void aStatementIsReconciledOnceAndTheHeaderCarriesTheLiveEquation() throws Exception {
            postEntry(cash, revenue, "250.00", DAY);
            UUID statementId = statement(cash, "0", "250.00", "250.00");
            UUID requestId = UUIDv7Generator.generate();
            JsonNode header = json(create(cash, statementId, requestId)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.accountingPeriodCode").value("2021-09"))
                    .andExpect(jsonPath("$.baselineDate").value("2021-09-01"))
                    .andExpect(jsonPath("$.difference").value(0))
                    .andExpect(jsonPath("$.countUnexplainedBank").value(1))
                    .andExpect(jsonPath("$.countUnexplainedLedger").value(1))
                    .andExpect(jsonPath("$.statementLines").doesNotExist()));
            String reconciliationId = header.get("reconciliationId").asString();

            create(cash, statementId, requestId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.replayed").value(true))
                    .andExpect(jsonPath("$.reconciliationId").value(reconciliationId));
            expectError(create(cash, UUIDv7Generator.generate(), requestId), 409, "IDEMPOTENCY_CONFLICT");
            expectError(
                            create(cash, statementId, UUIDv7Generator.generate()),
                            409,
                            "RECONCILIATION_WINDOW_ALREADY_RECONCILED")
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("reconciliationId"))
                    .andExpect(jsonPath("$.fieldErrors[0].message").value(reconciliationId));
        }

        @Test
        void aStatementlessBodyIs422FeedNotLinkedAndANonBankAccount422NotReconcilable() throws Exception {
            Map<String, Object> interim = new LinkedHashMap<>();
            interim.put("glAccountId", cash.toString());
            interim.put("requestId", UUIDv7Generator.generate().toString());
            interim.put("windowStartDate", "2021-09-01");
            interim.put("windowEndDate", "2021-09-15");
            interim.put("closingBalance", "10.00");
            interim.put("openingBalance", "0.00");
            expectError(postJson(RECONCILIATIONS, interim), 422, "BANK_ACCOUNT_FEED_NOT_LINKED");
            expectError(
                    create(undeposited, UUIDv7Generator.generate(), UUIDv7Generator.generate()),
                    422,
                    "ACCOUNT_NOT_RECONCILABLE");
            expectError(
                    create(cash, UUIDv7Generator.generate(), UUIDv7Generator.generate()),
                    404,
                    "BANK_STATEMENT_NOT_FOUND");
        }

        @Test
        void aManualStatementWithStartReconciliationStartsOneInTheSameCommit() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("glAccountId", cash.toString());
            body.put("requestId", UUIDv7Generator.generate().toString());
            body.put(
                    "statement",
                    Map.of(
                            "startDate",
                            "2021-09-01",
                            "endDate",
                            "2021-09-30",
                            "openingBalance",
                            "0",
                            "closingBalance",
                            "25.00"));
            body.put(
                    "transactions",
                    List.of(Map.of("date", DAY.toString(), "signedAmount", "25.00", "description", "ROW")));
            body.put("gapAcknowledgement", ACK);
            body.put("startReconciliation", true);
            JsonNode created = json(postJson("/v1/accounting/bank-statements", body)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.reconciliations.length()").value(1))
                    .andExpect(jsonPath("$.reconciliations[0].status").value("IN_PROGRESS")));
            String reconciliationId = created.get("reconciliations")
                    .get(0)
                    .get("reconciliationId")
                    .asString();
            mockMvc.perform(withAuth(get(RECONCILIATIONS + "/" + reconciliationId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statementId")
                            .value(created.get("statementId").asString()));
            postJson("/v1/accounting/bank-statements", body)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.replayed").value(true))
                    .andExpect(jsonPath("$.reconciliations.length()").value(1));

            // The flag is part of the payload: flipping it under the same requestId is a reuse, both ways.
            body.put("startReconciliation", false);
            expectError(postJson("/v1/accounting/bank-statements", body), 409, "IDEMPOTENCY_CONFLICT");
            body.put("requestId", UUIDv7Generator.generate().toString());
            body.put(
                    "statement",
                    Map.of(
                            "startDate", "2021-10-01",
                            "endDate", "2021-10-31",
                            "openingBalance", "25.00",
                            "closingBalance", "35.00"));
            body.put(
                    "transactions",
                    List.of(Map.of("date", "2021-10-05", "signedAmount", "10.00", "description", "ROW")));
            body.remove("gapAcknowledgement"); // October continues September
            postJson("/v1/accounting/bank-statements", body)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.reconciliations.length()").value(0));
            body.put("startReconciliation", true);
            expectError(postJson("/v1/accounting/bank-statements", body), 409, "IDEMPOTENCY_CONFLICT");
        }

        @Test
        void theListFiltersByPeriodAndWindow() throws Exception {
            reconcile(cash, statement(cash, "0", "250.00", "250.00"));
            mockMvc.perform(withAuth(get(RECONCILIATIONS)
                            .param("glAccountId", cash.toString())
                            .param("periodCode", "2021-09")
                            .param("from", "2021-09-30")
                            .param("to", "2021-09-30")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));
            mockMvc.perform(withAuth(get(RECONCILIATIONS)
                            .param("glAccountId", cash.toString())
                            .param("periodCode", "2021-10")))
                    .andExpect(jsonPath("$.totalElements").value(0));
        }
    }

    // ---- matching --------------------------------------------------------------------------------

    @Nested
    @DisplayName("matching")
    class Matching {

        @Test
        void matchCodesAndEnums() throws Exception {
            UUID first = postEntry(cash, revenue, "1000.00", LocalDate.of(2021, 9, 11));
            UUID second = postEntry(cash, revenue, "250.00", DAY);
            UUID october = postEntry(cash, revenue, "99.50", LocalDate.of(2021, 10, 2));
            UUID statementId = statement(cash, "0", "1349.50", "1250.00", "99.50");
            UUID reconId = reconcile(cash, statementId);
            List<UUID> bank = bankRows(cash);
            String matches = RECONCILIATIONS + "/" + reconId + "/matches";

            expectError(
                    postJson(
                            matches,
                            match(
                                    List.of(UUID.randomUUID(), UUID.randomUUID()),
                                    List.of(UUID.randomUUID(), UUID.randomUUID()),
                                    null)),
                    422,
                    "MATCH_CARDINALITY_NOT_ALLOWED");
            expectError(
                            postJson(matches, match(List.of(bank.get(0)), List.of(first, second), null)),
                            422,
                            "MATCH_REQUIRES_REVIEW")
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("justification"))
                    .andExpect(jsonPath("$.fieldErrors[0].message").value("CARDINALITY_NOT_ONE_TO_ONE"));
            expectError(
                    postJson(matches, match(List.of(bank.get(0)), List.of(first), null)), 422, "MATCH_AMOUNT_MISMATCH");
            expectError(
                    postJson(matches, match(List.of(bank.get(1)), List.of(october), null)),
                    409,
                    "RECONCILIATION_LINE_INELIGIBLE");
            expectError(
                    postJson(matches, match(List.of(bank.get(0)), List.of(first, second), "too short")),
                    400,
                    "JUSTIFICATION_REQUIRED");

            JsonNode accepted = json(postJson(
                            matches,
                            match(List.of(bank.get(0)), List.of(first, second), "Batch deposit of two receipts"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.matchKind").value("ONE_TO_MANY"))
                    .andExpect(jsonPath("$.state").value("ACCEPTED"))
                    .andExpect(jsonPath("$.origin").value("USER"))
                    .andExpect(jsonPath("$.residual").value(0)));
            String matchId = accepted.get("matchId").asString();
            expectError(postJson(matches + "/" + matchId + "/accept", Map.of()), 409, "MATCH_STATE_INVALID");
            expectError(
                    postJson(matches + "/" + matchId + "/unmatch", Map.of("reason", "short")),
                    400,
                    "JUSTIFICATION_REQUIRED");
            postJson(matches + "/" + matchId + "/unmatch", Map.of("reason", "Paired the wrong deposit"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("UNMATCHED"))
                    .andExpect(jsonPath("$.unmatchReason").value("Paired the wrong deposit"));
            postJson(matches, match(List.of(bank.get(0)), List.of(first, second), "Batch deposit of two receipts"))
                    .andExpect(status().isCreated());

            mockMvc.perform(withAuth(
                            get(RECONCILIATIONS + "/" + reconId + "/candidates").param("glLineId", october.toString())))
                    .andExpect(status().isOk());
            expectError(
                    mockMvc.perform(withAuth(get(RECONCILIATIONS + "/" + reconId + "/candidates"))),
                    400,
                    "VALIDATION_ERROR");
            postJson(RECONCILIATIONS + "/" + reconId + "/auto-match", Map.of())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.proposedCount").isNumber());
        }

        @Test
        void theReplacedF2RoutesAre404() throws Exception {
            UUID reconId = reconcile(cash, statement(cash, "0", "250.00", "250.00"));
            mockMvc.perform(withAuth(post(RECONCILIATIONS + "/" + reconId + "/match"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(withAuth(post(RECONCILIATIONS + "/" + reconId + "/unmatch"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound());
        }
    }

    // ---- outstanding items -----------------------------------------------------------------------

    @Nested
    @DisplayName("outstanding items")
    class Items {

        @Test
        void aDepositInTransitBalancesTheWindowAndAWrongSignIsNotEligible() throws Exception {
            UUID deposit = postEntry(cash, revenue, "500.00", LocalDate.of(2021, 9, 30));
            UUID reconId = reconcile(cash, statement(cash, "0", "0.00", "25.00", "-25.00"));
            String items = RECONCILIATIONS + "/" + reconId + "/outstanding-items";

            expectError(
                    postJson(items, Map.of("glLineId", deposit.toString(), "itemKind", "OUTSTANDING_CHECK")),
                    422,
                    "OUTSTANDING_ITEM_NOT_ELIGIBLE");
            expectError(
                    postJson(items, Map.of("glLineId", deposit.toString(), "itemKind", "OTHER_LEDGER_TIMING")),
                    400,
                    "JUSTIFICATION_REQUIRED");
            expectError(
                    postJson(items, Map.of("glLineId", deposit.toString(), "itemKind", "DEPOSIT_IN_TRANSIT")),
                    400,
                    "JUSTIFICATION_REQUIRED");
            // Registered years after its date, the deposit is older than the aging days and needs a justification.
            JsonNode item = json(postJson(
                            items,
                            Map.of(
                                    "glLineId",
                                    deposit.toString(),
                                    "itemKind",
                                    "DEPOSIT_IN_TRANSIT",
                                    "justification",
                                    "Deposit made after the bank's cut-off"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("OPEN"))
                    .andExpect(jsonPath("$.side").value("LEDGER"))
                    .andExpect(jsonPath("$.itemKind").value("DEPOSIT_IN_TRANSIT")));
            mockMvc.perform(withAuth(get(RECONCILIATIONS + "/" + reconId)))
                    .andExpect(jsonPath("$.sumOutstandingLedgerItems").value(500.0))
                    .andExpect(jsonPath("$.adjustedBankBalance").value(500.0))
                    .andExpect(jsonPath("$.difference").value(0))
                    .andExpect(jsonPath("$.countUnexplainedLedger").value(0));
            expectError(
                    postJson(
                            items + "/" + item.get("outstandingItemId").asString() + "/reaffirm",
                            Map.of("justification", "Still in transit at the bank")),
                    422,
                    "OUTSTANDING_ITEM_NOT_ELIGIBLE");
            expectError(
                    postJson(
                            items + "/" + item.get("outstandingItemId").asString() + "/clear-in-gap",
                            Map.of("justification", "Cleared while we changed banks")),
                    422,
                    "OUTSTANDING_ITEM_NOT_ELIGIBLE");
            postJson(
                            items + "/" + item.get("outstandingItemId").asString() + "/release",
                            Map.of("reason", "Registered on the wrong line"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("RELEASED"));
        }
    }

    // ---- adjustments -----------------------------------------------------------------------------

    @Nested
    @DisplayName("adjustments")
    class Adjustments {

        @Test
        void linkAndTransferCodesInTheirOrder() throws Exception {
            UUID reconId = reconcile(cash, statement(cash, "0", "250.00", "250.00"));
            List<UUID> bank = bankRows(cash);
            String adjustments = RECONCILIATIONS + "/" + reconId + "/adjustments";

            expectError(
                    postJson(
                            adjustments,
                            adjustment("OTHER", "5.00", Map.of("justification", "Unclassified bank debit"))),
                    422,
                    "ADJUSTMENT_LINK_REQUIRED");
            expectError(
                    postJson(
                            adjustments,
                            adjustment(
                                    "OTHER",
                                    "250.00",
                                    Map.of("bankTransactionId", bank.get(0).toString(), "justification", "123456789"))),
                    400,
                    "JUSTIFICATION_REQUIRED");
            expectError(
                    postJson(
                            adjustments,
                            adjustment(
                                    "OTHER",
                                    "250.00",
                                    Map.of(
                                            "bankTransactionId",
                                            bank.get(0).toString(),
                                            "justification",
                                            "Unclassified bank credit")),
                            ADJUST_ONLY),
                    403,
                    "RECONCILIATION_ADJUSTMENT_APPROVAL_REQUIRED");
            expectError(
                    postJson(
                            adjustments,
                            adjustment(
                                    "BANK_FEE",
                                    "-1.00",
                                    Map.of("settlesMatchId", UUID.randomUUID().toString()))),
                    422,
                    "ADJUSTMENT_LINK_REQUIRED");
            expectError(
                    postJson(adjustments, adjustment("TRANSFER", "250.00", Map.of())), 422, "ADJUSTMENT_LINK_REQUIRED");
            expectError(
                    postJson(
                            adjustments,
                            adjustment("BANK_FEE", "-1.00", Map.of("counterGlAccountId", otherBank.toString()))),
                    422,
                    "ADJUSTMENT_LINK_REQUIRED");
            expectError(
                    postJson(
                            adjustments,
                            adjustment("TRANSFER", "250.00", Map.of("counterGlAccountId", cash.toString()))),
                    422,
                    "ADJUSTMENT_LINK_NOT_ELIGIBLE");
            expectError(
                    postJson(
                            adjustments,
                            adjustment(
                                    "TRANSFER",
                                    "250.00",
                                    Map.of(
                                            "counterGlAccountId",
                                            UUID.randomUUID().toString()))),
                    422,
                    "ADJUSTMENT_LINK_NOT_ELIGIBLE");
            expectError(
                    postJson(
                            adjustments,
                            adjustment("TRANSFER", "250.00", Map.of("counterGlAccountId", undeposited.toString()))),
                    422,
                    "ACCOUNT_NOT_RECONCILABLE");
            expectError(
                    postJson(
                            adjustments,
                            adjustment("TRANSFER", "0", Map.of("counterGlAccountId", otherBank.toString()))),
                    422,
                    "RECONCILIATION_ADJUSTMENT_SIGN_INVALID");
            expectError(
                    postJson(adjustments, adjustment("BANK_FEE", "5.00", Map.of())),
                    422,
                    "RECONCILIATION_ADJUSTMENT_SIGN_INVALID");
            expectError(
                    postJson(
                            adjustments,
                            adjustment(
                                    "OTHER",
                                    null,
                                    Map.of(
                                            "bridgesStatementId",
                                            UUID.randomUUID().toString(),
                                            "justification",
                                            "Gap left by the change of bank"))),
                    422,
                    "ADJUSTMENT_LINK_NOT_ELIGIBLE");
            // M5: a linked adjustment equals its bank transaction exactly; one minor unit off is refused.
            expectError(
                            postJson(
                                    adjustments,
                                    adjustment(
                                            "TRANSFER",
                                            "249.99",
                                            Map.of(
                                                    "counterGlAccountId",
                                                    otherBank.toString(),
                                                    "bankTransactionId",
                                                    bank.get(0).toString()))),
                            422,
                            "ADJUSTMENT_LINK_NOT_ELIGIBLE")
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("amount"));
        }

        @Test
        void aTransferPostsMatchesItsBankRowReplaysAndReversesOnce() throws Exception {
            UUID reconId = reconcile(cash, statement(cash, "0", "250.00", "250.00"));
            UUID row = bankRows(cash).get(0);
            String adjustments = RECONCILIATIONS + "/" + reconId + "/adjustments";
            Map<String, Object> transfer = adjustment(
                    "TRANSFER",
                    "250.00",
                    Map.of("counterGlAccountId", otherBank.toString(), "bankTransactionId", row.toString()));

            JsonNode posted = json(postJson(adjustments, transfer)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("TRANSFER"))
                    .andExpect(jsonPath("$.status").value("POSTED"))
                    .andExpect(jsonPath("$.transactionDate").value(DAY.toString()))
                    .andExpect(jsonPath("$.postedPeriodCode").value("2021-09"))
                    .andExpect(jsonPath("$.matchId").isNotEmpty())
                    .andExpect(jsonPath("$.entryNumber").isNotEmpty()));
            postJson(adjustments, transfer)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.replayed").value(true))
                    .andExpect(jsonPath("$.journalEntryId")
                            .value(posted.get("journalEntryId").asString()));
            mockMvc.perform(withAuth(get(RECONCILIATIONS + "/" + reconId)))
                    .andExpect(jsonPath("$.difference").value(0))
                    .andExpect(jsonPath("$.countUnexplainedBank").value(0))
                    .andExpect(jsonPath("$.countUnexplainedLedger").value(0));

            String reverse = adjustments + "/" + posted.get("adjustmentId").asString() + "/reverse";
            postJson(reverse, Map.of("reason", "Transfer booked on the wrong account"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REVERSED"));
            expectError(
                    postJson(reverse, Map.of("reason", "Transfer booked on the wrong account")),
                    409,
                    "ADJUSTMENT_ALREADY_REVERSED");
            mockMvc.perform(withAuth(get(RECONCILIATIONS + "/" + reconId)))
                    .andExpect(jsonPath("$.countUnexplainedBank").value(1))
                    .andExpect(jsonPath("$.countUnexplainedLedger").value(0));
        }

        @Test
        void anInactiveCounterIs422GlAccountNotActive() throws Exception {
            GLAccount inactive = glAccounts.findById(otherBank).orElseThrow();
            inactive.setDeactivationDate(LocalDateTime.of(2021, 1, 1, 0, 0));
            glAccounts.save(inactive);
            UUID reconId = reconcile(cash, statement(cash, "0", "250.00", "250.00"));
            expectError(
                    postJson(
                            RECONCILIATIONS + "/" + reconId + "/adjustments",
                            adjustment("TRANSFER", "250.00", Map.of("counterGlAccountId", otherBank.toString()))),
                    422,
                    "GL_ACCOUNT_NOT_ACTIVE");
        }

        @Test
        void theServedTypesIncludeTransfer() throws Exception {
            mockMvc.perform(withAuth(get(RECONCILIATIONS + "/adjustment-types")))
                    .andExpect(jsonPath("$[*].code")
                            .value(org.hamcrest.Matchers.containsInAnyOrder(
                                    "BANK_FEE", "NSF_FEE", "INTEREST_EARNED", "OTHER", "TRANSFER")));
        }
    }

    // ---- finalize, review, report ----------------------------------------------------------------

    @Nested
    @DisplayName("finalize, review and report")
    class Views {

        @Test
        void finalizeGatesOnTheLiveDifferenceAndSealsTheReconciliation() throws Exception {
            UUID reconId = reconcile(cash, statement(cash, "0", "250.00", "250.00"));
            expectError(
                            postJson(RECONCILIATIONS + "/" + reconId + "/finalize", Map.of()),
                            422,
                            "RECONCILIATION_NOT_BALANCED")
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("difference"));
            mockMvc.perform(withAuth(get(RECONCILIATIONS + "/" + reconId + "/review")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.header.baselineSetByThisStatement").value(true))
                    .andExpect(jsonPath("$.equation.difference").value(250.0))
                    .andExpect(jsonPath("$.readiness.canSubmit").value(false))
                    .andExpect(jsonPath("$.readiness.reasons[0]").value("NOT_BALANCED"))
                    .andExpect(jsonPath("$.readiness.reasons[1]").value("UNEXPLAINED_BANK"))
                    .andExpect(jsonPath("$.unresolved.unexplainedBank.length()").value(1));

            UUID line = postEntry(cash, revenue, "250.00", DAY);
            postJson(RECONCILIATIONS + "/" + reconId + "/matches", match(bankRows(cash), List.of(line), null))
                    .andExpect(status().isCreated());
            mockMvc.perform(withAuth(get(RECONCILIATIONS + "/" + reconId + "/report")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.difference").value(0))
                    .andExpect(jsonPath("$.equation.adjustedBankBalance").value(250.0))
                    .andExpect(jsonPath("$.countUnexplainedBank").value(0))
                    .andExpect(jsonPath("$.matchedLineCount").value(1));
            postJson(RECONCILIATIONS + "/" + reconId + "/finalize", Map.of())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("FINALIZED"));
            expectError(
                    postJson(RECONCILIATIONS + "/" + reconId + "/auto-match", Map.of()),
                    409,
                    "RECONCILIATION_ALREADY_FINALIZED");
        }
    }

    // ---- 403 per endpoint ------------------------------------------------------------------------

    @Test
    void everyNewEndpointRefusesACallerWithoutItsPermission() throws Exception {
        UUID id = UUID.randomUUID();
        String base = RECONCILIATIONS + "/" + id;
        String uuid = "\"" + UUIDv7Generator.generate() + "\"";
        Map<String, String> adjustEndpoints = Map.of(
                RECONCILIATIONS,
                "{\"glAccountId\":" + uuid + ",\"requestId\":" + uuid + ",\"statementId\":" + uuid + "}",
                base + "/auto-match",
                "{}",
                base + "/matches",
                "{\"bankTransactionIds\":[" + uuid + "],\"glLineIds\":[" + uuid + "],\"requestId\":" + uuid + "}",
                base + "/matches/" + id + "/accept",
                "{}",
                base + "/matches/" + id + "/reject",
                "{}",
                base + "/matches/" + id + "/unmatch",
                "{}",
                base + "/outstanding-items",
                "{\"glLineId\":" + uuid + ",\"itemKind\":\"DEPOSIT_IN_TRANSIT\"}",
                base + "/outstanding-items/" + id + "/release",
                "{}",
                base + "/outstanding-items/" + id + "/reaffirm",
                "{}",
                base + "/adjustments",
                "{\"type\":\"BANK_FEE\",\"amount\":-1,\"requestId\":" + uuid + "}");
        for (Map.Entry<String, String> endpoint : adjustEndpoints.entrySet()) {
            mockMvc.perform(withAuth(post(endpoint.getKey()), VIEW_ONLY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(endpoint.getValue()))
                    .andExpect(status().isForbidden());
        }
        for (String approveOnly : List.of(
                base + "/outstanding-items/" + id + "/clear-in-gap", base + "/adjustments/" + id + "/reverse")) {
            mockMvc.perform(withAuth(post(approveOnly), ADJUST_ONLY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }
        for (String read : List.of(base + "/review", base + "/candidates")) {
            mockMvc.perform(withAuth(get(read), "accounting:je:view")).andExpect(status().isForbidden());
        }
    }
}
