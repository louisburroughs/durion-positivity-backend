package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.BankRecCloseTestPolicy;
import com.positivity.accounting.internal.bankrec.dto.AdjustmentReverseRequest;
import com.positivity.accounting.internal.bankrec.dto.BankReconciliationAdjustmentResponse;
import com.positivity.accounting.internal.bankrec.dto.BankReconciliationResponse;
import com.positivity.accounting.internal.bankrec.dto.BankStatementCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.BankTransactionJustificationRequest;
import com.positivity.accounting.internal.bankrec.dto.OutstandingItemRegisterRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationAdjustmentRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationApiStatus;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationAuditResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationJustificationRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationMatchCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationMatchDecisionRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationMatchResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationUnmatchRequest;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationMatch;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankAdjustmentType;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.MatchState;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemKind;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationBankMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.BankReconciliationService;
import com.positivity.accounting.internal.bankrec.service.BankStatementService;
import com.positivity.accounting.internal.bankrec.service.BankTransactionService;
import com.positivity.accounting.internal.bankrec.service.ReconciliationAdjustmentService;
import com.positivity.accounting.internal.bankrec.service.ReconciliationApprovalService;
import com.positivity.accounting.internal.bankrec.service.ReconciliationMatchingService;
import com.positivity.accounting.internal.bankrec.service.ReconciliationOutstandingItemService;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.exception.ReconciliationNotBalancedException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import com.positivity.accounting.internal.service.AccountingPeriodService;
import com.positivity.accounting.internal.service.JournalEntryService;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The approval workflow, the ledger-change hook and statement supersession on the real baseline, through the
 * real {@code JournalEntryServiceImpl} post and reverse paths (SPEC-manual-bank-reconciliation §3.8, §4.9, §5.5,
 * §5.6, §8.4; story S5, #2304). Each test works on accounts and months of its own, commits, and removes what it
 * wrote. Requires Docker.
 */
@DisplayName("Bank reconciliation approval, invalidation and supersession on Postgres (#2304)")
class BankReconciliationApprovalPostgresIT extends PostgresTenancyTestBase {

    private static final String ACK = "First statement reconciled on this account";
    private static final String WHY = "The approval no longer holds for this window";
    private static final String TRANSIT = "Deposited on the last day; the bank credits it next month";
    private static final String PREPARER = "preparer";
    private static final String APPROVER = "controller";
    private static final String[] ALL = {
        "accounting:reconciliation:view", "accounting:reconciliation:adjust", "accounting:reconciliation:approve"
    };

    /** The seeded 1000 Cash is in close scope; these closes are not about bank reconciliation (#2305). */
    @Autowired
    private AccountingConfigurationRepository bankRecCloseConfiguration;

    @Autowired
    private GLAccountRepository glAccounts;

    @Autowired
    private JournalEntryService journalEntries;

    @Autowired
    private JournalEntryLineRepository lines;

    @Autowired
    private AccountingPeriodService periods;

    @Autowired
    private BankStatementService statementService;

    @Autowired
    private BankStatementRepository statementRepository;

    @Autowired
    private BankTransactionRepository bankTransactions;

    @Autowired
    private BankTransactionService bankTransactionService;

    @Autowired
    private BankReconciliationService reconciliationService;

    @Autowired
    private ReconciliationApprovalService approval;

    @Autowired
    private ReconciliationMatchingService matching;

    @Autowired
    private ReconciliationOutstandingItemService itemService;

    @Autowired
    private ReconciliationAdjustmentService adjustmentService;

    @Autowired
    private BankReconciliationRepository reconciliationRepository;

    @Autowired
    private BankReconciliationMatchRepository matchRepository;

    @Autowired
    private BankReconciliationGlMatchRepository glMatchRepository;

    @Autowired
    private BankReconciliationBankMatchRepository bankMatchRepository;

    @Autowired
    private BankReconciliationOutstandingItemRepository itemRepository;

    @Autowired
    private AccountingAuditLogRepository auditLogs;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<UUID> accounts = new ArrayList<>();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        // The re-close test switches the close policy to ADVISORY (story S6, #2305); keep it from leaking.
        owner.update(
                "DELETE FROM accounting_configuration WHERE tenant_id = ? AND config_key LIKE 'BANK_REC_%'", TENANT_A);
        for (UUID account : accounts) {
            String recons = "SELECT reconciliation_id FROM bank_reconciliation WHERE gl_account_id = '" + account + "'";
            owner.update("DELETE FROM bank_reconciliation_adjustment WHERE reconciliation_id IN (" + recons + ")");
            owner.update("DELETE FROM bank_reconciliation_outstanding_item WHERE gl_account_id = ?", account);
            owner.update("DELETE FROM bank_reconciliation_gl_match WHERE reconciliation_id IN (" + recons + ")");
            owner.update("DELETE FROM bank_reconciliation_bank_match WHERE match_id IN (SELECT match_id FROM"
                    + " bank_reconciliation_match WHERE reconciliation_id IN (" + recons + "))");
            owner.update("UPDATE bank_reconciliation_match SET replaces_match_id = NULL WHERE reconciliation_id IN ("
                    + recons + ")");
            owner.update("DELETE FROM bank_reconciliation_match WHERE reconciliation_id IN (" + recons + ")");
            owner.update("DELETE FROM bank_reconciliation WHERE gl_account_id = ?", account);
            owner.update("DELETE FROM bank_transaction WHERE gl_account_id = ?", account);
            owner.update(
                    "UPDATE bank_statement SET superseded_by_statement_id = NULL WHERE gl_account_id = ?", account);
            owner.update("DELETE FROM bank_statement WHERE gl_account_id = ?", account);
            owner.update("DELETE FROM bank_account_profile WHERE gl_account_id = ?", account);
        }
        for (UUID account : accounts) {
            List<UUID> entries = owner.queryForList(
                    "SELECT DISTINCT journal_entry_id FROM journal_entry_line WHERE gl_account_id = ?",
                    UUID.class,
                    account);
            for (UUID entry : entries) {
                owner.update(
                        "UPDATE journal_entry SET reversal_journal_entry_id = NULL, reversed_by_journal_entry_id = NULL"
                                + " WHERE journal_entry_id = ?",
                        entry);
            }
            for (UUID entry : entries) {
                owner.update("DELETE FROM journal_entry_line WHERE journal_entry_id = ?", entry);
                owner.update("DELETE FROM journal_entry WHERE journal_entry_id = ?", entry);
            }
        }
        for (UUID account : accounts) {
            owner.update("DELETE FROM gl_account WHERE gl_account_id = ?", account);
        }
        accounts.clear();
    }

    // ---- AC 1, 2, 3, 5, 7 --------------------------------------------------------------------------

    @Test
    @DisplayName("submit and approve: E4 in its order, the approval snapshot, the self-approval refusal audited")
    void submitAndApprove() {
        UUID cash = bankAccount();
        UUID revenue = otherAccount();
        LocalDate day = LocalDate.of(2019, 3, 10);
        UUID deposit = cashLine(post(cash, revenue, "100.00", day), cash);
        UUID transit = cashLine(post(cash, revenue, "40.00", LocalDate.of(2019, 3, 30)), cash);
        UUID statementId = statement(cash, "2019-03-01", "2019-03-31", "0", "100.00", ACK, List.of("100.00"), day);
        as(PREPARER);
        UUID reconId = create(cash, statementId);
        match(reconId, bankRows(statementId), List.of(deposit));

        // AC 3 / 5: balanced by the transit item's absence? No — the unregistered deposit in transit is both a
        // difference and an unexplained line; the difference answers first.
        assertThatThrownBy(() -> inTx(() -> approval.submit(reconId, null)))
                .isInstanceOf(ReconciliationNotBalancedException.class);
        inTx(() -> itemService.register(
                reconId,
                OutstandingItemRegisterRequest.builder()
                        .glLineId(transit)
                        .itemKind(OutstandingItemKind.DEPOSIT_IN_TRANSIT)
                        .justification(TRANSIT)
                        .build()));

        BankReconciliationResponse submitted = inTx(() -> approval.submit(reconId, null));
        assertThat(submitted.getStatus()).isEqualTo(ReconciliationApiStatus.SUBMITTED);
        assertThat(submitted.getSubmittedBy()).isEqualTo(PREPARER);

        // AC 7: the submitter is refused and the refusal is audited although the request rolled back.
        assertThatThrownBy(() -> inTx(() -> approval.approve(reconId, null)))
                .isInstanceOfSatisfying(
                        BankRecException.class,
                        e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.RECONCILIATION_SELF_APPROVAL));
        assertThat(operations(reconId)).contains("RECONCILIATION_APPROVE");
        assertThat(status(reconId)).isEqualTo(ReconciliationStatus.SUBMITTED);

        as(APPROVER);
        BankReconciliationResponse approved = inTx(() -> approval.approve(reconId, null));
        assertThat(approved.getStatus()).isEqualTo(ReconciliationApiStatus.FINALIZED);
        assertThat(approved.getFinalizedBy()).isEqualTo(APPROVER);
        assertThat(approved.getApprovedGlEndingBalance())
                .as("the live balance as-of the window end at approval")
                .isEqualByComparingTo("140.00");
        assertThat(approved.getBaselineDate()).isEqualTo(LocalDate.of(2019, 3, 1));
        assertThat(operations(reconId))
                .containsSubsequence(
                        "RECONCILIATION_CREATE",
                        "RECONCILIATION_SUBMIT",
                        "RECONCILIATION_APPROVE",
                        "RECONCILIATION_APPROVE");
    }

    @Test
    @DisplayName("balanced with an unexplained ledger line: 422 HAS_UNEXPLAINED_ITEMS naming the line (AC 3, 5)")
    void unexplainedLine() {
        UUID cash = bankAccount();
        UUID revenue = otherAccount();
        LocalDate day = LocalDate.of(2019, 4, 10);
        UUID deposit = cashLine(post(cash, revenue, "100.00", day), cash);
        // A pair on the account that nets to zero leaves the difference alone but two lines unexplained.
        UUID in = cashLine(post(cash, revenue, "25.00", day), cash);
        UUID out = cashLine(post(revenue, cash, "25.00", day), cash);
        UUID statementId = statement(cash, "2019-04-01", "2019-04-30", "0", "100.00", ACK, List.of("100.00"), day);
        as(PREPARER);
        UUID reconId = create(cash, statementId);
        match(reconId, bankRows(statementId), List.of(deposit));

        assertThatThrownBy(() -> inTx(() -> approval.submit(reconId, null)))
                .isInstanceOfSatisfying(BankRecException.class, e -> {
                    assertThat(e.code()).isEqualTo(BankRecErrorCode.RECONCILIATION_HAS_UNEXPLAINED_ITEMS);
                    assertThat(e.fieldErrors())
                            .containsEntry("countUnexplainedBank", "0")
                            .containsEntry("countUnexplainedLedger", "2")
                            .containsValues(in.toString(), out.toString());
                });
    }

    // ---- AC 10 -------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "reversing a JE inside an approved window breaks, voids and invalidates; the reversal proceeds (AC 10)")
    void reversalInvalidates() {
        UUID cash = bankAccount();
        UUID revenue = otherAccount();
        LocalDate day = LocalDate.of(2019, 5, 10);
        UUID entry = postLines(
                day, List.of(line(cash, "100.00", "0"), line(cash, "50.00", "0"), line(revenue, "0", "150.00")));
        List<UUID> cashLines = cashLines(entry, cash);
        UUID statementId = statement(cash, "2019-05-01", "2019-05-31", "0", "100.00", ACK, List.of("100.00"), day);
        List<UUID> bankIds = bankRows(statementId);
        as(PREPARER);
        UUID reconId = create(cash, statementId);
        UUID lineL = cashLines.stream()
                .filter(l -> amountOf(l).compareTo(new BigDecimal("100")) == 0)
                .findFirst()
                .orElseThrow();
        UUID lineD =
                cashLines.stream().filter(l -> !l.equals(lineL)).findFirst().orElseThrow();
        UUID matchId = match(reconId, bankIds, List.of(lineL)).getMatchId();
        UUID itemId = inTx(() -> itemService.register(
                        reconId,
                        OutstandingItemRegisterRequest.builder()
                                .glLineId(lineD)
                                .itemKind(OutstandingItemKind.DEPOSIT_IN_TRANSIT)
                                .justification(TRANSIT)
                                .build()))
                .getOutstandingItemId();
        approve(reconId);

        LocalDate reversalDate = LocalDate.of(2019, 6, 3);
        as(PREPARER);
        UUID reversal = inTx(() ->
                        journalEntries.reverseJournalEntry(entry, "Posted to the wrong customer", reversalDate))
                .getJournalEntryId();

        inTx(() -> {
            BankReconciliationMatch match = matchRepository.findById(matchId).orElseThrow();
            assertThat(match.getState()).isEqualTo(MatchState.BROKEN);
            assertThat(match.getBrokenByJournalEntryId()).isEqualTo(reversal);
            assertThat(bankTransactions
                            .findById(bankIds.getFirst())
                            .orElseThrow()
                            .getStatus())
                    .isEqualTo(BankTransactionStatus.UNMATCHED);
            var item = itemRepository.findById(itemId).orElseThrow();
            assertThat(item.getStatus()).isEqualTo(OutstandingItemStatus.VOIDED);
            assertThat(item.getVoidedByJournalEntryId()).isEqualTo(reversal);
            assertThat(item.getClosedOn()).isEqualTo(reversalDate);
            var recon = reconciliationRepository.findById(reconId).orElseThrow();
            assertThat(recon.getStatus()).isEqualTo(ReconciliationStatus.INVALIDATED);
            assertThat(recon.getInvalidationReason()).isEqualTo("LEDGER_LINE_REVERSED");
            assertThat(recon.getInvalidatedByJournalEntryId()).isEqualTo(reversal);
            return null;
        });
        assertThat(operations(reconId)).endsWith("RECONCILIATION_INVALIDATE");
        BankReconciliationResponse live = get(reconId);
        assertThat(live.getCountUnexplainedBank())
                .as("the bank member counts again")
                .isEqualTo(1);
        assertThat(live.getCountUnexplainedLedger())
                .as("the reversal pair never counts")
                .isZero();
    }

    // ---- AC 11 -------------------------------------------------------------------------------------

    @Test
    @DisplayName("reversing a residual settlement breaks its replacement match and frees its members (AC 11)")
    void residualReversalBreaksReplacement() {
        UUID cash = bankAccount();
        UUID revenue = otherAccount();
        // The seeded OTHER -> 2360 mapping is effective from 2021 on.
        LocalDate day = LocalDate.of(2022, 1, 10);
        UUID line = cashLine(post(cash, revenue, "100.00", day), cash);
        UUID statementId = statement(cash, "2022-01-01", "2022-01-31", "0", "99.99", ACK, List.of("99.99"), day);
        List<UUID> bankIds = bankRows(statementId);
        as(ALL);
        UUID reconId = create(cash, statementId);
        UUID matchId = match(reconId, bankIds, List.of(line), "Rounding on the card processor's payout")
                .getMatchId();
        BankReconciliationAdjustmentResponse settled = inTx(() -> adjustmentService.addAdjustment(
                reconId,
                ReconciliationAdjustmentRequest.builder()
                        .type(BankAdjustmentType.OTHER)
                        .settlesMatchId(matchId)
                        .justification("Rounding on the card processor's payout")
                        .requestId(UUIDv7Generator.generate())
                        .build()));
        UUID replacement = settled.getMatchId();
        assertThat(matchState(replacement)).isEqualTo(MatchState.ACCEPTED);

        inTx(() -> adjustmentService.reverse(
                reconId,
                settled.getAdjustmentId(),
                new AdjustmentReverseRequest("The processor refunded the cent", null, null)));

        assertThat(matchState(replacement)).isEqualTo(MatchState.BROKEN);
        inTx(() -> {
            assertThat(bankMatchRepository.findByMatchIdAndActiveTrue(replacement))
                    .isEmpty();
            assertThat(glMatchRepository.findByMatchIdAndActiveTrue(replacement))
                    .isEmpty();
            assertThat(bankTransactions
                            .findById(bankIds.getFirst())
                            .orElseThrow()
                            .getStatus())
                    .isEqualTo(BankTransactionStatus.UNMATCHED);
            return null;
        });
    }

    // ---- AC 12, §5.6 -------------------------------------------------------------------------------

    @Test
    @DisplayName("reopening changes nothing; a posting into the approved window invalidates it (AC 12, §5.6)")
    void postingInvalidatesReopenDoesNot() {
        UUID cash = bankAccount();
        UUID revenue = otherAccount();
        LocalDate day = LocalDate.of(2019, 8, 10);
        UUID deposit = cashLine(post(cash, revenue, "100.00", day), cash);
        UUID statementId = statement(cash, "2019-08-01", "2019-08-31", "0", "100.00", ACK, List.of("100.00"), day);
        as(PREPARER);
        UUID reconId = create(cash, statementId);
        match(reconId, bankRows(statementId), List.of(deposit));
        approve(reconId);

        as("accounting:period:close", "accounting:period:reopen", "accounting:period:view");
        inTx(() -> {
            BankRecCloseTestPolicy.advisory(bankRecCloseConfiguration);
            return null;
        });
        inTx(() -> periods.closePeriod("2019-08"));
        inTx(() -> periods.reopenPeriod("2019-08", "Late supplier refund to book in August"));
        assertThat(status(reconId))
                .as("reopen is permission to post, not a posting")
                .isEqualTo(ReconciliationStatus.FINALIZED);

        as(PREPARER);
        UUID late = post(cash, revenue, "12.00", LocalDate.of(2019, 8, 20));
        inTx(() -> {
            var recon = reconciliationRepository.findById(reconId).orElseThrow();
            assertThat(recon.getStatus()).isEqualTo(ReconciliationStatus.INVALIDATED);
            assertThat(recon.getInvalidationReason()).isEqualTo("LEDGER_LINE_POSTED");
            assertThat(recon.getInvalidatedByJournalEntryId()).isEqualTo(late);
            return null;
        });
    }

    // ---- AC 13 -------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "supersede an INVALIDATED reconciliation: members re-proposed, predecessor SUPERSEDED on approval (AC 13)")
    void supersede() {
        UUID cash = bankAccount();
        UUID revenue = otherAccount();
        LocalDate day = LocalDate.of(2019, 9, 10);
        UUID deposit = cashLine(post(cash, revenue, "100.00", day), cash);
        UUID statementId = statement(cash, "2019-09-01", "2019-09-30", "0", "100.00", ACK, List.of("100.00"), day);
        List<UUID> bankIds = bankRows(statementId);
        as(PREPARER);
        UUID reconId = create(cash, statementId);
        UUID matchId = match(reconId, bankIds, List.of(deposit)).getMatchId();
        approve(reconId);
        as(PREPARER);
        UUID late = cashLine(post(cash, revenue, "30.00", LocalDate.of(2019, 9, 29)), cash);
        assertThat(status(reconId)).isEqualTo(ReconciliationStatus.INVALIDATED);

        as(APPROVER);
        BankReconciliationResponse successor = inTx(() -> approval.supersede(
                reconId, new ReconciliationJustificationRequest(WHY, UUIDv7Generator.generate(), null)));
        assertThat(successor.getStatus()).isEqualTo(ReconciliationApiStatus.IN_PROGRESS);
        assertThat(successor.getSupersedesReconciliationId()).isEqualTo(reconId);
        assertThat(successor.getBaselineDate()).isEqualTo(LocalDate.of(2019, 9, 1));
        UUID successorId = successor.getReconciliationId();
        List<BankReconciliationMatch> proposed =
                inTx(() -> matchRepository.findByReconciliationIdAndState(successorId, MatchState.PROPOSED));
        assertThat(proposed).hasSize(1);
        assertThat(matchState(matchId))
                .as("the predecessor's header keeps its state")
                .isEqualTo(MatchState.ACCEPTED);
        inTx(() -> {
            assertThat(glMatchRepository.findByMatchIdAndActiveTrue(matchId)).isEmpty();
            assertThat(bankMatchRepository.findByMatchIdAndActiveTrue(matchId)).isEmpty();
            return null;
        });

        as(PREPARER);
        inTx(() -> matching.accept(
                successorId, proposed.getFirst().getMatchId(), new ReconciliationMatchDecisionRequest(null)));
        inTx(() -> itemService.register(
                successorId,
                OutstandingItemRegisterRequest.builder()
                        .glLineId(late)
                        .itemKind(OutstandingItemKind.DEPOSIT_IN_TRANSIT)
                        .justification(TRANSIT)
                        .build()));
        approve(successorId);

        inTx(() -> {
            var predecessor = reconciliationRepository.findById(reconId).orElseThrow();
            assertThat(predecessor.getStatus()).isEqualTo(ReconciliationStatus.SUPERSEDED);
            assertThat(predecessor.getSupersededByReconciliationId()).isEqualTo(successorId);
            return null;
        });
    }

    // ---- AC 14, 15 ---------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "cancel unmatches and releases; the stored trail carries every action with actor and reason (AC 14, 15)")
    void cancelAndTrail() {
        UUID cash = bankAccount();
        UUID revenue = otherAccount();
        LocalDate day = LocalDate.of(2019, 10, 10);
        // The deposit's description carries the bank reference, so the rule proposes it with a clear score.
        UUID deposit = cashLine(
                postLines(day, List.of(line(cash, "100.00", "0"), line(revenue, "0", "100.00")), "Deposit DEP1001"),
                cash);
        UUID transit = cashLine(post(cash, revenue, "40.00", LocalDate.of(2019, 10, 30)), cash);
        UUID statementId = inTx(() -> statementService
                .createManualStatement(BankStatementCreateRequest.builder()
                        .glAccountId(cash)
                        .requestId(UUIDv7Generator.generate())
                        .statement(BankStatementCreateRequest.Header.builder()
                                .startDate(LocalDate.of(2019, 10, 1))
                                .endDate(LocalDate.of(2019, 10, 31))
                                .openingBalance(BigDecimal.ZERO)
                                .closingBalance(new BigDecimal("100.00"))
                                .build())
                        .transactions(List.of(BankStatementCreateRequest.Transaction.builder()
                                .date(day)
                                .signedAmount(new BigDecimal("100.00"))
                                .description("DEPOSIT")
                                .reference("DEP1001")
                                .build()))
                        .gapAcknowledgement(ACK)
                        .build())
                .getStatementId());
        List<UUID> bankIds = bankRows(statementId);
        as(PREPARER);
        UUID reconId = create(cash, statementId);
        UUID wrong = match(reconId, bankIds, List.of(deposit)).getMatchId();
        inTx(() -> matching.unmatch(reconId, wrong, new ReconciliationUnmatchRequest("Matched the wrong deposit")));
        inTx(() -> matching.autoMatch(reconId));
        UUID proposal = inTx(() -> matchRepository.findByReconciliationIdAndState(reconId, MatchState.PROPOSED))
                .getFirst()
                .getMatchId();
        inTx(() -> matching.reject(reconId, proposal, new ReconciliationMatchDecisionRequest("Not this one either")));
        UUID accepted = match(reconId, bankIds, List.of(deposit)).getMatchId();
        UUID itemId = inTx(() -> itemService.register(
                        reconId,
                        OutstandingItemRegisterRequest.builder()
                                .glLineId(transit)
                                .itemKind(OutstandingItemKind.DEPOSIT_IN_TRANSIT)
                                .justification(TRANSIT)
                                .build()))
                .getOutstandingItemId();
        inTx(() -> approval.submit(reconId, null));

        as(APPROVER);
        BankReconciliationResponse cancelled =
                inTx(() -> approval.cancel(reconId, new ReconciliationJustificationRequest(WHY, null, null)));
        assertThat(cancelled.getStatus()).isEqualTo(ReconciliationApiStatus.CANCELLED);
        inTx(() -> {
            BankReconciliationMatch match = matchRepository.findById(accepted).orElseThrow();
            assertThat(match.getState()).isEqualTo(MatchState.UNMATCHED);
            assertThat(match.getUnmatchReason()).isEqualTo("RECONCILIATION_CANCELLED");
            assertThat(bankTransactions
                            .findById(bankIds.getFirst())
                            .orElseThrow()
                            .getStatus())
                    .isEqualTo(BankTransactionStatus.UNMATCHED);
            assertThat(itemRepository.findById(itemId).orElseThrow().getStatus())
                    .isEqualTo(OutstandingItemStatus.RELEASED);
            return null;
        });

        ReconciliationAuditResponse trail = inTx(() -> reconciliationService.audit(
                reconId,
                PageRequest.of(
                        0,
                        50,
                        Sort.by("timestamp")
                                .ascending()
                                .and(Sort.by("auditLogId").ascending()))));
        assertThat(trail.getEntries())
                .extracting(ReconciliationAuditResponse.Entry::getOperation)
                .contains(
                        "RECONCILIATION_CREATE",
                        "RECONCILIATION_MATCH",
                        "RECONCILIATION_UNMATCH",
                        "RECONCILIATION_AUTO_MATCH",
                        "RECONCILIATION_MATCH_REJECT",
                        "RECONCILIATION_OUTSTANDING_REGISTER",
                        "RECONCILIATION_SUBMIT",
                        "RECONCILIATION_CANCEL");
        assertThat(trail.getEntries())
                .filteredOn(e -> "RECONCILIATION_UNMATCH".equals(e.getOperation()))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getUserId()).isEqualTo(PREPARER);
                    assertThat(e.getEntityId()).isEqualTo(wrong);
                    assertThat(e.getJustification()).isEqualTo("Matched the wrong deposit");
                });
        assertThat(trail.getEntries())
                .filteredOn(e -> "RECONCILIATION_CANCEL".equals(e.getOperation()))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getUserId()).isEqualTo(APPROVER);
                    assertThat(e.getJustification()).isEqualTo(WHY);
                });
    }

    // ---- AC 6 --------------------------------------------------------------------------------------

    @Test
    @DisplayName("a corrected statement supersedes the approved one: rows excluded, approval invalidated (AC 6) [M]")
    void statementSupersession() {
        UUID cash = bankAccount();
        UUID revenue = otherAccount();
        LocalDate day = LocalDate.of(2019, 11, 10);
        UUID deposit = cashLine(post(cash, revenue, "100.00", day), cash);
        UUID wire = cashLine(post(cash, revenue, "60.00", LocalDate.of(2019, 11, 14)), cash);
        UUID oldStatement = statement(cash, "2019-11-01", "2019-11-30", "0", "100.00", ACK, List.of("100.00"), day);
        List<UUID> oldRows = bankRows(oldStatement);
        as(PREPARER);
        UUID reconId = create(cash, oldStatement);
        UUID matchId = match(reconId, oldRows, List.of(deposit)).getMatchId();
        inTx(() -> itemService.register(
                reconId,
                OutstandingItemRegisterRequest.builder()
                        .glLineId(wire)
                        .itemKind(OutstandingItemKind.DEPOSIT_IN_TRANSIT)
                        .justification(TRANSIT)
                        .build()));
        approve(reconId);

        // Refusals first: nothing changes.
        as(PREPARER);
        UUID otherBank = bankAccount();
        UUID elsewhere = statement(otherBank, "2019-11-01", "2019-11-30", "0", "5.00", ACK, List.of("5.00"), day);
        assertThatThrownBy(() -> corrected(cash, elsewhere, "160.00", List.of("100.00", "60.00")))
                .isInstanceOfSatisfying(
                        BankRecException.class,
                        e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.STATEMENT_SUPERSESSION_NOT_ELIGIBLE));
        assertThat(statusOf(oldStatement)).isEqualTo(BankStatementStatus.COMMITTED);

        UUID correctedId = corrected(cash, oldStatement, "160.00", List.of("100.00", "60.00"));

        inTx(() -> {
            BankStatement old = statementRepository.findById(oldStatement).orElseThrow();
            assertThat(old.getStatus()).isEqualTo(BankStatementStatus.SUPERSEDED);
            assertThat(old.getSupersededByStatementId()).isEqualTo(correctedId);
            for (UUID row : oldRows) {
                BankTransaction t = bankTransactions.findById(row).orElseThrow();
                assertThat(t.getStatus()).isEqualTo(BankTransactionStatus.EXCLUDED);
                assertThat(t.getExclusionReason()).isEqualTo("STATEMENT_SUPERSEDED");
            }
            assertThat(bankTransactions.findByStatementIdOrderBySourceRowNumberAsc(correctedId))
                    .as("no collision with the old rows (R2)")
                    .extracting(BankTransaction::getStatus)
                    .containsOnly(BankTransactionStatus.UNMATCHED);
            var recon = reconciliationRepository.findById(reconId).orElseThrow();
            assertThat(recon.getStatus()).isEqualTo(ReconciliationStatus.INVALIDATED);
            assertThat(recon.getInvalidationReason()).isEqualTo("STATEMENT_SUPERSEDED");
            assertThat(glMatchRepository.findByMatchIdAndActiveTrue(matchId)).isEmpty();
            List<AccountingAuditLog> supersede =
                    auditLogs.findByEntityTypeAndEntityIdOrderByTimestampAsc("BANK_STATEMENT", oldStatement).stream()
                            .filter(r -> "BANK_STATEMENT_SUPERSEDE".equals(r.getOperation()))
                            .toList();
            assertThat(supersede).singleElement().satisfies(r -> {
                assertThat(r.getOldValue()).isEqualTo("COMMITTED");
                assertThat(r.getNewValue()).isEqualTo("SUPERSEDED");
                assertThat(r.getJustification()).isEqualTo(WHY);
            });
            assertThat(auditLogs.findByEntityTypeAndEntityIdOrderByTimestampAsc("BANK_ACCOUNT_PROFILE", cash))
                    .as("the baseline did not move, so no second BANK_ACCOUNT_BASELINE_SET")
                    .hasSize(1);
            return null;
        });

        // §3.8: a row of the superseded statement is not restored.
        as(ALL);
        assertThatThrownBy(() -> inTx(() -> bankTransactionService.restore(
                        oldRows.getFirst(), new BankTransactionJustificationRequest(WHY, null))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("superseded");

        // The corrected statement is reconciled; its approval supersedes the invalidated one.
        as(PREPARER);
        UUID successorId = create(cash, correctedId);
        List<UUID> newRows = bankRows(correctedId);
        match(successorId, List.of(newRows.get(0)), List.of(deposit));
        // The ledger-side item the old reconciliation registered stays OPEN (O2); matching its line clears it.
        match(successorId, List.of(newRows.get(1)), List.of(wire));
        approve(successorId);
        inTx(() -> {
            var predecessor = reconciliationRepository.findById(reconId).orElseThrow();
            assertThat(predecessor.getStatus()).isEqualTo(ReconciliationStatus.SUPERSEDED);
            assertThat(predecessor.getSupersededByReconciliationId()).isEqualTo(successorId);
            return null;
        });
    }

    @Test
    @DisplayName("superseding a statement with an IN_PROGRESS reconciliation is 409 WINDOW_ALREADY_RECONCILED (AC 6)")
    void supersessionOverAnActiveReconciliation() {
        UUID cash = bankAccount();
        LocalDate day = LocalDate.of(2019, 12, 10);
        UUID oldStatement = statement(cash, "2019-12-01", "2019-12-31", "0", "100.00", ACK, List.of("100.00"), day);
        as(PREPARER);
        create(cash, oldStatement);

        assertThatThrownBy(() -> corrected(cash, oldStatement, "160.00", List.of("100.00", "60.00")))
                .isInstanceOfSatisfying(
                        BankRecException.class,
                        e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.RECONCILIATION_WINDOW_ALREADY_RECONCILED));
        assertThat(statusOf(oldStatement)).isEqualTo(BankStatementStatus.COMMITTED);
    }

    // ---- AC 9 --------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "a posting that commits first is seen by the approval: NOT_BALANCED, never a stale FINALIZED (AC 9) [M]")
    void postingBeforeApproval() throws Exception {
        UUID cash = bankAccount();
        UUID revenue = otherAccount();
        UUID reconId = submittedWindow(cash, revenue, LocalDate.of(2018, 3, 10), "2018-03-01", "2018-03-31");
        CountDownLatch posted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> posting = pool.submit(() -> inTxAs(new String[] {PREPARER}, () -> {
                journalEntries.postJournalEntry(draft(cash, revenue, "7.00", LocalDate.of(2018, 3, 15)), null);
                posted.countDown();
                release.await(20, TimeUnit.SECONDS);
                return null;
            }));
            assertThat(posted.await(20, TimeUnit.SECONDS)).isTrue();
            Future<Object> approving = pool.submit(() -> {
                try {
                    return inTxAs(new String[] {APPROVER}, () -> approval.approve(reconId, null));
                } catch (RuntimeException e) {
                    return e;
                }
            });
            Thread.sleep(500);
            assertThat(approving.isDone())
                    .as("the approval waits for the posting's row lock")
                    .isFalse();
            release.countDown();
            posting.get(20, TimeUnit.SECONDS);
            assertThat(approving.get(20, TimeUnit.SECONDS)).isInstanceOf(ReconciliationNotBalancedException.class);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(status(reconId)).isEqualTo(ReconciliationStatus.SUBMITTED);
    }

    @Test
    @DisplayName("an approval that commits first is invalidated by the posting behind it (AC 9) [M]")
    void approvalBeforePosting() throws Exception {
        UUID cash = bankAccount();
        UUID revenue = otherAccount();
        UUID reconId = submittedWindow(cash, revenue, LocalDate.of(2018, 4, 10), "2018-04-01", "2018-04-30");
        UUID draft = inTxAs(new String[] {PREPARER}, () -> draft(cash, revenue, "7.00", LocalDate.of(2018, 4, 15)));
        CountDownLatch approved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> approving = pool.submit(() -> inTxAs(new String[] {APPROVER}, () -> {
                approval.approve(reconId, null);
                approved.countDown();
                release.await(20, TimeUnit.SECONDS);
                return null;
            }));
            assertThat(approved.await(20, TimeUnit.SECONDS)).isTrue();
            Future<?> posting = pool.submit(
                    () -> inTxAs(new String[] {PREPARER}, () -> journalEntries.postJournalEntry(draft, null)));
            Thread.sleep(500);
            assertThat(posting.isDone())
                    .as("the hook waits for the approval's row lock")
                    .isFalse();
            release.countDown();
            approving.get(20, TimeUnit.SECONDS);
            posting.get(20, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        inTx(() -> {
            var recon = reconciliationRepository.findById(reconId).orElseThrow();
            assertThat(recon.getStatus()).isEqualTo(ReconciliationStatus.INVALIDATED);
            assertThat(recon.getInvalidationReason()).isEqualTo("LEDGER_LINE_POSTED");
            return null;
        });
    }

    // ---- fixtures ----------------------------------------------------------------------------------

    /** A window with one matched deposit, SUBMITTED by the preparer. */
    private UUID submittedWindow(UUID cash, UUID revenue, LocalDate day, String start, String end) {
        UUID deposit = cashLine(post(cash, revenue, "100.00", day), cash);
        UUID statementId = statement(cash, start, end, "0", "100.00", ACK, List.of("100.00"), day);
        as(PREPARER);
        UUID reconId = create(cash, statementId);
        match(reconId, bankRows(statementId), List.of(deposit));
        inTx(() -> approval.submit(reconId, null));
        SecurityContextHolder.clearContext();
        return reconId;
    }

    /** Submits as the preparer and approves as the controller. */
    private void approve(UUID reconId) {
        as(PREPARER);
        if (status(reconId) == ReconciliationStatus.IN_PROGRESS) {
            inTx(() -> approval.submit(reconId, null));
        }
        as(APPROVER);
        inTx(() -> approval.approve(reconId, null));
        assertThat(status(reconId)).isEqualTo(ReconciliationStatus.FINALIZED);
    }

    private UUID corrected(UUID cash, UUID supersedes, String closing, List<String> amounts) {
        List<BankStatementCreateRequest.Transaction> rows = new ArrayList<>();
        LocalDate[] dates = {LocalDate.of(2019, 11, 10), LocalDate.of(2019, 11, 14)};
        if (supersedes != null) {
            BankStatement old =
                    inTx(() -> statementRepository.findById(supersedes).orElse(null));
            if (old != null && old.getStartDate().getMonthValue() == 12) {
                dates = new LocalDate[] {LocalDate.of(2019, 12, 10), LocalDate.of(2019, 12, 14)};
            }
        }
        for (int i = 0; i < amounts.size(); i++) {
            rows.add(BankStatementCreateRequest.Transaction.builder()
                    .date(dates[i])
                    .signedAmount(new BigDecimal(amounts.get(i)))
                    .description("ROW " + (i + 1))
                    .build());
        }
        LocalDate start = dates[0].withDayOfMonth(1);
        return inTx(() -> statementService
                .createManualStatement(BankStatementCreateRequest.builder()
                        .glAccountId(cash)
                        .requestId(UUIDv7Generator.generate())
                        .statement(BankStatementCreateRequest.Header.builder()
                                .startDate(start)
                                .endDate(start.withDayOfMonth(start.lengthOfMonth()))
                                .openingBalance(BigDecimal.ZERO)
                                .closingBalance(new BigDecimal(closing))
                                .build())
                        .transactions(rows)
                        .gapAcknowledgement(ACK)
                        .supersedesStatementId(supersedes)
                        .supersessionJustification(WHY)
                        .build())
                .getStatementId());
    }

    private List<String> operations(UUID reconId) {
        return inTx(
                () -> auditLogs.findByEntityTypeAndEntityIdOrderByTimestampAsc("BANK_RECONCILIATION", reconId).stream()
                        .map(AccountingAuditLog::getOperation)
                        .toList());
    }

    private ReconciliationStatus status(UUID reconId) {
        return inTx(
                () -> reconciliationRepository.findById(reconId).orElseThrow().getStatus());
    }

    private BankStatementStatus statusOf(UUID statementId) {
        return inTx(
                () -> statementRepository.findById(statementId).orElseThrow().getStatus());
    }

    private MatchState matchState(UUID matchId) {
        return inTx(() -> matchRepository.findById(matchId).orElseThrow().getState());
    }

    private BigDecimal amountOf(UUID lineId) {
        return inTx(() -> {
            JournalEntryLine line = lines.findById(lineId).orElseThrow();
            return line.getDebitAmount().subtract(line.getCreditAmount());
        });
    }

    private static UsernamePasswordAuthenticationToken token(String user, String... authorities) {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                user,
                null,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        token.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, user));
        return token;
    }

    /** Acts as {@code user} with every reconciliation permission; a permission list acts as the preparer. */
    private void as(String... userOrAuthorities) {
        if (userOrAuthorities.length == 1 && !userOrAuthorities[0].contains(":")) {
            SecurityContextHolder.getContext().setAuthentication(token(userOrAuthorities[0], ALL));
        } else {
            SecurityContextHolder.getContext().setAuthentication(token(PREPARER, userOrAuthorities));
        }
    }

    private UUID create(UUID cash, UUID statementId) {
        return inTx(() -> reconciliationService.create(ReconciliationCreateRequest.builder()
                        .glAccountId(cash)
                        .requestId(UUIDv7Generator.generate())
                        .statementId(statementId)
                        .build()))
                .getReconciliationId();
    }

    private BankReconciliationResponse get(UUID reconId) {
        return inTx(() -> reconciliationService.get(reconId));
    }

    private ReconciliationMatchResponse match(UUID reconId, List<UUID> bankIds, List<UUID> glIds) {
        return match(reconId, bankIds, glIds, null);
    }

    private ReconciliationMatchResponse match(UUID reconId, List<UUID> bankIds, List<UUID> glIds, String why) {
        return inTx(() -> matching.createMatch(
                reconId,
                ReconciliationMatchCreateRequest.builder()
                        .bankTransactionIds(bankIds)
                        .glLineIds(glIds)
                        .justification(why)
                        .requestId(UUIDv7Generator.generate())
                        .build()));
    }

    private UUID statement(
            UUID account,
            String start,
            String end,
            String opening,
            String closing,
            String ack,
            List<String> amounts,
            LocalDate rowDate) {
        List<BankStatementCreateRequest.Transaction> rows = new ArrayList<>();
        int n = 0;
        for (String amount : amounts) {
            rows.add(BankStatementCreateRequest.Transaction.builder()
                    .date(rowDate)
                    .signedAmount(new BigDecimal(amount))
                    .description("ROW " + (++n))
                    .build());
        }
        return inTx(() -> statementService
                .createManualStatement(BankStatementCreateRequest.builder()
                        .glAccountId(account)
                        .requestId(UUIDv7Generator.generate())
                        .statement(BankStatementCreateRequest.Header.builder()
                                .startDate(LocalDate.parse(start))
                                .endDate(LocalDate.parse(end))
                                .openingBalance(new BigDecimal(opening))
                                .closingBalance(new BigDecimal(closing))
                                .build())
                        .transactions(rows)
                        .gapAcknowledgement(ack)
                        .build())
                .getStatementId());
    }

    private List<UUID> bankRows(UUID statementId) {
        return inTx(() -> bankTransactions.findByStatementIdOrderBySourceRowNumberAsc(statementId).stream()
                .map(BankTransaction::getBankTransactionId)
                .toList());
    }

    private UUID bankAccount() {
        return account(AccountType.ASSET, AccountSubtype.BANK_CASH, true);
    }

    private UUID otherAccount() {
        return account(AccountType.REVENUE, null, false);
    }

    private UUID account(AccountType type, AccountSubtype subtype, boolean reconcilable) {
        String suffix = UUIDv7Generator.generate().toString().substring(24);
        UUID id = inTx(() -> {
            GLAccount account = new GLAccount();
            account.setGlAccountId(UUIDv7Generator.generate());
            account.setAccountCode("S" + suffix);
            account.setAccountName("Approval IT " + suffix);
            account.setAccountType(type);
            account.setAccountSubtype(subtype);
            account.setReconcilable(reconcilable);
            account.setActivationDate(LocalDateTime.of(2015, 1, 1, 0, 0));
            account.setCreatedBy("it");
            account.setModifiedBy("it");
            return glAccounts.save(account).getGlAccountId();
        });
        accounts.add(id);
        return id;
    }

    private static JournalEntryCreateRequest.JournalEntryLineRequest line(UUID account, String debit, String credit) {
        return JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                .glAccountId(account)
                .debitAmount(new BigDecimal(debit))
                .creditAmount(new BigDecimal(credit))
                .build();
    }

    /** A DRAFT Dr {@code debit} / Cr {@code credit} entry, not yet posted. */
    private UUID draft(UUID debit, UUID credit, String amount, LocalDate day) {
        return journalEntries
                .createJournalEntry(JournalEntryCreateRequest.builder()
                        .transactionDate(day.atTime(12, 0))
                        .sourceEventId(UUIDv7Generator.generate())
                        .description("Approval IT")
                        .lines(List.of(line(debit, amount, "0"), line(credit, "0", amount)))
                        .build())
                .getJournalEntryId();
    }

    /** Posts Dr {@code debit} / Cr {@code credit} of {@code amount} on {@code day}; returns the entry id. */
    private UUID post(UUID debit, UUID credit, String amount, LocalDate day) {
        return postLines(day, List.of(line(debit, amount, "0"), line(credit, "0", amount)));
    }

    private UUID postLines(LocalDate day, List<JournalEntryCreateRequest.JournalEntryLineRequest> entryLines) {
        return postLines(day, entryLines, "Approval IT");
    }

    private UUID postLines(
            LocalDate day, List<JournalEntryCreateRequest.JournalEntryLineRequest> entryLines, String description) {
        return inTx(() -> {
            UUID created = journalEntries
                    .createJournalEntry(JournalEntryCreateRequest.builder()
                            .transactionDate(day.atTime(12, 0))
                            .sourceEventId(UUIDv7Generator.generate())
                            .description(description)
                            .lines(entryLines)
                            .build())
                    .getJournalEntryId();
            return journalEntries.postJournalEntry(created, null).getJournalEntryId();
        });
    }

    private UUID cashLine(UUID entryId, UUID cash) {
        return cashLines(entryId, cash).getFirst();
    }

    private List<UUID> cashLines(UUID entryId, UUID cash) {
        return inTx(() -> lines.findByJournalEntry_JournalEntryId(entryId).stream()
                .filter(l -> cash.equals(l.getGlAccountId()))
                .map(JournalEntryLine::getLineId)
                .toList());
    }

    private <T> T inTxAs(String[] user, Callable<T> work) {
        SecurityContextHolder.getContext().setAuthentication(token(user[0], ALL));
        try {
            return inTx(work);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private <T> T inTx(Callable<T> work) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return asTenant(
                TENANT_A,
                () -> tx.execute(status -> {
                    try {
                        return work.call();
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }));
    }
}
