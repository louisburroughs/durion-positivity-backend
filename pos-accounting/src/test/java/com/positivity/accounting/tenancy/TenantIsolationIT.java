package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.audit.entity.OverridePolicyThreshold;
import com.positivity.accounting.internal.audit.repository.OverridePolicyThresholdRepository;
import com.positivity.accounting.internal.bankfeed.file.entity.BankImport;
import com.positivity.accounting.internal.bankfeed.file.entity.BankImportFile;
import com.positivity.accounting.internal.bankfeed.file.entity.BankImportRow;
import com.positivity.accounting.internal.bankfeed.file.enums.BankImportRowStatus;
import com.positivity.accounting.internal.bankfeed.file.enums.BankImportStatus;
import com.positivity.accounting.internal.bankfeed.file.repository.BankImportFileRepository;
import com.positivity.accounting.internal.bankfeed.file.repository.BankImportRepository;
import com.positivity.accounting.internal.bankfeed.file.repository.BankImportRowRepository;
import com.positivity.accounting.internal.bankrec.entity.BankAccountProfile;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.enums.SettlementState;
import com.positivity.accounting.internal.bankrec.enums.SourceKind;
import com.positivity.accounting.internal.bankrec.repository.BankAccountProfileRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the isolation, not just the mapping (plan R-B7): a row written as tenant A is invisible to
 * tenant B through the repository (Hibernate's {@code @TenantId} filter) and through a raw {@code
 * JdbcTemplate} on the same pool (row-level security alone), and an unbound connection can neither
 * read nor write a scoped table.
 */
@DisplayName("Tenant isolation on Postgres (ADR-0062, pos-accounting)")
class TenantIsolationIT extends PostgresTenancyTestBase {

    @Autowired
    private OverridePolicyThresholdRepository policies;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private GLAccountRepository glAccounts;

    @Autowired
    private BankStatementRepository statements;

    @Autowired
    private BankTransactionRepository transactions;

    @Autowired
    private BankReconciliationRepository reconciliations;

    @Autowired
    private BankImportRepository imports;

    @Autowired
    private BankAccountProfileRepository profiles;

    @Autowired
    private BankImportRowRepository importRows;

    @Autowired
    private BankImportFileRepository importFiles;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AccountingAuditLogRepository auditLogs;

    /** Seeded {@code 1000 Cash} of the default tenant, which is TENANT_A. */
    private static final UUID CASH_ACCOUNT_ID = UUID.fromString("5eed0acc-0000-4000-8000-000000001000");

    private final List<UUID> bankRecRows = new ArrayList<>();

    @AfterEach
    void clear() {
        TenantContext.clear();
        if (!bankRecRows.isEmpty()) {
            // The owner bypasses RLS; remove the bank reconciliation rows so the shared database keeps no
            // COMMITTED statement window another IT could collide with.
            JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
            for (String table : List.of(
                    "bank_import_row",
                    "bank_import_file",
                    "bank_import",
                    "bank_reconciliation",
                    "bank_transaction",
                    "bank_statement",
                    "bank_account_profile")) {
                String key =
                        switch (table) {
                            case "bank_import", "bank_import_row", "bank_import_file" -> "import_id";
                            case "bank_account_profile" -> "gl_account_id";
                            case "bank_reconciliation" -> "reconciliation_id";
                            case "bank_transaction" -> "bank_transaction_id";
                            default -> "statement_id";
                        };
                bankRecRows.forEach(id -> owner.update("DELETE FROM " + table + " WHERE " + key + " = ?", id));
            }
            bankRecRows.clear();
        }
    }

