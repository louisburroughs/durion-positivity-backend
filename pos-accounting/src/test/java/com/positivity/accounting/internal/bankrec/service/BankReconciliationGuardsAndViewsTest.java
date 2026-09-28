package com.positivity.accounting.internal.bankrec.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.dto.BankReconciliationListResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationAdjustmentRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationAuditResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationMatchRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationReportResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationUnmatchRequest;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationAdjustment;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationBankMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationGlMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationMatch;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankAdjustmentType;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.MatchState;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationAdjustmentRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationBankMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.exception.ReconciliationAlreadyFinalizedException;
import com.positivity.accounting.internal.exception.ReconciliationLineIneligibleException;
import com.positivity.accounting.internal.exception.ReconciliationNotFoundException;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import com.positivity.accounting.internal.service.GLMappingResolver;
import com.positivity.accounting.internal.service.JournalEntryService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * The guard rails and read views of {@link BankReconciliationServiceImpl}.
 *
 * <h2>Why this test exists</h2>
 *
 * The class sat at 52% branch — the weakest of Phase 3.6 so far — and the gaps were the guards on
 * money movements. {@code list}'s four filter combinations had no coverage at all;
 * {@code unmatch}'s statement-line resolution (the arm operators actually use from the UI) had
 * never resolved a match group; and half of {@code match}'s rejections — a duplicated line id, a
 * GL line on someone else's account, a line already matched — were unexercised. Every one of those
 * guards exists to stop a reconciliation from silently absorbing another account's cash movement.
 * These tests pin them before {@code match} and {@code unmatch} are split.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BankReconciliationServiceImpl — guards and read views")
class BankReconciliationGuardsAndViewsTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("5eed0acc-0000-4000-8000-000000001000");
    private static final UUID RECON_ID = UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678900001");
    private static final UUID STATEMENT_ID = UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678900002");

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private BankReconciliationRepository reconciliationRepository;

    @Mock
    private BankStatementRepository statementRepository;

    @Mock
    private BankTransactionRepository transactionRepository;

    @Mock
    private BankReconciliationMatchRepository matchRepository;

    @Mock
    private BankReconciliationBankMatchRepository bankMatchRepository;

    @Mock
    private BankReconciliationAdjustmentRepository adjustmentRepository;

    @Mock
    private BankReconciliationGlMatchRepository glMatchRepository;

    @Mock
    private GLAccountRepository glAccountRepository;

    @Mock
    private JournalEntryLineRepository journalEntryLineRepository;

    @Mock
    private GLMappingResolver glMappingResolver;

    @Mock
    private JournalEntryService journalEntryService;

    private BankReconciliationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BankReconciliationServiceImpl(
                clock,
                reconciliationRepository,
                statementRepository,
                transactionRepository,
                matchRepository,
                glMatchRepository,
                bankMatchRepository,
                adjustmentRepository,
                glAccountRepository,
                journalEntryLineRepository,
                glMappingResolver,
                journalEntryService);
    }

    @Nested
    @DisplayName("list filters")
    class ListFilters {

        private final Pageable page = PageRequest.of(1, 20);
        private final Page<BankReconciliation> result =
                new PageImpl<>(List.of(openReconciliation()), PageRequest.of(1, 20), 41);

        @Test
        @DisplayName("account and status together use the combined query")
        void accountAndStatus() {
            when(reconciliationRepository.findByGlAccount_GlAccountIdAndStatus(
                            ACCOUNT_ID, ReconciliationStatus.IN_PROGRESS, page))
                    .thenReturn(result);
            stubLinesForResponse();

            BankReconciliationListResponse response = service.list(ACCOUNT_ID, ReconciliationStatus.IN_PROGRESS, page);

            assertThat(response.getReconciliations()).hasSize(1);
            assertThat(response.getTotalElements()).isEqualTo(41);
            assertThat(response.getPageNumber()).isEqualTo(1);
            assertThat(response.getTotalPages()).isEqualTo(3);
        }

        @Test
        @DisplayName("account alone filters by account")
        void accountOnly() {
            when(reconciliationRepository.findByGlAccount_GlAccountId(ACCOUNT_ID, page))
                    .thenReturn(result);
            stubLinesForResponse();

            assertThat(service.list(ACCOUNT_ID, null, page).getReconciliations())
                    .hasSize(1);
        }

        @Test
        @DisplayName("status alone filters by status")
        void statusOnly() {
            when(reconciliationRepository.findByStatus(ReconciliationStatus.FINALIZED, page))
                    .thenReturn(result);
            stubLinesForResponse();

            assertThat(service.list(null, ReconciliationStatus.FINALIZED, page).getReconciliations())
                    .hasSize(1);
        }

        @Test
        @DisplayName("no filters lists everything")
        void noFilters() {
            when(reconciliationRepository.findAll(page)).thenReturn(result);
            stubLinesForResponse();

            assertThat(service.list(null, null, page).getReconciliations()).hasSize(1);
        }

        private void stubLinesForResponse() {
            when(transactionRepository.findByStatementIdOrderBySourceRowNumberAsc(STATEMENT_ID))
                    .thenReturn(List.of());
            when(adjustmentRepository.findByReconciliation_ReconciliationId(RECON_ID))
                    .thenReturn(List.of());
        }
    }

    @Nested
    @DisplayName("match guards")
    class MatchGuards {

        @BeforeEach
        void openRecon() {
            when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(openReconciliation()));
        }

        @Test
        @DisplayName("a statement line id that resolves to no row is missing, not skipped")
        void missingStatementLine() {
            UUID s1 = UUID.randomUUID();
            UUID ghost = UUID.randomUUID();
            when(transactionRepository.findAllById(List.of(s1, ghost)))
                    .thenReturn(List.of(statementLine(s1, new BigDecimal("100.0000"))));

            // Matching only the lines that were found would report success for a selection the
            // operator did not make. (A duplicated id, by contrast, is deduped by the same
            // guard's HashSet and is not an error.)
            assertThatThrownBy(
                            () -> service.match(RECON_ID, matchRequest(List.of(s1, ghost), List.of(UUID.randomUUID()))))
                    .isInstanceOf(ReconciliationNotFoundException.class);
        }

        @Test
        @DisplayName("a duplicated statement line id dedupes to one line and one amount")
        void duplicatedStatementLineIdDedupes() {
            when(reconciliationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            UUID s1 = UUID.randomUUID();
            UUID g1 = UUID.randomUUID();
            BankTransaction line = statementLine(s1, new BigDecimal("100.0000"));
            when(transactionRepository.findAllById(List.of(s1, s1))).thenReturn(List.of(line));
            when(journalEntryLineRepository.findAllById(List.of(g1)))
                    .thenReturn(List.of(postedGlLine(g1, new BigDecimal("100.0000"), BigDecimal.ZERO)));
            when(glMatchRepository.existsByGlLineIdAndActiveTrue(g1)).thenReturn(false);
            when(matchRepository.save(any(BankReconciliationMatch.class))).thenAnswer(inv -> {
                BankReconciliationMatch header = inv.getArgument(0);
                header.setMatchId(UUID.randomUUID());
                return header;
            });
            when(transactionRepository.findByStatementIdOrderBySourceRowNumberAsc(STATEMENT_ID))
                    .thenReturn(List.of(line));
            when(adjustmentRepository.findByReconciliation_ReconciliationId(RECON_ID))
                    .thenReturn(List.of());

            // The repository returns the row once, so the statement side nets 100.00 — counting
            // it per requested id would demand 200.00 of GL for 100.00 of statement.
            service.match(RECON_ID, matchRequest(List.of(s1, s1), List.of(g1)));

            assertThat(line.getStatus()).isEqualTo(BankTransactionStatus.MATCHED);
        }

        @Test
        @DisplayName("a statement line already in a match group cannot be matched again")
        void alreadyMatchedStatementLine() {
            UUID s1 = UUID.randomUUID();
            BankTransaction line = statementLine(s1, new BigDecimal("100.0000"));
            line.setStatus(BankTransactionStatus.MATCHED);
            when(transactionRepository.findAllById(List.of(s1))).thenReturn(List.of(line));

            assertThatThrownBy(() -> service.match(RECON_ID, matchRequest(List.of(s1), List.of(UUID.randomUUID()))))
                    .isInstanceOf(ReconciliationLineIneligibleException.class);
        }

        @Test
        @DisplayName("a GL line id that resolves to no row is missing")
        void missingGlLine() {
            UUID s1 = UUID.randomUUID();
            UUID g1 = UUID.randomUUID();
            UUID ghost = UUID.randomUUID();
            when(transactionRepository.findAllById(List.of(s1)))
                    .thenReturn(List.of(statementLine(s1, new BigDecimal("100.0000"))));
            when(journalEntryLineRepository.findAllById(List.of(g1, ghost)))
                    .thenReturn(List.of(postedGlLine(g1, new BigDecimal("100.0000"), BigDecimal.ZERO)));

            assertThatThrownBy(() -> service.match(RECON_ID, matchRequest(List.of(s1), List.of(g1, ghost))))
                    .isInstanceOf(ReconciliationNotFoundException.class);
        }

        @Test
        @DisplayName("a GL line posting to a different account is rejected outright")
        void glLineOnAnotherAccount() {
            UUID s1 = UUID.randomUUID();
            UUID g1 = UUID.randomUUID();
            when(transactionRepository.findAllById(List.of(s1)))
                    .thenReturn(List.of(statementLine(s1, new BigDecimal("100.0000"))));
            JournalEntryLine foreign = postedGlLine(g1, new BigDecimal("100.0000"), BigDecimal.ZERO);
            foreign.setGlAccountId(UUID.randomUUID());
            when(journalEntryLineRepository.findAllById(List.of(g1))).thenReturn(List.of(foreign));

            // The whole point of a reconciliation: only movements on the reconciled account may
            // explain the bank statement.
            assertThatThrownBy(() -> service.match(RECON_ID, matchRequest(List.of(s1), List.of(g1))))
                    .isInstanceOf(InvalidRequestParameterException.class);
        }

        @Test
        @DisplayName("a GL line detached from any journal entry is not eligible")
        void glLineWithoutAJournalEntry() {
            UUID s1 = UUID.randomUUID();
            UUID g1 = UUID.randomUUID();
            when(transactionRepository.findAllById(List.of(s1)))
                    .thenReturn(List.of(statementLine(s1, new BigDecimal("100.0000"))));
            JournalEntryLine orphan = postedGlLine(g1, new BigDecimal("100.0000"), BigDecimal.ZERO);
            orphan.setJournalEntry(null);
            when(journalEntryLineRepository.findAllById(List.of(g1))).thenReturn(List.of(orphan));

            assertThatThrownBy(() -> service.match(RECON_ID, matchRequest(List.of(s1), List.of(g1))))
                    .isInstanceOf(ReconciliationLineIneligibleException.class);
        }

        private ReconciliationMatchRequest matchRequest(List<UUID> statementIds, List<UUID> glIds) {
            return ReconciliationMatchRequest.builder()
                    .statementLineIds(statementIds)
                    .glLineIds(glIds)
                    .build();
        }
    }

    @Nested
    @DisplayName("unmatch by statement lines")
    class UnmatchByStatementLines {

        @BeforeEach
        void openRecon() {
            when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(openReconciliation()));
        }

        @Test
        @DisplayName("lines agreeing on one match group release that group")
        void linesResolvingToOneGroup() {
            when(reconciliationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            UUID matchId = UUID.randomUUID();
            UUID s1 = UUID.randomUUID();
            UUID s2 = UUID.randomUUID();
            BankTransaction line1 = matchedLine(s1);
            BankTransaction line2 = matchedLine(s2);
            when(transactionRepository.findAllById(List.of(s1, s2))).thenReturn(List.of(line1, line2));
            List<BankReconciliationBankMatch> members =
                    List.of(new BankReconciliationBankMatch(matchId, s1), new BankReconciliationBankMatch(matchId, s2));
            when(bankMatchRepository.findByBankTransactionIdInAndActiveTrue(List.of(s1, s2)))
                    .thenReturn(members);
            when(matchRepository.findByMatchIdAndReconciliationId(matchId, RECON_ID))
                    .thenReturn(Optional.of(acceptedHeader(matchId)));
            when(bankMatchRepository.findByMatchIdAndActiveTrue(matchId)).thenReturn(members);
            when(transactionRepository.findByStatementIdOrderBySourceRowNumberAsc(STATEMENT_ID))
                    .thenReturn(List.of(line1, line2));
            when(adjustmentRepository.findByReconciliation_ReconciliationId(RECON_ID))
                    .thenReturn(List.of());

            service.unmatch(
                    RECON_ID,
                    ReconciliationUnmatchRequest.builder()
                            .statementLineIds(List.of(s1, s2))
                            .build());

            assertThat(line1.getStatus()).isEqualTo(BankTransactionStatus.UNMATCHED);
            assertThat(line2.getStatus()).isEqualTo(BankTransactionStatus.UNMATCHED);
            // M7: the group is released by deactivating its members, never by deleting them.
            assertThat(members).noneMatch(BankReconciliationBankMatch::isActive);
        }

        @Test
        @DisplayName("lines from two different match groups are refused")
        void linesSpanningTwoGroups() {
            UUID s1 = UUID.randomUUID();
            UUID s2 = UUID.randomUUID();
            when(transactionRepository.findAllById(List.of(s1, s2)))
                    .thenReturn(List.of(matchedLine(s1), matchedLine(s2)));
            when(bankMatchRepository.findByBankTransactionIdInAndActiveTrue(List.of(s1, s2)))
                    .thenReturn(List.of(
                            new BankReconciliationBankMatch(UUID.randomUUID(), s1),
                            new BankReconciliationBankMatch(UUID.randomUUID(), s2)));

            // Ambiguous on purpose: releasing two groups because the operator selected lines
            // sloppily would undo a match they meant to keep.
            assertThatThrownBy(() -> service.unmatch(
                            RECON_ID,
                            ReconciliationUnmatchRequest.builder()
                                    .statementLineIds(List.of(s1, s2))
                                    .build()))
                    .isInstanceOf(InvalidRequestParameterException.class)
                    .hasMessageContaining("exactly one match group");
        }

        @Test
        @DisplayName("lines that are not matched at all resolve to no group")
        void unmatchedLinesResolveToNothing() {
            UUID s1 = UUID.randomUUID();
            when(transactionRepository.findAllById(List.of(s1)))
                    .thenReturn(List.of(statementLine(s1, new BigDecimal("100.0000"))));

            assertThatThrownBy(() -> service.unmatch(
                            RECON_ID,
                            ReconciliationUnmatchRequest.builder()
                                    .statementLineIds(List.of(s1))
                                    .build()))
                    .isInstanceOf(InvalidRequestParameterException.class)
                    .hasMessageContaining("found 0");
        }

        @Test
        @DisplayName("neither matchId nor statement lines is an unusable request")
        void emptyRequest() {
            assertThatThrownBy(() -> service.unmatch(
                            RECON_ID, ReconciliationUnmatchRequest.builder().build()))
                    .isInstanceOf(InvalidRequestParameterException.class);
        }

        @Test
        @DisplayName("a matchId with no lines in this reconciliation is not found")
        void matchIdWithNoLines() {
            UUID matchId = UUID.randomUUID();
            // No header of that id in this reconciliation (the repository answers Optional.empty()).

            assertThatThrownBy(() -> service.unmatch(
                            RECON_ID,
                            ReconciliationUnmatchRequest.builder()
                                    .matchId(matchId)
                                    .build()))
                    .isInstanceOf(ReconciliationNotFoundException.class);
        }

        private BankTransaction matchedLine(UUID id) {
            BankTransaction line = statementLine(id, new BigDecimal("100.0000"));
            line.setStatus(BankTransactionStatus.MATCHED);
            return line;
        }

        private BankReconciliationMatch acceptedHeader(UUID matchId) {
            BankReconciliationMatch header = new BankReconciliationMatch();
            header.setMatchId(matchId);
            header.setReconciliationId(RECON_ID);
            header.setState(MatchState.ACCEPTED);
            return header;
        }
    }

    @Nested
    @DisplayName("views")
    class Views {

        @Test
        @DisplayName("the report splits matched from outstanding and totals each side")
        void reportTotalsBothSides() {
            BankReconciliation recon = openReconciliation();
            when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));
            BankTransaction matched = statementLine(UUID.randomUUID(), new BigDecimal("600.0000"));
            matched.setStatus(BankTransactionStatus.MATCHED);
            BankTransaction outstanding = statementLine(UUID.randomUUID(), new BigDecimal("150.0000"));
            when(transactionRepository.findByStatementIdOrderBySourceRowNumberAsc(STATEMENT_ID))
                    .thenReturn(List.of(matched, outstanding));
            BankReconciliationAdjustment adjustment = new BankReconciliationAdjustment();
            adjustment.setAmount(new BigDecimal("-12.0000"));
            adjustment.setAdjustmentType(BankAdjustmentType.BANK_FEE);
            when(adjustmentRepository.findByReconciliation_ReconciliationId(RECON_ID))
                    .thenReturn(List.of(adjustment));

            ReconciliationReportResponse report = service.report(RECON_ID);

            assertThat(report.getTotalMatched()).isEqualByComparingTo("600.0000");
            assertThat(report.getTotalOutstanding()).isEqualByComparingTo("150.0000");
            assertThat(report.getTotalAdjustments()).isEqualByComparingTo("-12.0000");
            assertThat(report.getMatchedLineCount()).isEqualTo(1);
            assertThat(report.getOutstandingLineCount()).isEqualTo(1);
            assertThat(report.getOutstandingLines()).hasSize(1);
        }

        @Test
        @DisplayName("the audit trail lists import, each match group, each adjustment, and the finalize")
        void auditListsTheWholeTrail() {
            BankReconciliation recon = openReconciliation();
            recon.setCreatedAt(Instant.parse("2026-07-01T08:00:00Z"));
            recon.setStatus(ReconciliationStatus.FINALIZED);
            recon.setDifference(BigDecimal.ZERO);
            recon.setFinalizedAt(Instant.parse("2026-07-01T11:00:00Z"));
            recon.setFinalizedBy("controller");
            when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));
            BankReconciliationAdjustment adjustment = new BankReconciliationAdjustment();
            adjustment.setAmount(new BigDecimal("-12.0000"));
            adjustment.setAdjustmentType(BankAdjustmentType.BANK_FEE);
            adjustment.setJournalEntryId(UUID.randomUUID());
            adjustment.setCreatedAt(Instant.parse("2026-07-01T10:00:00Z"));
            when(adjustmentRepository.findByReconciliation_ReconciliationId(RECON_ID))
                    .thenReturn(List.of(adjustment));
            BankReconciliationGlMatch match = new BankReconciliationGlMatch();
            match.setMatchId(UUID.randomUUID());
            match.setGlLineId(UUID.randomUUID());
            match.setCreatedAt(Instant.parse("2026-07-01T09:00:00Z"));
            when(glMatchRepository.findByReconciliationIdAndActiveTrue(RECON_ID))
                    .thenReturn(List.of(match));

            ReconciliationAuditResponse audit = service.audit(RECON_ID);

            // Chronological: the trail reads as the story of the reconciliation, whatever order
            // the underlying tables returned the rows in.
            assertThat(audit.getEntries())
                    .extracting(ReconciliationAuditResponse.Entry::getAction)
                    .containsExactly("IMPORT", "MATCH", "ADJUSTMENT", "FINALIZE");
        }
    }

    @Nested
    @DisplayName("adjustments and lifecycle")
    class AdjustmentsAndLifecycle {

        @Test
        @DisplayName("a positive adjustment debits the reconciled cash account")
        void positiveAdjustmentDebitsCash() {
            BankReconciliation recon = openReconciliation();
            when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));
            when(reconciliationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            UUID counterAccountId = UUID.randomUUID();
            when(glMappingResolver.resolveGLAccount(
                            BankReconciliationServiceImpl.POSTING_CATEGORY,
                            "INTEREST_EARNED",
                            LocalDate.of(2026, 6, 30).atStartOfDay()))
                    .thenReturn(counterAccountId);
            JournalEntryResponse created = JournalEntryResponse.builder()
                    .journalEntryId(UUID.randomUUID())
                    .build();
            when(journalEntryService.createJournalEntry(any())).thenReturn(created);
            when(journalEntryService.postJournalEntry(created.getJournalEntryId(), null))
                    .thenReturn(JournalEntryResponse.builder()
                            .journalEntryId(UUID.randomUUID())
                            .build());
            when(transactionRepository.findByStatementIdOrderBySourceRowNumberAsc(STATEMENT_ID))
                    .thenReturn(List.of());
            when(adjustmentRepository.findByReconciliation_ReconciliationId(RECON_ID))
                    .thenReturn(List.of());

            service.addAdjustment(
                    RECON_ID,
                    ReconciliationAdjustmentRequest.builder()
                            .type(BankAdjustmentType.INTEREST_EARNED)
                            .amount(new BigDecimal("3.2500"))
                            // Blank on purpose: the entry must fall back to the generated
                            // description rather than carrying "(BANK_INTEREST): ".
                            .description("  ")
                            .build());

            ArgumentCaptor<JournalEntryCreateRequest> jeCaptor =
                    ArgumentCaptor.forClass(JournalEntryCreateRequest.class);
            verify(journalEntryService).createJournalEntry(jeCaptor.capture());
            JournalEntryCreateRequest je = jeCaptor.getValue();
            // Interest earned increases the bank balance: Dr cash / Cr income.
            assertThat(je.getLines().get(0).getGlAccountId()).isEqualTo(ACCOUNT_ID);
            assertThat(je.getLines().get(0).getDebitAmount()).isEqualByComparingTo("3.2500");
            assertThat(je.getLines().get(1).getGlAccountId()).isEqualTo(counterAccountId);
            assertThat(je.getLines().get(1).getCreditAmount()).isEqualByComparingTo("3.2500");
            assertThat(je.getDescription()).doesNotContain(": ");
        }

        @Test
        @DisplayName("finalizing twice is refused, not repeated")
        void finalizeTwiceRefused() {
            BankReconciliation recon = openReconciliation();
            recon.setStatus(ReconciliationStatus.FINALIZED);
            when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));

            assertThatThrownBy(() -> service.finalizeReconciliation(RECON_ID))
                    .isInstanceOf(ReconciliationAlreadyFinalizedException.class);
        }
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static GLAccount reconcilableAccount() {
        GLAccount account = new GLAccount(ACCOUNT_ID);
        account.setAccountCode("1000");
        account.setAccountName("Cash");
        account.setReconcilable(true);
        return account;
    }

    private static BankReconciliation openReconciliation() {
        BankReconciliation recon = new BankReconciliation(RECON_ID);
        recon.setGlAccountId(ACCOUNT_ID);
        recon.setAccountCode("1000");
        recon.setAccountName("Cash");
        recon.setStatementId(STATEMENT_ID);
        recon.setStatementStartDate(LocalDate.of(2026, 6, 1));
        recon.setStatementEndDate(LocalDate.of(2026, 6, 30));
        recon.setCurrency("USD");
        recon.setStatementClosingBalance(new BigDecimal("1000.0000"));
        recon.setGlEndingBalance(new BigDecimal("1000.0000"));
        recon.setStatus(ReconciliationStatus.IN_PROGRESS);
        return recon;
    }

    /** A statement line: a bank transaction of the reconciliation's statement (story S1, #2300). */
    private static BankTransaction statementLine(UUID id, BigDecimal amount) {
        BankTransaction line = new BankTransaction();
        line.setBankTransactionId(id);
        line.setGlAccountId(ACCOUNT_ID);
        line.setStatementId(STATEMENT_ID);
        line.setSourceRowNumber(1);
        line.setTransactionDate(LocalDate.of(2026, 6, 15));
        line.setSignedAmount(amount);
        line.setCurrency("USD");
        line.setStatus(BankTransactionStatus.UNMATCHED);
        return line;
    }

    private static JournalEntryLine postedGlLine(UUID id, BigDecimal debit, BigDecimal credit) {
        JournalEntryLine line = new JournalEntryLine();
        line.setLineId(id);
        line.setGlAccountId(ACCOUNT_ID);
        line.setDebitAmount(debit);
        line.setCreditAmount(credit);
        JournalEntry parent = new JournalEntry(UUID.randomUUID());
        parent.setStatus(JournalEntryStatus.POSTED);
        line.setJournalEntry(parent);
        return line;
    }
}
