package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.BankReconciliationImportRequest;
import com.positivity.accounting.internal.dto.BankReconciliationResponse;
import com.positivity.accounting.internal.dto.ReconciliationAdjustmentRequest;
import com.positivity.accounting.internal.dto.ReconciliationMatchRequest;
import com.positivity.accounting.internal.entity.BankReconciliation;
import com.positivity.accounting.internal.entity.BankReconciliationAdjustment;
import com.positivity.accounting.internal.entity.BankReconciliationLine;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.BankAdjustmentType;
import com.positivity.accounting.internal.enums.BankReconciliationLineStatus;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.enums.ReconciliationStatus;
import com.positivity.accounting.internal.exception.AccountNotReconcilableException;
import com.positivity.accounting.internal.exception.AdjustmentSignInvalidException;
import com.positivity.accounting.internal.exception.MatchAmountMismatchException;
import com.positivity.accounting.internal.exception.ReconciliationAlreadyFinalizedException;
import com.positivity.accounting.internal.exception.ReconciliationLineIneligibleException;
import com.positivity.accounting.internal.exception.ReconciliationNotBalancedException;
import com.positivity.accounting.internal.exception.ReconciliationNotFoundException;
import com.positivity.accounting.internal.repository.BankReconciliationAdjustmentRepository;
import com.positivity.accounting.internal.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.repository.BankReconciliationLineRepository;
import com.positivity.accounting.internal.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import com.positivity.accounting.service.GLMappingResolver;
import com.positivity.accounting.service.JournalEntryService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit tests for {@link BankReconciliationServiceImpl} (Story F2, issue #965):
 * CSV parsing, matching tolerance, N-to-1, finalize balance gate, adjustment JE
 * posting, and not-reconcilable rejection.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BankReconciliationServiceImpl Tests")
class BankReconciliationServiceTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("5eed0acc-0000-4000-8000-000000001000");
    private static final UUID RECON_ID = UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678900001");
    private static final UUID COUNTER_ACCOUNT_ID = UUID.fromString("5eed0acc-0000-4000-8000-000000006010");

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private BankReconciliationRepository reconciliationRepository;

    @Mock
    private BankReconciliationLineRepository lineRepository;

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
                lineRepository,
                adjustmentRepository,
                glMatchRepository,
                glAccountRepository,
                journalEntryLineRepository,
                glMappingResolver,
                journalEntryService);
    }

    private static GLAccount reconcilableAccount() {
        GLAccount account = new GLAccount(ACCOUNT_ID);
        account.setAccountCode("1000");
        account.setAccountName("Cash");
        account.setReconcilable(true);
        return account;
    }

    private BankReconciliation openReconciliation() {
        BankReconciliation recon = new BankReconciliation(RECON_ID);
        recon.setGlAccountId(ACCOUNT_ID);
        recon.setAccountCode("1000");
        recon.setAccountName("Cash");
        recon.setStatementDate(LocalDate.of(2026, 6, 30));
        recon.setPeriodStartDate(LocalDate.of(2026, 6, 1));
        recon.setPeriodEndDate(LocalDate.of(2026, 6, 30));
        recon.setCurrency("USD");
        recon.setStatementEndingBalance(new BigDecimal("1000.0000"));
        recon.setGlEndingBalance(new BigDecimal("1000.0000"));
        recon.setStatus(ReconciliationStatus.IN_PROGRESS);
        return recon;
    }

    @Test
    @DisplayName("import parses CSV, snapshots GL balance, and creates IN_PROGRESS reconciliation")
    void importParsesCsv() {
        when(glAccountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(reconcilableAccount()));
        when(journalEntryLineRepository.getAccountBalanceAsOf(eq(ACCOUNT_ID), any()))
                .thenReturn(new BigDecimal("1500.0000"));
        when(reconciliationRepository.save(any(BankReconciliation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(lineRepository.findByReconciliation_ReconciliationId(any())).thenReturn(List.of());
        when(adjustmentRepository.findByReconciliation_ReconciliationId(any())).thenReturn(List.of());

        BankReconciliationImportRequest request = BankReconciliationImportRequest.builder()
                .glAccountId(ACCOUNT_ID)
                .periodStartDate(LocalDate.of(2026, 6, 1))
                .periodEndDate(LocalDate.of(2026, 6, 30))
                .statementDate(LocalDate.of(2026, 6, 30))
                .statementEndingBalance(new BigDecimal("2000.0000"))
                .currency("USD")
                .csv("date,description,amount,reference\n2026-06-15,ACH DEPOSIT,1500.00,REF-1\n"
                        + "2026-06-20,SERVICE FEE,(12.50),REF-2")
                .build();

        ArgumentCaptor<BankReconciliation> captor = ArgumentCaptor.forClass(BankReconciliation.class);
        BankReconciliationResponse response = service.importStatement(request);

        verify(reconciliationRepository).save(captor.capture());
        BankReconciliation saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ReconciliationStatus.IN_PROGRESS);
        assertThat(saved.getGlEndingBalance()).isEqualByComparingTo("1500.0000");
        assertThat(saved.getStatementLines()).hasSize(2);
        // second line uses parentheses negative
        assertThat(saved.getStatementLines().get(1).getAmount()).isEqualByComparingTo("-12.50");
        // difference = statement (2000) - glEnding (1500) = 500 at import
        assertThat(saved.getDifference()).isEqualByComparingTo("500.0000");
        assertThat(response.getGlAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(response.getStatus()).isEqualTo(ReconciliationStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("import rejects a non-reconcilable account with ACCOUNT_NOT_RECONCILABLE")
    void importRejectsNonReconcilable() {
        GLAccount account = new GLAccount(ACCOUNT_ID);
        account.setAccountCode("4000");
        account.setReconcilable(false);
        when(glAccountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

        BankReconciliationImportRequest request = BankReconciliationImportRequest.builder()
                .glAccountId(ACCOUNT_ID)
                .periodStartDate(LocalDate.of(2026, 6, 1))
                .periodEndDate(LocalDate.of(2026, 6, 30))
                .statementDate(LocalDate.of(2026, 6, 30))
                .statementEndingBalance(new BigDecimal("2000.0000"))
                .currency("USD")
                .csv("2026-06-15,ACH DEPOSIT,1500.00,REF-1")
                .build();

        assertThatThrownBy(() -> service.importStatement(request)).isInstanceOf(AccountNotReconcilableException.class);
        verify(reconciliationRepository, never()).save(any());
    }

    @Test
    @DisplayName("import rejects a malformed CSV amount with IllegalArgumentException (400)")
    void importRejectsMalformedCsv() {
        when(glAccountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(reconcilableAccount()));

        BankReconciliationImportRequest request = BankReconciliationImportRequest.builder()
                .glAccountId(ACCOUNT_ID)
                .periodStartDate(LocalDate.of(2026, 6, 1))
                .periodEndDate(LocalDate.of(2026, 6, 30))
                .statementDate(LocalDate.of(2026, 6, 30))
                .statementEndingBalance(new BigDecimal("2000.0000"))
                .currency("USD")
                .csv("2026-06-15,ACH DEPOSIT,NOT_A_NUMBER,REF-1")
                .build();

        assertThatThrownBy(() -> service.importStatement(request)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("import does not silently drop a header-less first row with a bad date")
    void importRejectsHeaderlessBadFirstRowDate() {
        when(glAccountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(reconcilableAccount()));

        // No header row; first row has an invalid date but a valid numeric amount, so it must NOT be
        // treated as a header and silently dropped — it must raise a clear 400.
        BankReconciliationImportRequest request = BankReconciliationImportRequest.builder()
                .glAccountId(ACCOUNT_ID)
                .periodStartDate(LocalDate.of(2026, 6, 1))
                .periodEndDate(LocalDate.of(2026, 6, 30))
                .statementDate(LocalDate.of(2026, 6, 30))
                .statementEndingBalance(new BigDecimal("2000.0000"))
                .currency("USD")
                .csv("2026-13-45,BAD DATE,100.00,REF-1")
                .build();

        assertThatThrownBy(() -> service.importStatement(request)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("match links N statement lines to 1 GL line when amounts net within tolerance")
    void matchNToOneWithinTolerance() {
        BankReconciliation recon = openReconciliation();
        when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));
        when(reconciliationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        UUID g1 = UUID.randomUUID();
        BankReconciliationLine line1 = statementLine(s1, new BigDecimal("600.0000"));
        BankReconciliationLine line2 = statementLine(s2, new BigDecimal("400.005"));
        when(lineRepository.findAllById(List.of(s1, s2))).thenReturn(List.of(line1, line2));

        JournalEntryLine glLine = postedGlLine(g1, new BigDecimal("1000.0000"), BigDecimal.ZERO);
        when(journalEntryLineRepository.findAllById(List.of(g1))).thenReturn(List.of(glLine));
        when(glMatchRepository.existsByGlLineId(g1)).thenReturn(false);
        when(lineRepository.findByReconciliation_ReconciliationId(RECON_ID)).thenReturn(List.of(line1, line2));
        when(adjustmentRepository.findByReconciliation_ReconciliationId(RECON_ID))
                .thenReturn(List.of());

        ReconciliationMatchRequest request = ReconciliationMatchRequest.builder()
                .statementLineIds(List.of(s1, s2))
                .glLineIds(List.of(g1))
                .build();

        service.match(RECON_ID, request);

        assertThat(line1.getStatus()).isEqualTo(BankReconciliationLineStatus.MATCHED);
        assertThat(line2.getStatus()).isEqualTo(BankReconciliationLineStatus.MATCHED);
        assertThat(line1.getMatchId()).isNotNull().isEqualTo(line2.getMatchId());
        verify(glMatchRepository).saveAllAndFlush(anyList());
    }

    @Test
    @DisplayName("match maps a concurrent unique(gl_line_id) violation to RECONCILIATION_LINE_INELIGIBLE (409)")
    void matchConcurrentConstraintViolationMappedTo409() {
        BankReconciliation recon = openReconciliation();
        when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));

        UUID s1 = UUID.randomUUID();
        UUID g1 = UUID.randomUUID();
        BankReconciliationLine line1 = statementLine(s1, new BigDecimal("500.0000"));
        when(lineRepository.findAllById(List.of(s1))).thenReturn(List.of(line1));
        JournalEntryLine glLine = postedGlLine(g1, new BigDecimal("500.0000"), BigDecimal.ZERO);
        when(journalEntryLineRepository.findAllById(List.of(g1))).thenReturn(List.of(glLine));
        when(glMatchRepository.existsByGlLineId(g1)).thenReturn(false);
        // A racing match committed first; the unique(gl_line_id) index rejects this one.
        when(glMatchRepository.saveAllAndFlush(anyList()))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        ReconciliationMatchRequest request = ReconciliationMatchRequest.builder()
                .statementLineIds(List.of(s1))
                .glLineIds(List.of(g1))
                .build();

        assertThatThrownBy(() -> service.match(RECON_ID, request))
                .isInstanceOf(ReconciliationLineIneligibleException.class);
    }

    @Test
    @DisplayName("unmatch by statementLineIds rejects a line from another reconciliation with 404")
    void unmatchRejectsCrossReconciliationLine() {
        BankReconciliation recon = openReconciliation();
        when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));

        UUID foreignLineId = UUID.randomUUID();
        BankReconciliationLine foreign = statementLine(foreignLineId, new BigDecimal("100.0000"));
        foreign.setReconciliation(new BankReconciliation(UUID.randomUUID())); // different reconciliation
        foreign.setStatus(BankReconciliationLineStatus.MATCHED);
        foreign.setMatchId(UUID.randomUUID());
        when(lineRepository.findAllById(List.of(foreignLineId))).thenReturn(List.of(foreign));

        com.positivity.accounting.internal.dto.ReconciliationUnmatchRequest request =
                com.positivity.accounting.internal.dto.ReconciliationUnmatchRequest.builder()
                        .statementLineIds(List.of(foreignLineId))
                        .build();

        assertThatThrownBy(() -> service.unmatch(RECON_ID, request))
                .isInstanceOf(ReconciliationNotFoundException.class);
    }

    @Test
    @DisplayName("match rejects sets that do not net with MATCH_AMOUNT_MISMATCH")
    void matchRejectsMismatch() {
        BankReconciliation recon = openReconciliation();
        when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));

        UUID s1 = UUID.randomUUID();
        UUID g1 = UUID.randomUUID();
        BankReconciliationLine line1 = statementLine(s1, new BigDecimal("600.0000"));
        when(lineRepository.findAllById(List.of(s1))).thenReturn(List.of(line1));
        JournalEntryLine glLine = postedGlLine(g1, new BigDecimal("500.0000"), BigDecimal.ZERO);
        when(journalEntryLineRepository.findAllById(List.of(g1))).thenReturn(List.of(glLine));
        when(glMatchRepository.existsByGlLineId(g1)).thenReturn(false);

        ReconciliationMatchRequest request = ReconciliationMatchRequest.builder()
                .statementLineIds(List.of(s1))
                .glLineIds(List.of(g1))
                .build();

        assertThatThrownBy(() -> service.match(RECON_ID, request)).isInstanceOf(MatchAmountMismatchException.class);
    }

    @Test
    @DisplayName("match rejects a GL line already reconciled elsewhere with RECONCILIATION_LINE_INELIGIBLE")
    void matchRejectsGlLineAlreadyMatched() {
        BankReconciliation recon = openReconciliation();
        when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));

        UUID s1 = UUID.randomUUID();
        UUID g1 = UUID.randomUUID();
        BankReconciliationLine line1 = statementLine(s1, new BigDecimal("500.0000"));
        when(lineRepository.findAllById(List.of(s1))).thenReturn(List.of(line1));
        JournalEntryLine glLine = postedGlLine(g1, new BigDecimal("500.0000"), BigDecimal.ZERO);
        when(journalEntryLineRepository.findAllById(List.of(g1))).thenReturn(List.of(glLine));
        when(glMatchRepository.existsByGlLineId(g1)).thenReturn(true);

        ReconciliationMatchRequest request = ReconciliationMatchRequest.builder()
                .statementLineIds(List.of(s1))
                .glLineIds(List.of(g1))
                .build();

        assertThatThrownBy(() -> service.match(RECON_ID, request))
                .isInstanceOf(ReconciliationLineIneligibleException.class);
    }

    @Test
    @DisplayName("unmatch by matchId returns lines to UNMATCHED and releases GL lines")
    void unmatchReleasesLines() {
        BankReconciliation recon = openReconciliation();
        when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));
        when(reconciliationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UUID matchId = UUID.randomUUID();
        UUID s1 = UUID.randomUUID();
        BankReconciliationLine line1 = statementLine(s1, new BigDecimal("500.0000"));
        line1.setStatus(BankReconciliationLineStatus.MATCHED);
        line1.setMatchId(matchId);
        when(lineRepository.findByReconciliation_ReconciliationIdAndMatchId(RECON_ID, matchId))
                .thenReturn(List.of(line1));
        when(lineRepository.findByReconciliation_ReconciliationId(RECON_ID)).thenReturn(List.of(line1));
        when(adjustmentRepository.findByReconciliation_ReconciliationId(RECON_ID))
                .thenReturn(List.of());

        com.positivity.accounting.internal.dto.ReconciliationUnmatchRequest request =
                com.positivity.accounting.internal.dto.ReconciliationUnmatchRequest.builder()
                        .matchId(matchId)
                        .build();

        service.unmatch(RECON_ID, request);

        assertThat(line1.getStatus()).isEqualTo(BankReconciliationLineStatus.UNMATCHED);
        assertThat(line1.getMatchId()).isNull();
        verify(glMatchRepository).deleteByReconciliationIdAndMatchId(RECON_ID, matchId);
    }

    @Test
    @DisplayName("adjustment with a zero amount is rejected")
    void adjustmentZeroAmountRejected() {
        BankReconciliation recon = openReconciliation();
        when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));

        ReconciliationAdjustmentRequest request = ReconciliationAdjustmentRequest.builder()
                .type(BankAdjustmentType.OTHER)
                .amount(new BigDecimal("0.0000"))
                .build();

        assertThatThrownBy(() -> service.addAdjustment(RECON_ID, request)).isInstanceOf(IllegalArgumentException.class);
        verify(journalEntryService, never()).createJournalEntry(any());
    }

    @Test
    @DisplayName("a positive BANK_FEE is rejected — a fee may only reduce cash (D2 sign guard)")
    void adjustmentPositiveBankFeeRejected() {
        BankReconciliation recon = openReconciliation();
        when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));

        ReconciliationAdjustmentRequest request = ReconciliationAdjustmentRequest.builder()
                .type(BankAdjustmentType.BANK_FEE)
                .amount(new BigDecimal("12.5000"))
                .build();

        assertThatThrownBy(() -> service.addAdjustment(RECON_ID, request))
                .isInstanceOf(AdjustmentSignInvalidException.class);
        verify(journalEntryService, never()).createJournalEntry(any());
    }

    @Test
    @DisplayName("a negative INTEREST_EARNED is rejected — interest may only increase cash (D2 sign guard)")
    void adjustmentNegativeInterestRejected() {
        BankReconciliation recon = openReconciliation();
        when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));

        ReconciliationAdjustmentRequest request = ReconciliationAdjustmentRequest.builder()
                .type(BankAdjustmentType.INTEREST_EARNED)
                .amount(new BigDecimal("-5.0000"))
                .build();

        assertThatThrownBy(() -> service.addAdjustment(RECON_ID, request))
                .isInstanceOf(AdjustmentSignInvalidException.class);
        verify(journalEntryService, never()).createJournalEntry(any());
    }

    @Test
    @DisplayName("finalize succeeds with matched lines present (matched lines do not affect the gate)")
    void finalizeWithMatchedLinesBalanced() {
        BankReconciliation recon = openReconciliation(); // statement 1000, glEnding 1000
        BankReconciliationLine matched = statementLine(UUID.randomUUID(), new BigDecimal("750.0000"));
        matched.setStatus(BankReconciliationLineStatus.MATCHED);
        when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));
        when(reconciliationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lineRepository.findByReconciliation_ReconciliationId(RECON_ID)).thenReturn(List.of(matched));
        when(adjustmentRepository.findByReconciliation_ReconciliationId(RECON_ID))
                .thenReturn(List.of());

        BankReconciliationResponse response = service.finalizeReconciliation(RECON_ID);

        // Under the corrected identity (statement − (glEnding + Σ adjustments)), a 750 matched line does
        // NOT push the account out of balance — it is already reflected in glEndingBalance.
        assertThat(response.getStatus()).isEqualTo(ReconciliationStatus.FINALIZED);
        assertThat(recon.getDifference()).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("adjustment posts a real balanced JE and stores the journalEntryId")
    void adjustmentPostsJe() {
        BankReconciliation recon = openReconciliation();
        when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));
        when(reconciliationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        LocalDateTime txDate = LocalDate.of(2026, 6, 30).atStartOfDay();
        when(glMappingResolver.resolveGLAccount(BankReconciliationServiceImpl.POSTING_CATEGORY, "BANK_FEE", txDate))
                .thenReturn(COUNTER_ACCOUNT_ID);
        JournalEntry created = new JournalEntry(UUID.randomUUID());
        JournalEntry posted = new JournalEntry(UUID.randomUUID());
        when(journalEntryService.createJournalEntry(any())).thenReturn(created);
        when(journalEntryService.postJournalEntry(created.getJournalEntryId(), null))
                .thenReturn(posted);
        when(lineRepository.findByReconciliation_ReconciliationId(RECON_ID)).thenReturn(List.of());
        when(adjustmentRepository.findByReconciliation_ReconciliationId(RECON_ID))
                .thenReturn(List.of());

        ReconciliationAdjustmentRequest request = ReconciliationAdjustmentRequest.builder()
                .type(BankAdjustmentType.BANK_FEE)
                .amount(new BigDecimal("-12.5000"))
                .description("Monthly service charge")
                .build();

        service.addAdjustment(RECON_ID, request);

        ArgumentCaptor<JournalEntry> jeCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryService).createJournalEntry(jeCaptor.capture());
        JournalEntry je = jeCaptor.getValue();
        assertThat(je.getLines()).hasSize(2);
        BigDecimal totalDebit =
                je.getLines().stream().map(JournalEntryLine::getDebitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit =
                je.getLines().stream().map(JournalEntryLine::getCreditAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebit).isEqualByComparingTo(totalCredit);

        ArgumentCaptor<BankReconciliationAdjustment> adjCaptor =
                ArgumentCaptor.forClass(BankReconciliationAdjustment.class);
        verify(adjustmentRepository).save(adjCaptor.capture());
        assertThat(adjCaptor.getValue().getJournalEntryId()).isEqualTo(posted.getJournalEntryId());
    }

    @Test
    @DisplayName("finalize succeeds when balanced (difference within tolerance)")
    void finalizeSucceedsWhenBalanced() {
        BankReconciliation recon = openReconciliation(); // statement 1000, glEnding 1000
        when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));
        when(reconciliationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lineRepository.findByReconciliation_ReconciliationId(RECON_ID)).thenReturn(List.of());
        when(adjustmentRepository.findByReconciliation_ReconciliationId(RECON_ID))
                .thenReturn(List.of());

        BankReconciliationResponse response = service.finalizeReconciliation(RECON_ID);

        assertThat(response.getStatus()).isEqualTo(ReconciliationStatus.FINALIZED);
        assertThat(recon.getFinalizedAt()).isNotNull();
    }

    @Test
    @DisplayName("finalize rejects an unbalanced reconciliation with RECONCILIATION_NOT_BALANCED")
    void finalizeRejectsUnbalanced() {
        BankReconciliation recon = openReconciliation();
        recon.setStatementEndingBalance(new BigDecimal("1500.0000")); // 500 off, no adjustments
        when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));
        when(adjustmentRepository.findByReconciliation_ReconciliationId(RECON_ID))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.finalizeReconciliation(RECON_ID))
                .isInstanceOf(ReconciliationNotBalancedException.class)
                .satisfies(e -> assertThat(((ReconciliationNotBalancedException) e).getDifference())
                        .isEqualByComparingTo("500.0000"));
    }

    @Test
    @DisplayName("mutating a FINALIZED reconciliation is rejected with RECONCILIATION_ALREADY_FINALIZED")
    void mutateFinalizedRejected() {
        BankReconciliation recon = openReconciliation();
        recon.setStatus(ReconciliationStatus.FINALIZED);
        when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));

        ReconciliationAdjustmentRequest request = ReconciliationAdjustmentRequest.builder()
                .type(BankAdjustmentType.OTHER)
                .amount(new BigDecimal("5.0000"))
                .build();

        assertThatThrownBy(() -> service.addAdjustment(RECON_ID, request))
                .isInstanceOf(ReconciliationAlreadyFinalizedException.class);
    }

    @Test
    @DisplayName("get throws RECONCILIATION_NOT_FOUND for an unknown id")
    void getNotFound() {
        when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(RECON_ID)).isInstanceOf(ReconciliationNotFoundException.class);
    }

    private static BankReconciliationLine statementLine(UUID id, BigDecimal amount) {
        BankReconciliationLine line = new BankReconciliationLine();
        line.setLineId(id);
        line.setReconciliation(new BankReconciliation(RECON_ID));
        line.setLineNumber(1);
        line.setLineDate(LocalDate.of(2026, 6, 15));
        line.setAmount(amount);
        line.setStatus(BankReconciliationLineStatus.UNMATCHED);
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
