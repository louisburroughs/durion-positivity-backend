package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.StatementLineMapping;
import com.positivity.accounting.internal.enums.StatementType;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for Statement Line Mapping entities.
 */
@Repository
public interface StatementLineMappingRepository extends JpaRepository<StatementLineMapping, UUID> {

    /**
     * Find all mappings for a specific statement type, ordered by display order.
     *
     * @param statementType the statement type (INCOME_STATEMENT or BALANCE_SHEET)
     * @return list of mappings sorted by display order
     */
    @NonNull
    List<StatementLineMapping> findByStatementTypeOrderByDisplayOrder(@NonNull StatementType statementType);

    /**
     * Find all mappings for a specific statement line code.
     *
     * @param statementLineCode the statement line code
     * @return list of mappings for that line code
     */
    @NonNull
    List<StatementLineMapping> findByStatementLineCode(@NonNull String statementLineCode);

    /**
     * Find all mappings for a specific GL account.
     *
     * @param accountId the GL account ID
     * @return list of mappings for that account
     */
    @NonNull
    List<StatementLineMapping> findByAccountId(@NonNull String accountId);
}
