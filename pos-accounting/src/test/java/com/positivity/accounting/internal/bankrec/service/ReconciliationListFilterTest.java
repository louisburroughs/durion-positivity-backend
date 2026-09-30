package com.positivity.accounting.internal.bankrec.service;

import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.ACCOUNT_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.END;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.START;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ReconciliationListFilter} (SPEC §6.1; story S4, #2303): every filter optional, each present one a
 * predicate on its own attribute, a blank period ignored and a padded one trimmed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReconciliationListFilter (#2303)")
class ReconciliationListFilterTest {

    @Mock
    private Root<BankReconciliation> root;

    @Mock
    private CriteriaQuery<?> query;

    @Mock
    private CriteriaBuilder cb;

    @Mock
    private Path<Object> glAccount;

    @Mock
    private Path<Object> glAccountId;

    @Mock
    private Path<Object> status;

    @Mock
    private Path<Object> period;

    @Mock
    private Path<LocalDate> statementEndDate;

    @Mock
    private Predicate onAccount;

    @Mock
    private Predicate onStatus;

    @Mock
    private Predicate onPeriod;

    @Mock
    private Predicate onOrAfterFrom;

    @Mock
    private Predicate onOrBeforeTo;

    @Mock
    private Predicate conjunction;

    @Test
    @DisplayName("no filter is an empty conjunction touching no attribute")
    void noFilter() {
        when(cb.and()).thenReturn(conjunction);

        Predicate result = ReconciliationListFilter.none().toSpecification().toPredicate(root, query, cb);

        assertThat(result).isSameAs(conjunction);
        verifyNoInteractions(root, query);
        verify(cb, never()).equal(any(), any(Object.class));
    }

    @Test
    @DisplayName("every filter set ANDs account, status, trimmed period and the statement-end window")
    void everyFilter() {
        doReturn(glAccount).when(root).get("glAccount");
        doReturn(glAccountId).when(glAccount).get("glAccountId");
        doReturn(status).when(root).get("status");
        doReturn(period).when(root).get("accountingPeriodCode");
        doReturn(statementEndDate).when(root).get("statementEndDate");
        when(cb.equal(glAccountId, ACCOUNT_ID)).thenReturn(onAccount);
        when(cb.equal(status, ReconciliationStatus.FINALIZED)).thenReturn(onStatus);
        when(cb.equal(period, "2026-09")).thenReturn(onPeriod);
        when(cb.greaterThanOrEqualTo(statementEndDate, START)).thenReturn(onOrAfterFrom);
        when(cb.lessThanOrEqualTo(statementEndDate, END)).thenReturn(onOrBeforeTo);
        when(cb.and(onAccount, onStatus, onPeriod, onOrAfterFrom, onOrBeforeTo)).thenReturn(conjunction);

        ReconciliationListFilter filter =
                new ReconciliationListFilter(ACCOUNT_ID, ReconciliationStatus.FINALIZED, " 2026-09 ", START, END);

        assertThat(filter.toSpecification().toPredicate(root, query, cb)).isSameAs(conjunction);
    }

    @Test
    @DisplayName("a blank period is no filter; a lone upper bound filters only the statement end")
    void blankPeriodAndLoneUpperBound() {
        doReturn(statementEndDate).when(root).get("statementEndDate");
        when(cb.lessThanOrEqualTo(statementEndDate, END)).thenReturn(onOrBeforeTo);
        when(cb.and(onOrBeforeTo)).thenReturn(conjunction);

        ReconciliationListFilter filter = new ReconciliationListFilter(null, null, "   ", null, END);

        assertThat(filter.toSpecification().toPredicate(root, query, cb)).isSameAs(conjunction);
        verify(root, never()).get("accountingPeriodCode");
        verify(cb, never()).equal(any(), anyString());
        verify(cb, never()).greaterThanOrEqualTo(any(), any(LocalDate.class));
    }

    @Test
    @DisplayName("a lone lower bound filters only the statement end")
    void loneLowerBound() {
        doReturn(statementEndDate).when(root).get("statementEndDate");
        when(cb.greaterThanOrEqualTo(statementEndDate, START)).thenReturn(onOrAfterFrom);
        when(cb.and(onOrAfterFrom)).thenReturn(conjunction);

        ReconciliationListFilter filter = new ReconciliationListFilter(null, null, null, START, null);

        assertThat(filter.toSpecification().toPredicate(root, query, cb)).isSameAs(conjunction);
        verify(cb, never()).lessThanOrEqualTo(any(), any(LocalDate.class));
    }
}
