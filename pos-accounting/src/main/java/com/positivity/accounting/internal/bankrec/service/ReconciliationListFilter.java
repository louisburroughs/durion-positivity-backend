package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

/**
 * The list filters of {@code GET /v1/accounting/reconciliations} (SPEC §6.1; story S4, #2303), every one
 * optional: account, status, {@code periodCode} (the attribution period, {@code YYYY-MM}), and a window
 * {@code [from, to]} on the statement end date. Built as a specification, not a nullable-parameter JPQL
 * query (issues #1891, #1961).
 */
public record ReconciliationListFilter(
        @Nullable UUID glAccountId,
        @Nullable ReconciliationStatus status,
        @Nullable String periodCode,
        @Nullable LocalDate from,
        @Nullable LocalDate to) {

    /** No filter. */
    public static @NonNull ReconciliationListFilter none() {
        return new ReconciliationListFilter(null, null, null, null, null);
    }

    public @NonNull Specification<BankReconciliation> toSpecification() {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (glAccountId != null) {
                predicates.add(cb.equal(root.get("glAccount").get("glAccountId"), glAccountId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (periodCode != null && !periodCode.isBlank()) {
                predicates.add(cb.equal(root.get("accountingPeriodCode"), periodCode.trim()));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("statementEndDate"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("statementEndDate"), to));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
