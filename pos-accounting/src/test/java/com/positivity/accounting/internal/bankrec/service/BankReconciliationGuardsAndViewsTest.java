package com.positivity.accounting.internal.bankrec.service;

import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.RECON_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.reconciliation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.dto.BankReconciliationListResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationAuditResponse;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationMatch;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
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
 * specification over every filter) and the stored audit trail (§4.9, §6.1; stories S4 #2303, S5 #2304).
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
    private BankReconciliationMatchRepository matchRepository;

    @Mock
    private BankReconciliationOutstandingItemRepository itemRepository;

    @Mock
    private AccountingAuditLogRepository auditLogRepository;

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
                reconciliationRepository,
                statementRepository,
                matchRepository,
                itemRepository,
                auditLogRepository,
                new BankCashAccounts(glAccountRepository, clock),
                calculator,
                new ReconciliationSupport(reconciliationRepository, calculator, clock),
                auditRecorder,
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
    @DisplayName("the audit trail pages the stored rows of the reconciliation, its matches and its items (G3)")
    void auditReadsTheStoredTrail() {
        when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(reconciliation()));
        BankReconciliationMatch match = new BankReconciliationMatch();
        match.setMatchId(UUID.randomUUID());
        when(matchRepository.findByReconciliationIdOrderByCreatedAtAsc(RECON_ID))
                .thenReturn(List.of(match));
        when(itemRepository.findIdsTouchedBy(RECON_ID)).thenReturn(List.of());
        AccountingAuditLog row = new AccountingAuditLog();
        row.setAuditLogId(UUID.randomUUID());
        row.setEntityType(BankRecAuditRecorder.RECONCILIATION_MATCH);
        row.setEntityId(match.getMatchId());
        row.setOperation(BankRecAuditRecorder.RECONCILIATION_UNMATCH);
        row.setUserId("preparer");
        row.setJustification("Wrong deposit matched");
        row.setTraceId("trace-1");
        Pageable page = PageRequest.of(0, 50);
        when(auditLogRepository.findReconciliationTrail(
                        eq(BankRecAuditRecorder.BANK_RECONCILIATION),
                        eq(RECON_ID),
                        eq(BankRecAuditRecorder.RECONCILIATION_MATCH),
                        eq(List.of(match.getMatchId())),
                        eq(BankRecAuditRecorder.OUTSTANDING_ITEM),
                        eq(List.of(new UUID(0L, 0L))),
                        eq(page)))
                .thenReturn(new PageImpl<>(List.of(row), page, 1));

        ReconciliationAuditResponse audit = service.audit(RECON_ID, page);

        assertThat(audit.getTotalElements()).isEqualTo(1);
        assertThat(audit.getEntries()).singleElement().satisfies(e -> {
            assertThat(e.getOperation()).isEqualTo("RECONCILIATION_UNMATCH");
            assertThat(e.getUserId()).isEqualTo("preparer");
            assertThat(e.getTraceId()).isEqualTo("trace-1");
            assertThat(e.getJustification()).isEqualTo("Wrong deposit matched");
        });
    }
}
