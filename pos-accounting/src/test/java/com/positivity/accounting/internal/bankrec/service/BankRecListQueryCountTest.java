package com.positivity.accounting.internal.bankrec.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.dto.BankAccountListResponse;
import com.positivity.accounting.internal.bankrec.dto.BankAccountResponse;
import com.positivity.accounting.internal.bankrec.dto.BankStatementListResponse;
import com.positivity.accounting.internal.bankrec.entity.BankAccountProfile;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.repository.AccountCount;
import com.positivity.accounting.internal.bankrec.repository.AccountDate;
import com.positivity.accounting.internal.bankrec.repository.BankAccountProfileRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.repository.StatementCounts;
import com.positivity.accounting.internal.bankrec.service.BankCashAccounts.BankCashAccount;
import com.positivity.accounting.internal.entity.GLAccount;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/**
 * The bank-account and statement lists read a page with a fixed number of grouped queries, whatever
 * its size (#2301): no per-row profile, count, frontier or reconciliation lookups.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Bank rec list reads — bounded queries")
class BankRecListQueryCountTest {

    @Mock
    private BankCashAccounts bankCashAccounts;

    @Mock
    private BankAccountProfileRepository profiles;

    @Mock
    private BankStatementRepository statements;

    @Mock
    private BankTransactionRepository transactions;

    @Mock
    private BankReconciliationRepository reconciliations;

    @Mock
    private BankReconciliationOutstandingItemRepository outstandingItems;

    private static UUID id(int n) {
        return UUID.fromString(String.format("01990000-0000-7000-8000-%012d", n));
    }

    @Nested
    class Accounts {

        @Test
        void aPageOfAccountsIsDescribedByOneQueryPerFactWithTheSameValues() {
            List<BankCashAccount> page = IntStream.rangeClosed(1, 5)
                    .mapToObj(n -> new BankCashAccount(id(n), "100" + n, "Bank " + n))
                    .toList();
            when(bankCashAccounts.pageActive(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(page, PageRequest.of(0, 5), 12));
            BankAccountProfile withBaseline = new BankAccountProfile(id(1));
            withBaseline.setBankName("First Bank");
            withBaseline.setCurrency("USD");
            withBaseline.setReconciliationBaselineDate(LocalDate.of(2025, 1, 1));
            when(profiles.findAllById(anyList())).thenReturn(List.of(withBaseline));
            when(transactions.countSinceBaselineByGlAccountIdIn(anyList(), eq(BankTransactionServiceImpl.UNEXPLAINED)))
                    .thenReturn(List.of(new AccountCount(id(1), 4), new AccountCount(id(3), 2)));
            when(outstandingItems.countSinceBaselineByGlAccountIdIn(anyList(), eq(OutstandingItemStatus.OPEN)))
                    .thenReturn(List.of(new AccountCount(id(1), 1)));
            when(statements.findLatestEndDateByGlAccountIdIn(anyList(), eq(BankStatementStatus.COMMITTED)))
                    .thenReturn(List.of(new AccountDate(id(1), LocalDate.of(2025, 2, 28))));
            GLAccount gl = new GLAccount();
            gl.setGlAccountId(id(1));
            BankReconciliation january = new BankReconciliation();
            january.setGlAccount(gl);
            january.setStatementStartDate(LocalDate.of(2025, 1, 1));
            january.setStatementEndDate(LocalDate.of(2025, 1, 31));
            when(reconciliations.findByGlAccount_GlAccountIdInAndStatusOrderByStatementStartDateAsc(
                            anyList(), eq(ReconciliationStatus.FINALIZED)))
                    .thenReturn(List.of(january));

            BankAccountServiceImpl service = new BankAccountServiceImpl(
                    bankCashAccounts,
                    null,
                    profiles,
                    statements,
                    transactions,
                    reconciliations,
                    outstandingItems,
                    null);
            BankAccountListResponse response = service.listBankAccounts(0, 5);

            assertThat(response.getTotalElements()).isEqualTo(12);
            assertThat(response.getTotalPages()).isEqualTo(3);
            assertThat(response.getAccounts()).hasSize(5);
            BankAccountResponse first = response.getAccounts().getFirst();
            assertThat(first.getGlAccountId()).isEqualTo(id(1));
            assertThat(first.isProfileExists()).isTrue();
            assertThat(first.getBankName()).isEqualTo("First Bank");
            assertThat(first.getReconciliationBaselineDate()).isEqualTo(LocalDate.of(2025, 1, 1));
            assertThat(first.getUnexplainedBankTransactionCount()).isEqualTo(4);
            assertThat(first.getOpenOutstandingItemCount()).isEqualTo(1);
            assertThat(first.getCoverageFrontier()).isEqualTo(LocalDate.of(2025, 2, 28));
            assertThat(first.getReconciledFrontier()).isEqualTo(LocalDate.of(2025, 1, 31));
            BankAccountResponse third = response.getAccounts().get(2);
            assertThat(third.isProfileExists()).isFalse();
            assertThat(third.getUnexplainedBankTransactionCount()).isEqualTo(2);
            assertThat(third.getOpenOutstandingItemCount()).isZero();
            assertThat(third.getCoverageFrontier()).isNull();
            assertThat(third.getReconciledFrontier()).isNull();

            verify(profiles, times(1)).findAllById(anyList());
            verify(transactions, times(1)).countSinceBaselineByGlAccountIdIn(anyList(), anyCollection());
            verify(outstandingItems, times(1)).countSinceBaselineByGlAccountIdIn(anyList(), any());
            verify(statements, times(1)).findLatestEndDateByGlAccountIdIn(anyList(), any());
            verify(reconciliations, times(1))
                    .findByGlAccount_GlAccountIdInAndStatusOrderByStatementStartDateAsc(anyList(), any());
            verifyNoMoreInteractions(profiles, transactions, outstandingItems, statements, reconciliations);
        }
    }

    @Nested
    class Statements {

        @Test
        @SuppressWarnings("unchecked")
        void aPageOfStatementsIsCountedByOneGroupedQuery() {
            List<BankStatement> page = IntStream.rangeClosed(1, 4)
                    .mapToObj(n -> {
                        BankStatement s = new BankStatement();
                        s.setStatementId(id(10 + n));
                        s.setGlAccountId(id(1));
                        return s;
                    })
                    .toList();
            when(statements.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn((Page<BankStatement>) new PageImpl<>(page, PageRequest.of(0, 4), 4));
            when(bankCashAccounts.displayValues(anyList()))
                    .thenReturn(Map.of(id(1), new BankCashAccount(id(1), "1001", "Bank")));
            when(transactions.countByStatementIdIn(anyList(), eq(BankTransactionStatus.POSSIBLE_DUPLICATE)))
                    .thenReturn(List.of(new StatementCounts(id(11), 7, 2)));

            BankStatementServiceImpl service = new BankStatementServiceImpl(
                    null, bankCashAccounts, null, statements, transactions, profiles, reconciliations, null, null);
            BankStatementListResponse response = service.listStatements(null, null, null, 0, 4);

            assertThat(response.getStatements()).hasSize(4);
            assertThat(response.getStatements().getFirst().getBankTransactionCount())
                    .isEqualTo(7);
            assertThat(response.getStatements().getFirst().getPossibleDuplicateCount())
                    .isEqualTo(2);
            assertThat(response.getStatements().get(1).getBankTransactionCount())
                    .isZero();
            assertThat(response.getStatements().get(1).getPossibleDuplicateCount())
                    .isZero();
            verify(transactions, times(1)).countByStatementIdIn(anyList(), any());
            verifyNoMoreInteractions(transactions);
        }
    }
}
