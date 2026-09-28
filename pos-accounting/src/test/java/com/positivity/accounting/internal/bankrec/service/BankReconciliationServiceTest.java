package com.positivity.accounting.internal.bankrec.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.dto.BankReconciliationResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationAdjustmentRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationApiStatus;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationMatchRequest;
import com.positivity.accounting.internal.bankrec.dto.StatementLineApiStatus;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationAdjustment;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationBankMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationGlMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationMatch;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.AdjustmentStatus;
import com.positivity.accounting.internal.bankrec.enums.BankAdjustmentType;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.MatchKind;
import com.positivity.accounting.internal.bankrec.enums.MatchOrigin;
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
import com.positivity.accounting.internal.exception.AdjustmentSignInvalidException;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.exception.MatchAmountMismatchException;
import com.positivity.accounting.internal.exception.ReconciliationAlreadyFinalizedException;
import com.positivity.accounting.internal.exception.ReconciliationLineIneligibleException;
import com.positivity.accounting.internal.exception.ReconciliationNotBalancedException;
import com.positivity.accounting.internal.exception.ReconciliationNotFoundException;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import com.positivity.accounting.internal.service.GLMappingResolver;
import com.positivity.accounting.internal.service.JournalEntryService;
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
    private static final UUID STATEMENT_ID = UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678900002");
    private static final UUID COUNTER_ACCOUNT_ID = UUID.fromString("5eed0acc-0000-4000-8000-000000006010");

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
        recon.setStatementId(STATEMENT_ID);
        recon.setStatementStartDate(LocalDate.of(2026, 6, 1));
        recon.setStatementEndDate(LocalDate.of(2026, 6, 30));
        recon.setCurrency("USD");
        recon.setStatementClosingBalance(new BigDecimal("1000.0000"));
        recon.setGlEndingBalance(new BigDecimal("1000.0000"));
        recon.setStatus(ReconciliationStatus.IN_PROGRESS);
        return recon;
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
        BankTransaction line1 = statementLine(s1, new BigDecimal("600.0000"));
        BankTransaction line2 = statementLine(s2, new BigDecimal("400.005"));
        when(transactionRepository.findAllById(List.of(s1, s2))).thenReturn(List.of(line1, line2));

        JournalEntryLine glLine = postedGlLine(g1, new BigDecimal("1000.0000"), BigDecimal.ZERO);
        when(journalEntryLineRepository.findAllById(List.of(g1))).thenReturn(List.of(glLine));
        when(glMatchRepository.existsByGlLineIdAndActiveTrue(g1)).thenReturn(false);
        stubMatchHeaderSave();
        when(transactionRepository.findByStatementIdOrderBySourceRowNumberAsc(STATEMENT_ID))
                .thenReturn(List.of(line1, line2));
        when(adjustmentRepository.findByReconciliation_ReconciliationId(RECON_ID))
                .thenReturn(List.of());

        ReconciliationMatchRequest request = ReconciliationMatchRequest.builder()
                .statementLineIds(List.of(s1, s2))
                .glLineIds(List.of(g1))
                .build();

        service.match(RECON_ID, request);

        assertThat(line1.getStatus()).isEqualTo(BankTransactionStatus.MATCHED);
        assertThat(line2.getStatus()).isEqualTo(BankTransactionStatus.MATCHED);
        verify(glMatchRepository).saveAllAndFlush(anyList());

        // Story S1 (#2300): one ACCEPTED USER header for the group, N bank members : 1 ledger member.
        ArgumentCaptor<BankReconciliationMatch> headerCaptor = ArgumentCaptor.forClass(BankReconciliationMatch.class);
        verify(matchRepository).save(headerCaptor.capture());
        BankReconciliationMatch header = headerCaptor.getValue();
        assertThat(header.getMatchId()).isEqualTo(MATCH_ID);
        assertThat(header.getReconciliationId()).isEqualTo(RECON_ID);
        assertThat(header.getMatchKind()).isEqualTo(MatchKind.MANY_TO_ONE);
        assertThat(header.getState()).isEqualTo(MatchState.ACCEPTED);
        assertThat(header.getOrigin()).isEqualTo(MatchOrigin.USER);
        assertThat(header.getBankTotal()).isEqualByComparingTo("1000.005");
        assertThat(header.getLedgerTotal()).isEqualByComparingTo("1000.0000");
        assertThat(header.getToleranceUsed()).isEqualByComparingTo("0.005");
        assertThat(header.getAcceptedAt()).isEqualTo(header.getProposedAt()).isEqualTo(clock.instant());
        assertThat(header.getAcceptedBy()).isEqualTo(header.getProposedBy());
        List<BankReconciliationBankMatch> bankMembers = savedBankMembers();
        assertThat(bankMembers)
                .extracting(BankReconciliationBankMatch::getBankTransactionId)
                .containsExactly(s1, s2);
        assertThat(bankMembers).allSatisfy(m -> {
            assertThat(m.getMatchId()).isEqualTo(MATCH_ID);
            assertThat(m.isActive()).isTrue();
        });
    }

    @Test
    @DisplayName("match maps a concurrent unique(gl_line_id) violation to RECONCILIATION_LINE_INELIGIBLE (409)")
    void matchConcurrentConstraintViolationMappedTo409() {
        BankReconciliation recon = openReconciliation();
        when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));

        UUID s1 = UUID.randomUUID();
        UUID g1 = UUID.randomUUID();
        BankTransaction line1 = statementLine(s1, new BigDecimal("500.0000"));
        when(transactionRepository.findAllById(List.of(s1))).thenReturn(List.of(line1));
        JournalEntryLine glLine = postedGlLine(g1, new BigDecimal("500.0000"), BigDecimal.ZERO);
        when(journalEntryLineRepository.findAllById(List.of(g1))).thenReturn(List.of(glLine));
        when(glMatchRepository.existsByGlLineIdAndActiveTrue(g1)).thenReturn(false);
        stubMatchHeaderSave();
        // A racing match committed first; the partial unique(gl_line_id) WHERE active rejects this one.
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
        BankTransaction foreign = statementLine(foreignLineId, new BigDecimal("100.0000"));
        foreign.setStatementId(UUID.randomUUID()); // another reconciliation's statement
        foreign.setStatus(BankTransactionStatus.MATCHED);
        when(transactionRepository.findAllById(List.of(foreignLineId))).thenReturn(List.of(foreign));

        com.positivity.accounting.internal.bankrec.dto.ReconciliationUnmatchRequest request =
                com.positivity.accounting.internal.bankrec.dto.ReconciliationUnmatchRequest.builder()
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
        BankTransaction line1 = statementLine(s1, new BigDecimal("600.0000"));
        when(transactionRepository.findAllById(List.of(s1))).thenReturn(List.of(line1));
        JournalEntryLine glLine = postedGlLine(g1, new BigDecimal("500.0000"), BigDecimal.ZERO);
        when(journalEntryLineRepository.findAllById(List.of(g1))).thenReturn(List.of(glLine));
        when(glMatchRepository.existsByGlLineIdAndActiveTrue(g1)).thenReturn(false);

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
        BankTransaction line1 = statementLine(s1, new BigDecimal("500.0000"));
        when(transactionRepository.findAllById(List.of(s1))).thenReturn(List.of(line1));
        JournalEntryLine glLine = postedGlLine(g1, new BigDecimal("500.0000"), BigDecimal.ZERO);
        when(journalEntryLineRepository.findAllById(List.of(g1))).thenReturn(List.of(glLine));
        when(glMatchRepository.existsByGlLineIdAndActiveTrue(g1)).thenReturn(true);

        ReconciliationMatchRequest request = ReconciliationMatchRequest.builder()
                .statementLineIds(List.of(s1))
                .glLineIds(List.of(g1))
                .build();

        assertThatThrownBy(() -> service.match(RECON_ID, request))
                .isInstanceOf(ReconciliationLineIneligibleException.class);
    }

    @Test
    @DisplayName("unmatch by matchId returns lines to UNMATCHED and releases GL lines — without deleting (M7)")
    void unmatchReleasesLines() {
        BankReconciliation recon = openReconciliation();
        when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));
        when(reconciliationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UUID matchId = UUID.randomUUID();
        UUID s1 = UUID.randomUUID();
        BankTransaction line1 = statementLine(s1, new BigDecimal("500.0000"));
        line1.setStatus(BankTransactionStatus.MATCHED);
        BankReconciliationMatch header = new BankReconciliationMatch();
        header.setMatchId(matchId);
        header.setReconciliationId(RECON_ID);
        header.setState(MatchState.ACCEPTED);
        when(matchRepository.findByMatchIdAndReconciliationId(matchId, RECON_ID))
                .thenReturn(Optional.of(header));
        BankReconciliationBankMatch bankMember = new BankReconciliationBankMatch(matchId, s1);
        when(bankMatchRepository.findByMatchIdAndActiveTrue(matchId)).thenReturn(List.of(bankMember));
        when(transactionRepository.findAllById(List.of(s1))).thenReturn(List.of(line1));
        BankReconciliationGlMatch glMember = new BankReconciliationGlMatch();
        glMember.setMatchId(matchId);
        glMember.setGlLineId(UUID.randomUUID());
        glMember.setActive(true);
        when(glMatchRepository.findByMatchIdAndActiveTrue(matchId)).thenReturn(List.of(glMember));
        when(transactionRepository.findByStatementIdOrderBySourceRowNumberAsc(STATEMENT_ID))
                .thenReturn(List.of(line1));
        when(adjustmentRepository.findByReconciliation_ReconciliationId(RECON_ID))
                .thenReturn(List.of());

        com.positivity.accounting.internal.bankrec.dto.ReconciliationUnmatchRequest request =
                com.positivity.accounting.internal.bankrec.dto.ReconciliationUnmatchRequest.builder()
                        .matchId(matchId)
                        .build();

        BankReconciliationResponse response = service.unmatch(RECON_ID, request);

        assertThat(line1.getStatus()).isEqualTo(BankTransactionStatus.UNMATCHED);
        assertThat(response.getStatementLines()).singleElement().satisfies(line -> {
            assertThat(line.getStatus()).isEqualTo(StatementLineApiStatus.UNMATCHED);
            assertThat(line.getMatchId()).isNull();
        });
        // M7: nothing is deleted — the members go inactive and the header records the unmatch.
        assertThat(bankMember.isActive()).isFalse();
        assertThat(glMember.isActive()).isFalse();
        assertThat(header.getState()).isEqualTo(MatchState.UNMATCHED);
        assertThat(header.getUnmatchedAt()).isEqualTo(clock.instant());
        assertThat(header.getUnmatchedBy()).isNotBlank();
        verify(matchRepository).save(header);
        verify(glMatchRepository, never()).deleteAll(anyList());
        verify(bankMatchRepository, never()).deleteAll(anyList());
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

        assertThatThrownBy(() -> service.addAdjustment(RECON_ID, request))
                .isInstanceOf(InvalidRequestParameterException.class);
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
        BankTransaction matched = statementLine(UUID.randomUUID(), new BigDecimal("750.0000"));
        matched.setStatus(BankTransactionStatus.MATCHED);
        when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));
        when(reconciliationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.findByStatementIdOrderBySourceRowNumberAsc(STATEMENT_ID))
                .thenReturn(List.of(matched));
        when(adjustmentRepository.findByReconciliation_ReconciliationId(RECON_ID))
                .thenReturn(List.of());

        BankReconciliationResponse response = service.finalizeReconciliation(RECON_ID);

        // Under the corrected identity (statement − (glEnding + Σ adjustments)), a 750 matched line does
        // NOT push the account out of balance — it is already reflected in glEndingBalance.
        assertThat(response.getStatus()).isEqualTo(ReconciliationApiStatus.FINALIZED);
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
        JournalEntryResponse created =
                JournalEntryResponse.builder().journalEntryId(UUID.randomUUID()).build();
        JournalEntryResponse posted =
                JournalEntryResponse.builder().journalEntryId(UUID.randomUUID()).build();
        when(journalEntryService.createJournalEntry(any())).thenReturn(created);
        when(journalEntryService.postJournalEntry(created.getJournalEntryId(), null))
                .thenReturn(posted);
        when(transactionRepository.findByStatementIdOrderBySourceRowNumberAsc(STATEMENT_ID))
                .thenReturn(List.of());
        when(adjustmentRepository.findByReconciliation_ReconciliationId(RECON_ID))
                .thenReturn(List.of());

        ReconciliationAdjustmentRequest request = ReconciliationAdjustmentRequest.builder()
                .type(BankAdjustmentType.BANK_FEE)
                .amount(new BigDecimal("-12.5000"))
                .description("Monthly service charge")
                .build();

        service.addAdjustment(RECON_ID, request);

        ArgumentCaptor<JournalEntryCreateRequest> jeCaptor = ArgumentCaptor.forClass(JournalEntryCreateRequest.class);
        verify(journalEntryService).createJournalEntry(jeCaptor.capture());
        JournalEntryCreateRequest je = jeCaptor.getValue();
        assertThat(je.getLines()).hasSize(2);
        BigDecimal totalDebit = je.getLines().stream()
                .map(JournalEntryCreateRequest.JournalEntryLineRequest::getDebitAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = je.getLines().stream()
                .map(JournalEntryCreateRequest.JournalEntryLineRequest::getCreditAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebit).isEqualByComparingTo(totalCredit);

        ArgumentCaptor<BankReconciliationAdjustment> adjCaptor =
                ArgumentCaptor.forClass(BankReconciliationAdjustment.class);
        verify(adjustmentRepository).save(adjCaptor.capture());
        assertThat(adjCaptor.getValue().getJournalEntryId()).isEqualTo(posted.getJournalEntryId());
    }

    @Test
    @DisplayName("an F2 OTHER adjustment without a link or justification posts as today (link rule is S4's)")
    void otherAdjustmentWithoutLinkPostsAsToday() {
        BankReconciliation recon = openReconciliation();
        when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));
        when(reconciliationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        LocalDateTime txDate = LocalDate.of(2026, 6, 30).atStartOfDay();
        when(glMappingResolver.resolveGLAccount(BankReconciliationServiceImpl.POSTING_CATEGORY, "OTHER", txDate))
                .thenReturn(COUNTER_ACCOUNT_ID);
        JournalEntryResponse created =
                JournalEntryResponse.builder().journalEntryId(UUID.randomUUID()).build();
        JournalEntryResponse posted =
                JournalEntryResponse.builder().journalEntryId(UUID.randomUUID()).build();
        when(journalEntryService.createJournalEntry(any())).thenReturn(created);
        when(journalEntryService.postJournalEntry(created.getJournalEntryId(), null))
                .thenReturn(posted);
        when(transactionRepository.findByStatementIdOrderBySourceRowNumberAsc(STATEMENT_ID))
                .thenReturn(List.of());
        when(adjustmentRepository.findByReconciliation_ReconciliationId(RECON_ID))
                .thenReturn(List.of());

        service.addAdjustment(
                RECON_ID,
                ReconciliationAdjustmentRequest.builder()
                        .type(BankAdjustmentType.OTHER)
                        .amount(new BigDecimal("-7.2500"))
                        .build());

        ArgumentCaptor<BankReconciliationAdjustment> adjCaptor =
                ArgumentCaptor.forClass(BankReconciliationAdjustment.class);
        verify(adjustmentRepository).save(adjCaptor.capture());
        BankReconciliationAdjustment saved = adjCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(AdjustmentStatus.POSTED);
        assertThat(saved.getBankTransactionId()).isNull();
        assertThat(saved.getSettlesMatchId()).isNull();
        assertThat(saved.getBridgesStatementId()).isNull();
        assertThat(saved.getJustification()).isNull();
        assertThat(saved.getCounterGlAccountId()).isNull();
        // Dated at the statement end date (F2's statementDate is retired, SPEC §3.7).
        ArgumentCaptor<JournalEntryCreateRequest> jeCaptor = ArgumentCaptor.forClass(JournalEntryCreateRequest.class);
        verify(journalEntryService).createJournalEntry(jeCaptor.capture());
        assertThat(jeCaptor.getValue().getTransactionDate()).isEqualTo(txDate);
    }

    @Test
    @DisplayName("finalize succeeds when balanced (difference within tolerance)")
    void finalizeSucceedsWhenBalanced() {
        BankReconciliation recon = openReconciliation(); // statement 1000, glEnding 1000
        when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));
        when(reconciliationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.findByStatementIdOrderBySourceRowNumberAsc(STATEMENT_ID))
                .thenReturn(List.of());
        when(adjustmentRepository.findByReconciliation_ReconciliationId(RECON_ID))
                .thenReturn(List.of());

        BankReconciliationResponse response = service.finalizeReconciliation(RECON_ID);

        assertThat(response.getStatus()).isEqualTo(ReconciliationApiStatus.FINALIZED);
        assertThat(recon.getFinalizedAt()).isNotNull();
    }

    @Test
    @DisplayName("finalize rejects an unbalanced reconciliation with RECONCILIATION_NOT_BALANCED")
    void finalizeRejectsUnbalanced() {
        BankReconciliation recon = openReconciliation();
        recon.setStatementClosingBalance(new BigDecimal("1500.0000")); // 500 off, no adjustments
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

    /** A statement line: a bank transaction of the reconciliation's statement (story S1, #2300). */
    private static final UUID MATCH_ID = UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678900003");

    /** The match-header save hands back the header with its generated id, as JPA does. */
    private void stubMatchHeaderSave() {
        when(matchRepository.save(any(BankReconciliationMatch.class))).thenAnswer(inv -> {
            BankReconciliationMatch header = inv.getArgument(0);
            header.setMatchId(MATCH_ID);
            return header;
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<BankReconciliationBankMatch> savedBankMembers() {
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(bankMatchRepository).saveAll(captor.capture());
        return captor.getValue();
    }

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
