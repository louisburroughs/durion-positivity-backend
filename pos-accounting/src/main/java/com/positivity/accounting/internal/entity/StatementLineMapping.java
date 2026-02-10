package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.OperationType;
import com.positivity.accounting.internal.enums.StatementType;
import jakarta.persistence.*;
import lombok.*;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

/**
 * Configurable mapping from GL accounts to financial statement lines.
 * 
 * Enables flexible Chart of Accounts (COA) mapping to standardized
 * statement lines (e.g., US GAAP, IFRS).
 * 
 * Example mappings:
 * - Account "4000" (Sales Revenue) → "PL_REVENUE_SALES" line on Income
 * Statement
 * - Account "1000" (Cash) → "BS_ASSETS_CURRENT_CASH" line on Balance Sheet
 * 
 * @see StatementType
 * @see OperationType
 */
@Entity
@Table(name = "statement_line_mappings", indexes = {
        @Index(name = "idx_statement_line_mapping_type", columnList = "statement_type"),
        @Index(name = "idx_statement_line_mapping_account", columnList = "account_id"),
        @Index(name = "idx_statement_line_mapping_code", columnList = "statement_line_code")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class StatementLineMapping {

    @EqualsAndHashCode.Include
    @Id
    @Column(name = "mapping_id", columnDefinition = "UUID")
    private UUID mappingId;

    /**
     * GL Account ID (references gl_accounts.account_id).
     */
    @NonNull
    @Column(name = "account_id", length = 100, nullable = false)
    private String accountId;

    /**
     * GL Account Name (denormalized for reporting performance).
     */
    @Column(name = "account_name", length = 255)
    private String accountName;

    /**
     * Type of financial statement (Income Statement or Balance Sheet).
     */
    @NonNull
    @Enumerated(EnumType.STRING)
    @Column(name = "statement_type", length = 50, nullable = false)
    private StatementType statementType;

    /**
     * Unique code for the statement line (e.g., "PL_REVENUE_SALES",
     * "BS_ASSETS_CURRENT").
     */
    @NonNull
    @Column(name = "statement_line_code", length = 100, nullable = false)
    private String statementLineCode;

    /**
     * Human-readable description of the line item.
     */
    @Column(name = "line_description", length = 255)
    private String lineDescription;

    /**
     * Display order on the statement (lower numbers appear first).
     */
    @Column(name = "display_order")
    private Integer displayOrder;

    /**
     * Parent line code for hierarchical subtotals (null for top-level lines).
     */
    @Column(name = "parent_line_code", length = 100)
    private String parentLineCode;

    /**
     * Operation to apply when aggregating this account's balance.
     */
    @NonNull
    @Enumerated(EnumType.STRING)
    @Column(name = "operation", length = 50, nullable = false)
    private OperationType operation;

    @PrePersist
    protected void onCreate() {
        if (mappingId == null) {
            mappingId = UUID.randomUUID();
        }
    }
}
