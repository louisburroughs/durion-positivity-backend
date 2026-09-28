package com.positivity.accounting.internal.bankrec.service;

import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.RECON_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.reconciliation;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.dto.BankReconciliationListResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationAuditResponse;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationAdjustment;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationGlMatch;
import com.positivity.accounting.internal.bankrec.enums.BankAdjustmentType;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationAdjustmentRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/**
 * The read views of {@link BankReconciliationServiceImpl} that need no ledger: the list (stored terms, a
 * specification over every filter) and the derived audit trail (§6.1; stories F2 #965, S4 #2303).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BankReconciliationServiceImpl — list and audit views")
class BankReconciliationGuardsAndViewsTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private BankReconciliationRepository reconciliationRepository;

    @Mock
    private BankStatementRepository statementRepository;

    @Mock
    private BankTransactionRepository transactionRepository;

    @Mock
    private BankReconciliationGlMatchRepository glMatchRepository;

    @Mock
    private BankReconciliationAdjustmentRepository adjustmentRepository;

    @Mock
    private GLAccountRepository glAccountRepository;

    @Mock
    private ReconciliationCalculator calculator;

    @Mock
    private BankRecAuditRecorder auditRecorder;

    @Mock
    private ReconciliationReviewService reviewService;

    private BankReconciliationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BankReconciliationServiceImpl(
                clock,
                reconciliationRepository,
                statementRepository,
                transactionRepository,
                glMatchRepository,
                adjustmentRepository,
                glAccountRepository,
                new BankCashAccounts(glAccountRepository, clock),
                calculator,
                new ReconciliationSupport(reconciliationRepository, calculator, clock),
                auditRecorder,
                usd(),
                reviewService);
    }

    @Test
    @DisplayName("the list serves the stored terms of each row with its page metadata, reading no ledger")
    @SuppressWarnings("unchecked")
    void listServesStoredTerms() {
        BankReconciliation stored = reconciliation();
        stored.setDifference(new BigDecimal("3.2100"));
        Pageable page = PageRequest.of(1, 20);
        when(reconciliationRepository.findAll(any(Specification.class), eq(page)))
                .thenReturn(new PageImpl<>(List.of(stored), page, 41));

        BankReconciliationListResponse response = service.list(
                new ReconciliationListFilter(null, ReconciliationStatus.IN_PROGRESS, "2026-09", null, null), page);

        assertThat(response.getReconciliations())
                .singleElement()
                .satisfies(r -> assertThat(r.getDifference()).isEqualByComparingTo("3.21"));
        assertThat(response.getTotalElements()).isEqualTo(41);
        assertThat(response.getPageNumber()).isEqualTo(1);
        assertThat(response.getTotalPages()).isEqualTo(3);
        verify(calculator, org.mockito.Mockito.never()).compute(any());
    }

    @Test
    @DisplayName("the audit trail lists create, each match group, each adjustment and the finalize in time order")
    void auditListsTheWholeTrail() {
        BankReconciliation recon = reconciliation();
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
        when(glMatchRepository.findByReconciliationIdAndActiveTrue(RECON_ID)).thenReturn(List.of(match));

        ReconciliationAuditResponse audit = service.audit(RECON_ID);

        assertThat(audit.getEntries())
                .extracting(ReconciliationAuditResponse.Entry::getAction)
                .containsExactly("IMPORT", "MATCH", "ADJUSTMENT", "FINALIZE");
    }
}
