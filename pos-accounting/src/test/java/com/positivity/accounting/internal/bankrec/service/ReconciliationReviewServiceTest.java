package com.positivity.accounting.internal.bankrec.service;

import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.END;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.RECON_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.START;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.STATEMENT_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.adjustment;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.reconciliation;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.statement;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.terms;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.transaction;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.dto.ReconciliationReportResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationReviewResponse;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationAdjustment;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankAdjustmentType;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.ReadinessReason;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationAdjustmentRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.service.AccountingPeriodGate;
import com.positivity.accounting.internal.service.AccountingPeriodService;
import com.positivity.accounting.internal.service.JournalEntryService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The review read model and the report (SPEC §4.7, §4.8; story S4, #2303, criteria 12, 16). */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReconciliationReviewServiceImpl (#2303)")
class ReconciliationReviewServiceTest {

    @Mock
    private ReconciliationSupport support;

    @Mock
    private ReconciliationCalculator calculator;

    @Mock
    private CandidateFinder finder;

    @Mock
    private MatchResponses matchResponses;

    @Mock
    private BankReconciliationRepository reconciliations;

    @Mock
    private BankReconciliationMatchRepository matches;

    @Mock
    private BankReconciliationOutstandingItemRepository items;

    @Mock
    private BankReconciliationAdjustmentRepository adjustments;

    @Mock
    private BankStatementRepository statements;

    @Mock
    private BankTransactionRepository transactions;

    @Mock
    private JournalEntryService journalEntryService;

    @Mock
    private AccountingPeriodGate periodGate;

    @Mock
    private AccountingPeriodService periodService;

    @Mock
    private BankRecPolicy policy;

    private ReconciliationReviewServiceImpl service;
    private BankReconciliation recon;
    private BankStatement acknowledged;

    @BeforeEach
    void setUp() {
        service = new ReconciliationReviewServiceImpl(
                support,
                calculator,
                finder,
                matchResponses,
                reconciliations,
                matches,
                items,
                adjustments,
                statements,
                transactions,
                journalEntryService,
                periodGate,
                periodService,
                BankRecSettings.defaults(),
                usd(),
                policy);
        recon = reconciliation();
        acknowledged = statement(STATEMENT_ID, START, END, "Changed banks in August");
        lenient().when(support.require(RECON_ID)).thenReturn(recon);
        lenient().when(statements.findById(STATEMENT_ID)).thenReturn(Optional.of(acknowledged));
        lenient().when(periodService.isPeriodOpen(any(LocalDate.class))).thenReturn(true);
        lenient().when(matchResponses.of(anyCollection())).thenReturn(List.of());
        lenient().when(finder.rankedForEach(any(), anyList())).thenReturn(Map.of());
        lenient()
                .when(journalEntryService.getJournalEntry(any()))
                .thenReturn(JournalEntryResponse.builder()
                        .entryNumber("JE-202609-0042")
                        .build());
    }

    private void snapshot(String difference, String openingDifference, List<BankTransaction> unexplainedBank) {
        ReconciliationSnapshot base = BankRecFixtures.snapshot(terms(difference, openingDifference));
        when(calculator.compute(recon))
                .thenReturn(new ReconciliationSnapshot(
                        base.terms(),
                        acknowledged,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        unexplainedBank,
                        List.of(),
                        List.of(),
                        List.of()));
    }

    @Test
    @DisplayName("an unbridged gap is flagged OPENING_DIFFERENCE with its likely cause, never a readiness reason")
    void openingDifferenceIsADiagnostic() {
        snapshot("0", "-45.67", List.of());

        ReconciliationReviewResponse review = service.review(RECON_ID);

        assertThat(review.getDiagnostics().getFlags()).containsExactly("OPENING_DIFFERENCE");
        assertThat(review.getDiagnostics().getLikelyCause()).isEqualTo("GAP_NOT_BRIDGED");
        assertThat(review.getHeader().isBaselineSetByThisStatement()).isTrue();
        assertThat(review.getHeader().getGapAcknowledgement()).isEqualTo("Changed banks in August");
        assertThat(review.getHeader().getPeriodState()).isEqualTo("OPEN");
        assertThat(review.getReadiness().getReasons()).isEmpty();
        assertThat(review.getReadiness().isCanSubmit()).isTrue();
    }

    @Test
    @DisplayName("readiness lists NOT_BALANCED and UNEXPLAINED_BANK; a possible duplicate carries its candidates")
    void readinessAndDuplicates() {
        BankTransaction duplicate = transaction("250.00", LocalDate.of(2026, 9, 12));
        duplicate.setStatus(BankTransactionStatus.POSSIBLE_DUPLICATE);
        BankTransaction original = transaction("250.00", LocalDate.of(2026, 9, 11));
        snapshot("12.50", "0", List.of(duplicate));
        when(transactions.findByGlAccountIdAndSignedAmountAndTransactionDateBetweenAndStatusNotIn(
                        any(), any(), any(), any(), anyCollection()))
                .thenReturn(List.of(duplicate, original));

        ReconciliationReviewResponse review = service.review(RECON_ID);

        assertThat(review.getReadiness().getReasons())
                .containsExactly(ReadinessReason.NOT_BALANCED, ReadinessReason.UNEXPLAINED_BANK);
        assertThat(review.getReadiness().isCanSubmit()).isFalse();
        assertThat(review.getUnresolved().getPossibleDuplicates())
                .singleElement()
                .satisfies(row -> assertThat(row.getNearDuplicates())
                        .extracting(ReconciliationReviewResponse.BankRow::getBankTransactionId)
                        .containsExactly(original.getBankTransactionId()));
        assertThat(review.getUnresolved().getUnexplainedBank())
                .as("duplicates are listed apart")
                .isEmpty();
    }

    @Test
    @DisplayName("the report serves the live difference, the counts and every OTHER under adjustments to clearing")
    void reportListsClearingAdjustments() {
        snapshot("0", "0", List.of());
        BankReconciliationAdjustment other = adjustment(recon, BankAdjustmentType.OTHER, "-45.67", UUID.randomUUID());
        other.setBridgesStatementId(STATEMENT_ID);
        other.setJustification("Gap left by the change of bank");
        other.setTransactionDate(START.minusDays(1));
        BankReconciliationAdjustment fee = adjustment(recon, BankAdjustmentType.BANK_FEE, "-15", UUID.randomUUID());
        when(adjustments.findByReconciliation_ReconciliationId(RECON_ID)).thenReturn(List.of(other, fee));

        ReconciliationReportResponse report = service.report(RECON_ID);

        assertThat(report.getDifference()).isEqualByComparingTo("0");
        assertThat(report.getAdjustmentsToClearing()).singleElement().satisfies(c -> {
            assertThat(c.getLinkKind()).isEqualTo("GAP_BRIDGE");
            assertThat(c.getLinkId()).isEqualTo(STATEMENT_ID);
            assertThat(c.getAgeDays()).isEqualTo(30);
        });
        assertThat(report.getAdjustments())
                .hasSize(2)
                .allSatisfy(a -> assertThat(a.getEntryNumber()).isEqualTo("JE-202609-0042"));
        assertThat(report.getEquation()).isNotNull();
        assertThat(report.getOpeningTerms()).isNotNull();
    }
}