    /**
     * The bank reconciliation tables of story S1 (#2300, SPEC §8.4): a second tenant sees no statement,
     * transaction, reconciliation or import of the first — through the repository and through raw SQL.
     */
    @Test
    void bankReconciliationRowsOfOneTenantAreInvisibleToAnother() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        UUID[] ids = asTenant(
                TENANT_A,
                () -> tx.execute(status -> {
                    BankStatement statement = new BankStatement();
                    statement.setGlAccountId(CASH_ACCOUNT_ID);
                    statement.setSourceKind(SourceKind.FILE_IMPORT);
                    statement.setStartDate(LocalDate.of(2041, 3, 1));
                    statement.setEndDate(LocalDate.of(2041, 3, 31));
                    statement.setOpeningBalance(new BigDecimal("100.0000"));
                    statement.setActivityTotal(new BigDecimal("25.0000"));
                    statement.setClosingBalance(new BigDecimal("125.0000"));
                    statement.setCurrency("USD");
                    statement.setStatus(BankStatementStatus.COMMITTED);
                    UUID statementId = statements.saveAndFlush(statement).getStatementId();

                    BankTransaction transaction = new BankTransaction();
                    transaction.setGlAccountId(CASH_ACCOUNT_ID);
                    transaction.setStatementId(statementId);
                    transaction.setSourceKind(SourceKind.FILE_IMPORT);
                    transaction.setSourceRowNumber(1);
                    transaction.setSettlementState(SettlementState.POSTED);
                    transaction.setTransactionDate(LocalDate.of(2041, 3, 10));
                    transaction.setSignedAmount(new BigDecimal("25.0000"));
                    transaction.setCurrency("USD");
                    transaction.setStatus(BankTransactionStatus.UNMATCHED);
                    UUID transactionId = transactions.saveAndFlush(transaction).getBankTransactionId();

                    BankReconciliation reconciliation = new BankReconciliation();
                    reconciliation.setGlAccount(glAccounts.getReferenceById(CASH_ACCOUNT_ID));
                    reconciliation.setStatementId(statementId);
                    reconciliation.setStatementStartDate(LocalDate.of(2041, 3, 1));
                    reconciliation.setStatementEndDate(LocalDate.of(2041, 3, 31));
                    reconciliation.setCurrency("USD");
                    reconciliation.setStatementClosingBalance(new BigDecimal("125.0000"));
                    reconciliation.setGlEndingBalance(BigDecimal.ZERO);
                    reconciliation.setDifference(new BigDecimal("125.0000"));
                    reconciliation.setStatus(ReconciliationStatus.IN_PROGRESS);
                    UUID reconciliationId =
                            reconciliations.saveAndFlush(reconciliation).getReconciliationId();

                    BankImport bankImport = new BankImport();
                    bankImport.setGlAccountId(CASH_ACCOUNT_ID);
                    bankImport.setCurrency("USD");
                    bankImport.setFormatCode("CSV");
                    bankImport.setFileSha256("a".repeat(64));
                    bankImport.setStatus(BankImportStatus.UPLOADED);
                    UUID importId = imports.saveAndFlush(bankImport).getImportId();

                    // #2302: an import's staged rows and retained raw file are the tenant's too.
                    BankImportRow row = new BankImportRow();
                    row.setImportId(importId);
                    row.setRowNumber(1);
                    row.setRawValues(java.util.Map.of("date", "2041-03-10"));
                    row.setRowStatus(BankImportRowStatus.PARSED);
                    importRows.saveAndFlush(row);
                    BankImportFile file = new BankImportFile(importId);
                    file.setFileBytes("date,description,amount".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    importFiles.saveAndFlush(file);

                    // #2301: the bank-account profile carries the account's reconciliation baseline.
                    BankAccountProfile profile = new BankAccountProfile(CASH_ACCOUNT_ID);
                    profile.setCurrency("USD");
                    profile.setReconciliationBaselineDate(LocalDate.of(2041, 3, 1));
                    profiles.saveAndFlush(profile);
                    return new UUID[] {statementId, transactionId, reconciliationId, importId, CASH_ACCOUNT_ID};
                }));
        bankRecRows.addAll(List.of(ids));

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        asTenant(TENANT_A, () -> {
            assertThat(statements.findById(ids[0])).isPresent();
            assertThat(transactions.findById(ids[1])).isPresent();
            assertThat(reconciliations.findById(ids[2])).isPresent();
            assertThat(imports.findById(ids[3])).isPresent();
            assertThat(importFiles.findById(ids[3])).isPresent();
            assertThat(countById(jdbc, "bank_import_row", "import_id", ids[3])).isEqualTo(1);
            assertThat(profiles.findById(ids[4])).isPresent();
            assertThat(countById(jdbc, "bank_statement", "statement_id", ids[0]))
                    .isEqualTo(1);
        });
        asTenant(TENANT_B, () -> {
            assertThat(statements.findById(ids[0])).as("statement").isEmpty();
            assertThat(transactions.findById(ids[1])).as("transaction").isEmpty();
            assertThat(reconciliations.findById(ids[2])).as("reconciliation").isEmpty();
            assertThat(imports.findById(ids[3])).as("import").isEmpty();
            assertThat(importFiles.findById(ids[3])).as("import file").isEmpty();
            assertThat(countById(jdbc, "bank_import_row", "import_id", ids[3]))
                    .as("import rows")
                    .isZero();
            assertThat(countById(jdbc, "bank_import_file", "import_id", ids[3]))
                    .as("import file")
                    .isZero();
            assertThat(profiles.findById(ids[4])).as("bank-account profile").isEmpty();
            assertThat(countById(jdbc, "bank_account_profile", "gl_account_id", ids[4]))
                    .isZero();
            assertThat(countById(jdbc, "bank_statement", "statement_id", ids[0]))
                    .isZero();
            assertThat(countById(jdbc, "bank_transaction", "bank_transaction_id", ids[1]))
                    .isZero();
            assertThat(countById(jdbc, "bank_reconciliation", "reconciliation_id", ids[2]))
                    .isZero();
            assertThat(countById(jdbc, "bank_import", "import_id", ids[3])).isZero();
        });
        assertThat(countById(jdbc, "bank_statement", "statement_id", ids[0]))
                .as("unbound: nothing visible")
                .isZero();
    }

    /**
     * The stored reconciliation trail (story S5, #2304; SPEC §8.4): a second tenant reads none of the first
     * tenant's audit rows through the trail query.
     */
    @Test
    void reconciliationAuditRowsOfOneTenantAreInvisibleToAnother() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        UUID reconciliationId = com.positivity.shared.id.UUIDv7Generator.generate();
        UUID auditLogId = asTenant(
                TENANT_A,
                () -> tx.execute(status -> {
                    AccountingAuditLog row = new AccountingAuditLog();
                    row.setEntityType("BANK_RECONCILIATION");
                    row.setEntityId(reconciliationId);
                    row.setOperation("RECONCILIATION_SUBMIT");
                    row.setUserId("preparer");
                    return auditLogs.saveAndFlush(row).getAuditLogId();
                }));
        try {
            List<UUID> none = List.of(new UUID(0L, 0L));
            org.springframework.data.domain.Pageable page = org.springframework.data.domain.PageRequest.of(0, 50);
            asTenant(
                    TENANT_A,
                    () -> assertThat(auditLogs
                                    .findReconciliationTrail(
                                            "BANK_RECONCILIATION",
                                            reconciliationId,
                                            "RECONCILIATION_MATCH",
                                            none,
                                            "OUTSTANDING_ITEM",
                                            none,
                                            page)
                                    .getTotalElements())
                            .isEqualTo(1));
            asTenant(
                    TENANT_B,
                    () -> assertThat(auditLogs
                                    .findReconciliationTrail(
                                            "BANK_RECONCILIATION",
                                            reconciliationId,
                                            "RECONCILIATION_MATCH",
                                            none,
                                            "OUTSTANDING_ITEM",
                                            none,
                                            page)
                                    .getTotalElements())
                            .as("another tenant's trail")
                            .isZero());
        } finally {
            new JdbcTemplate(ownerDataSource())
                    .update("DELETE FROM accounting_audit_log WHERE audit_log_id = ?", auditLogId);
        }
    }

    private static int countById(JdbcTemplate jdbc, String table, String key, UUID id) {
        Integer count =
                jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + key + " = ?", Integer.class, id);
        return count == null ? 0 : count;
    }

    private static OverridePolicyThreshold policy(String role) {
        return OverridePolicyThreshold.builder()
                .role(role)
                .maxAbsoluteAmount(BigDecimal.valueOf(50))
                .maxPercentOff(BigDecimal.valueOf(10))
                .version("1.0")
                .effectiveDate(Instant.now())
                .active(true)
                .build();
    }

    @Test
    void aRowWrittenAsOneTenantIsInvisibleToAnotherAndToNoTenant() {
        String role = "isolation-" + UUID.randomUUID();
        UUID id = asTenant(TENANT_A, () -> policies.saveAndFlush(policy(role)).getPolicyId());

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        asTenant(TENANT_A, () -> {
            assertThat(policies.findById(id))
                    .as("owner reads through the repository")
                    .isPresent();
            assertThat(policies.findById(id).orElseThrow().getTenantId()).isEqualTo(TENANT_A);
            assertThat(countByRole(jdbc, role))
                    .as("owner reads through raw SQL")
                    .isEqualTo(1);
        });

        asTenant(TENANT_B, () -> {
            assertThat(policies.findById(id))
                    .as("Hibernate filter hides the other tenant's row")
                    .isEmpty();
            assertThat(countByRole(jdbc, role))
                    .as("RLS hides it from raw SQL too")
                    .isZero();
            assertThat(jdbc.update("UPDATE override_policy_threshold SET max_percent_off = 99 WHERE policy_id = ?", id))
                    .as("RLS makes the row unreachable for UPDATE")
                    .isZero();
        });

        // Unbound: the pool RESETs app.current_tenant, so pos_app sees an empty table and cannot insert.
        assertThat(countByRole(jdbc, role)).isZero();
        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO override_policy_threshold (policy_id, role, max_absolute_amount, max_percent_off,
                            effective_date, version, active, created_at, updated_at)
                        VALUES (?, ?, 50, 10, now(), '1.0', true, now(), now())
                        """, UUID.randomUUID(), "unbound-" + UUID.randomUUID()))
                .as("no tenant bound: the NOT NULL default is NULL and the policy's WITH CHECK refuses the row")
                .isInstanceOf(DataAccessException.class);

        asTenant(
                TENANT_A,
                () -> assertThat(policies.findById(id).orElseThrow().getMaxPercentOff())
                        .as("tenant B's UPDATE touched nothing")
                        .isEqualByComparingTo(BigDecimal.valueOf(10)));
    }

    private static int countByRole(JdbcTemplate jdbc, String role) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM override_policy_threshold WHERE role = ?", Integer.class, role);
        return count == null ? 0 : count;
    }
}
