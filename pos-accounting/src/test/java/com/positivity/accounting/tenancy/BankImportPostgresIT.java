package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.bankfeed.file.dto.BankImportCommitRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportCommitResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportCreateRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportRowUpdateRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportSplitPoint;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportStatementHeader;
import com.positivity.accounting.internal.bankfeed.file.entity.BankImport;
import com.positivity.accounting.internal.bankfeed.file.entity.BankImportFile;
import com.positivity.accounting.internal.bankfeed.file.entity.BankImportRow;
import com.positivity.accounting.internal.bankfeed.file.enums.BankImportRowStatus;
import com.positivity.accounting.internal.bankfeed.file.enums.BankImportStatus;
import com.positivity.accounting.internal.bankfeed.file.repository.BankImportFileRepository;
import com.positivity.accounting.internal.bankfeed.file.repository.BankImportRepository;
import com.positivity.accounting.internal.bankfeed.file.repository.BankImportRowRepository;
import com.positivity.accounting.internal.bankfeed.file.service.BankImportRetentionJob;
import com.positivity.accounting.internal.bankfeed.file.service.BankImportService;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.SourceKind;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.repository.BankAccountProfileRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The statement-file adapter against the real baseline (SPEC-manual-bank-reconciliation §3.1, §4.4,
 * §6.4, §8.2; ADR-0062 §8; story S3, #2302): the commit and the intake are one transaction, a failing
 * E1 leaves nothing, an acknowledged first file moves the baseline in the commit, the committed-file
 * partial unique holds, and the retention job purges only the bound tenant's files. Requires Docker.
 */
@DisplayName("Bank statement file import on Postgres (#2302)")
class BankImportPostgresIT extends PostgresTenancyTestBase {

    private static final String ACK = "First statement reconciled on this account";

    @Autowired
    private BankImportService service;

    @Autowired
    private BankImportRetentionJob retentionJob;

    @Autowired
    private BankImportRepository imports;

    @Autowired
    private BankImportRowRepository rows;

    @Autowired
    private BankImportFileRepository files;

    @Autowired
    private GLAccountRepository glAccounts;

    @Autowired
    private BankStatementRepository statements;

    @Autowired
    private BankTransactionRepository transactions;

    @Autowired
    private BankAccountProfileRepository profiles;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private com.positivity.accounting.internal.bankrec.intake.BankIntakeLookup lookup;

    private UUID account;
    private UUID otherTenantAccount;

    @BeforeEach
    void freshBankAccount() {
        account = inTenantA(this::bankAccount);
    }

    private UUID bankAccount() {
        String suffix = UUIDv7Generator.generate().toString().substring(26);
        GLAccount cash = new GLAccount();
        cash.setGlAccountId(UUIDv7Generator.generate());
        cash.setAccountCode("I" + suffix);
        cash.setAccountName("Import bank " + suffix);
        cash.setAccountType(AccountType.ASSET);
        cash.setAccountSubtype(AccountSubtype.BANK_CASH);
        cash.setReconcilable(true);
        cash.setActivationDate(LocalDateTime.of(2020, 1, 1, 0, 0));
        cash.setCreatedBy("preparer");
        cash.setModifiedBy("preparer");
        return glAccounts.saveAndFlush(cash).getGlAccountId();
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        List<UUID> accounts = new ArrayList<>(List.of(account));
        if (otherTenantAccount != null) {
            accounts.add(otherTenantAccount);
        }
        for (UUID id : accounts) {
            // Staging rows reference the bank transactions and the import the statement: they go first.
            owner.update(
                    "DELETE FROM accounting_audit_log WHERE entity_id = ? OR entity_id IN"
                            + " (SELECT statement_id FROM bank_statement WHERE gl_account_id = ?) OR entity_id IN"
                            + " (SELECT import_id FROM bank_import WHERE gl_account_id = ?)",
                    id,
                    id,
                    id);
            owner.update(
                    "DELETE FROM bank_import_row WHERE import_id IN (SELECT import_id FROM bank_import WHERE"
                            + " gl_account_id = ?)",
                    id);
            owner.update(
                    "DELETE FROM bank_import_file WHERE import_id IN (SELECT import_id FROM bank_import WHERE"
                            + " gl_account_id = ?)",
                    id);
            owner.update("DELETE FROM bank_import WHERE gl_account_id = ?", id);
            owner.update("DELETE FROM bank_transaction WHERE gl_account_id = ?", id);
            owner.update("DELETE FROM bank_statement WHERE gl_account_id = ?", id);
            owner.update("DELETE FROM bank_account_profile WHERE gl_account_id = ?", id);
            owner.update("DELETE FROM gl_account WHERE gl_account_id = ?", id);
        }
        otherTenantAccount = null;
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private <T> T inTenantA(Supplier<T> work) {
        return asTenant(TENANT_A, () -> new TransactionTemplate(transactionManager).execute(status -> work.get()));
    }

    private BankImportCreateRequest upload(String csv, String start, String end, String opening, String closing) {
        return BankImportCreateRequest.builder()
                .glAccountId(account)
                .requestId(UUIDv7Generator.generate())
                .formatCode("CSV")
                .fileName("statement.csv")
                .content(Base64.getEncoder().encodeToString(csv.getBytes(StandardCharsets.UTF_8)))
                .statement(BankImportStatementHeader.builder()
                        .startDate(LocalDate.parse(start))
                        .endDate(LocalDate.parse(end))
                        .openingBalance(new BigDecimal(opening))
                        .closingBalance(new BigDecimal(closing))
                        .build())
                .gapAcknowledgement(ACK)
                .build();
    }

    private BankImportResponse create(BankImportCreateRequest request) {
        BankImportResponse response = inTenantA(() -> service.create(request, null, null, null));
        return response;
    }

    private int auditRows(UUID entityId, String operation) {
        Integer count = new JdbcTemplate(ownerDataSource())
                .queryForObject(
                        "SELECT count(*) FROM accounting_audit_log WHERE entity_id = ? AND operation = ?",
                        Integer.class,
                        entityId,
                        operation);
        return count == null ? 0 : count;
    }

    private List<BankTransaction> transactionsOfAccount() {
        return inTenantA(() -> transactions.findAll().stream()
                .filter(t -> account.equals(t.getGlAccountId()))
                .sorted(Comparator.comparing(BankTransaction::getSourceRowNumber))
                .toList());
    }

    private List<BankStatement> statementsOfAccount() {
        return inTenantA(() -> statements.findAll().stream()
                .filter(s -> account.equals(s.getGlAccountId()))
                .sorted(Comparator.comparing(BankStatement::getStartDate))
                .toList());
    }

    private static BankRecErrorCode codeOf(Throwable thrown) {
        Throwable cause = thrown;
        while (cause != null && !(cause instanceof BankRecException)) {
            cause = cause.getCause();
        }
        assertThat(cause).as("a BankRecException in %s", thrown).isNotNull();
        return ((BankRecException) cause).code();
    }

    // ---- 240 rows, three bad dates (§4.4, §8.2), the acknowledged first file (§3.1) ------------

    @Test
    @DisplayName("240 rows with 3 bad dates: corrected, committed as 240 transactions, baseline set in the commit")
    void aLargeFileWithRejectedRowsIsCorrectedAndCommittedWithTheBaseline() {
        StringBuilder csv = new StringBuilder("date,description,amount,reference\n");
        for (int n = 1; n <= 240; n++) {
            String date = (n == 10 || n == 100 || n == 200)
                    ? String.format("2025-13-%02d", n % 28 + 1)
                    : String.format("2025-09-%02d", n % 30 + 1);
            csv.append(date)
                    .append(",DEPOSIT ")
                    .append(n)
                    .append(",1.00,R")
                    .append(n)
                    .append('\n');
        }
        BankImportResponse uploaded = create(upload(csv.toString(), "2025-09-01", "2025-09-30", "1000.00", "1240.00"));
        UUID importId = uploaded.getImportId();
        assertThat(uploaded.getStatus()).isEqualTo(BankImportStatus.VALIDATED);
        assertThat(uploaded.getAcceptedCount()).isEqualTo(237);
        assertThat(uploaded.getRejectedCount()).isEqualTo(3);

        List<BankImportRow> rejected = inTenantA(() -> rows.findByImportIdOrderByRowNumberAsc(importId).stream()
                .filter(r -> r.getRowStatus() == BankImportRowStatus.REJECTED)
                .toList());
        assertThat(rejected).extracting(BankImportRow::getRowNumber).containsExactly(10, 100, 200);
        assertThat(rejected).allSatisfy(r -> assertThat(r.getRejectionCode()).isEqualTo("DATE_UNPARSEABLE"));
        Map<String, Object> rawBefore = rejected.getFirst().getRawValues();

        assertThatThrownBy(() -> inTenantA(() -> service.commit(importId, null)))
                .isInstanceOfSatisfying(BankRecException.class, e -> {
                    assertThat(e.code()).isEqualTo(BankRecErrorCode.IMPORT_NOT_COMMITTABLE);
                    assertThat(e.fieldErrors()).containsKeys("rows[10]", "rows[100]", "rows[200]");
                });

        for (BankImportRow row : rejected) {
            inTenantA(() -> service.updateRow(
                    importId,
                    row.getRowId(),
                    BankImportRowUpdateRequest.builder()
                            .correctedValues(Map.of("date", "2025-09-15"))
                            .build()));
        }
        BankImportCommitResponse committed = inTenantA(() -> service.commit(importId, null));

        assertThat(committed.getBankTransactionCount()).isEqualTo(240);
        List<BankTransaction> stored = transactionsOfAccount();
        assertThat(stored).hasSize(240);
        assertThat(stored)
                .extracting(BankTransaction::getSourceRowNumber)
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(1, 240).boxed().toList());
        assertThat(stored).allSatisfy(t -> {
            assertThat(t.getSourceKind()).isEqualTo(SourceKind.FILE_IMPORT);
            assertThat(t.getSourceRef()).isEqualTo(importId);
            assertThat(t.getConnectorCode()).isEqualTo("csv-v1");
            assertThat(t.getStatementId()).isEqualTo(committed.getStatementId());
            assertThat(t.getStatus()).isEqualTo(BankTransactionStatus.UNMATCHED);
        });

        // The acknowledgement travelled to the intake: stored on the statement, baseline moved, one audit row.
        BankStatement statement = statementsOfAccount().getFirst();
        assertThat(statement.getGapAcknowledgement()).isEqualTo(ACK);
        assertThat(statement.getSourceRef()).isEqualTo(importId);
        assertThat(inTenantA(() -> profiles.findById(account).orElseThrow().getReconciliationBaselineDate()))
                .isEqualTo(LocalDate.of(2025, 9, 1));
        assertThat(auditRows(account, "BANK_ACCOUNT_BASELINE_SET")).isEqualTo(1);
        assertThat(auditRows(importId, "BANK_IMPORT_COMMIT")).isEqualTo(1);
        assertThat(auditRows(importId, "BANK_IMPORT_ROW_CORRECT")).isEqualTo(3);

        List<BankImportRow> after = inTenantA(() -> rows.findByImportIdOrderByRowNumberAsc(importId));
        assertThat(after).allSatisfy(r -> {
            assertThat(r.getRowStatus()).isEqualTo(BankImportRowStatus.COMMITTED);
            assertThat(r.getBankTransactionId()).isNotNull();
        });
        assertThat(after.get(9).getRawValues()).isEqualTo(rawBefore);
    }

    // ---- one transaction (§4.4) ----------------------------------------------------------------

    @Test
    @DisplayName("E1 failing at commit leaves no statement, transaction, profile, baseline or committed row")
    void anE1FailureLeavesNothing() {
        BankImportResponse uploaded = create(
                upload("date,description,amount\n2025-09-02,DEP,985.00", "2025-09-01", "2025-09-30", "9000", "10000"));

        assertThatThrownBy(() -> inTenantA(() -> service.commit(uploaded.getImportId(), null)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(BankRecErrorCode.IMPORT_NOT_COMMITTABLE));

        assertThat(statementsOfAccount()).isEmpty();
        assertThat(transactionsOfAccount()).isEmpty();
        assertThat(inTenantA(() -> profiles.findById(account))).isEmpty();
        assertThat(auditRows(account, "BANK_ACCOUNT_BASELINE_SET")).isZero();
        BankImport after =
                inTenantA(() -> imports.findById(uploaded.getImportId()).orElseThrow());
        assertThat(after.getStatus()).isEqualTo(BankImportStatus.VALIDATED);
        assertThat(inTenantA(() -> rows.findByImportIdOrderByRowNumberAsc(uploaded.getImportId())))
                .noneMatch(r -> r.getRowStatus() == BankImportRowStatus.COMMITTED);
    }

    @Test
    @DisplayName("the commit joins the caller's transaction: rolled back, the intake's writes go with it")
    void theCommitAndTheIntakeAreOneTransaction() {
        BankImportResponse uploaded = create(
                upload("date,description,amount\n2025-09-02,DEP,500.00", "2025-09-01", "2025-09-30", "0", "500"));

        asTenant(
                TENANT_A,
                () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    service.commit(uploaded.getImportId(), null);
                    assertThat(statements.findAll().stream().anyMatch(s -> account.equals(s.getGlAccountId())))
                            .as("the statement is written inside the transaction")
                            .isTrue();
                    status.setRollbackOnly();
                }));

        assertThat(statementsOfAccount()).isEmpty();
        assertThat(transactionsOfAccount()).isEmpty();
        assertThat(inTenantA(() -> profiles.findById(account))).isEmpty();
        assertThat(inTenantA(() ->
                        imports.findById(uploaded.getImportId()).orElseThrow().getStatus()))
                .isEqualTo(BankImportStatus.VALIDATED);
    }

    // ---- duplicates, split, whole-file (§4.3, §4.5, §5.7, §8.2) ---------------------------------

    @Test
    @DisplayName("a collision query over more fingerprints than Postgres takes bind parameters still answers")
    void aCollisionQueryBeyondTheBindParameterLimitAnswers() {
        BankImportResponse uploaded = create(
                upload("date,description,amount\n2025-09-02,FEE,-5.00", "2025-09-01", "2025-09-30", "100", "95"));
        inTenantA(() -> service.commit(uploaded.getImportId(), null));
        BankTransaction stored = transactionsOfAccount().getFirst();

        // 70,000 fingerprints: one IN list would exceed the 65,535 bind parameters of a Postgres statement.
        List<String> fingerprints = new ArrayList<>(java.util.stream.IntStream.range(0, 70_000)
                .mapToObj(i -> String.format("%064d", i))
                .toList());
        fingerprints.add(stored.getFingerprint());

        Map<String, UUID> colliding = inTenantA(() -> lookup.collidingFingerprints(account, fingerprints));

        assertThat(colliding).containsExactly(Map.entry(stored.getFingerprint(), stored.getBankTransactionId()));
    }

    @Test
    @DisplayName("two identical $5.00 rows confirmed DISTINCT become two UNMATCHED transactions")
    void twoIdenticalRowsConfirmedDistinctAreBothUnmatched() {
        BankImportResponse uploaded = create(upload(
                "date,description,amount\n2025-09-02,FEE,-5.00\n2025-09-02,FEE,-5.00",
                "2025-09-01",
                "2025-09-30",
                "100",
                "90"));
        assertThat(uploaded.getPossibleDuplicateCount()).isEqualTo(2);

        BankImportCommitResponse committed = inTenantA(() -> service.commit(
                uploaded.getImportId(),
                BankImportCommitRequest.builder()
                        .duplicateDecisions(List.of(
                                new BankImportCommitRequest.DuplicateDecision(1, "DISTINCT"),
                                new BankImportCommitRequest.DuplicateDecision(2, "DISTINCT")))
                        .build()));

        assertThat(committed.getPossibleDuplicateCount()).isZero();
        assertThat(transactionsOfAccount())
                .extracting(BankTransaction::getStatus)
                .containsExactly(BankTransactionStatus.UNMATCHED, BankTransactionStatus.UNMATCHED);
    }

    @Test
    @DisplayName("a split file commits one contiguous statement per segment")
    void aSplitFileCommitsTwoContiguousStatements() {
        BankImportCreateRequest request = upload(
                "date,description,amount\n2025-09-20,SEP,100\n2025-10-05,OCT,50",
                "2025-09-15",
                "2025-10-14",
                "1000",
                "1150");
        request.setSplitAt(List.of(new BankImportSplitPoint(LocalDate.of(2025, 9, 30), new BigDecimal("1100"))));
        BankImportResponse uploaded = create(request);

        BankImportCommitResponse committed = inTenantA(() -> service.commit(uploaded.getImportId(), null));

        List<BankStatement> stored = statementsOfAccount();
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).getEndDate()).isEqualTo(LocalDate.of(2025, 9, 30));
        assertThat(stored.get(0).getClosingBalance()).isEqualByComparingTo("1100");
        assertThat(stored.get(0).getGapAcknowledgement()).isEqualTo(ACK);
        assertThat(stored.get(1).getStartDate()).isEqualTo(LocalDate.of(2025, 10, 1));
        assertThat(stored.get(1).getOpeningBalance()).isEqualByComparingTo("1100");
        assertThat(stored.get(1).getGapAcknowledgement()).isNull();
        assertThat(committed.getStatementIds())
                .containsExactly(stored.get(0).getStatementId(), stored.get(1).getStatementId());
    }

    @Test
    @DisplayName("the committed-file partial unique refuses a second COMMITTED import of the same bytes")
    void theCommittedFilePartialUniqueHolds() {
        String csv = "date,description,amount\n2025-09-02,DEP,500.00";
        BankImportResponse first = create(upload(csv, "2025-09-01", "2025-09-30", "0", "500"));
        inTenantA(() -> service.commit(first.getImportId(), null));

        // The same file again is refused at the door, naming the earlier import and statement.
        assertThatThrownBy(() -> create(upload(csv, "2025-10-01", "2025-10-31", "500", "1000")))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(BankRecErrorCode.IMPORT_FILE_ALREADY_COMMITTED));

        // Around the service, the database refuses a second COMMITTED row; a DISCARDED one is fine.
        BankImport copy = inTenantA(() -> {
            BankImport original = imports.findById(first.getImportId()).orElseThrow();
            BankImport duplicate = new BankImport();
            duplicate.setGlAccountId(account);
            duplicate.setCurrency("USD");
            duplicate.setFormatCode("CSV");
            duplicate.setFileSha256(original.getFileSha256());
            duplicate.setStatus(BankImportStatus.DISCARDED);
            return imports.saveAndFlush(duplicate);
        });
        assertThatThrownBy(() -> inTenantA(() -> {
                    BankImport again = imports.findById(copy.getImportId()).orElseThrow();
                    again.setStatus(BankImportStatus.COMMITTED);
                    return imports.saveAndFlush(again);
                }))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("bank_import_committed_file_uk");
    }

    // ---- retention (§6.4, D13, ADR-0062 §8) ----------------------------------------------------

    @Test
    @DisplayName("the retention job purges only the bound tenant's expired files and keeps the metadata")
    void theRetentionJobPurgesOnlyTheBoundTenantsFiles() {
        UUID mine = expiredImport(TENANT_A);
        UUID theirs = expiredImport(TENANT_B);

        int purged = inTenantA(() -> retentionJob.purgeForBoundTenant());

        assertThat(purged).isGreaterThanOrEqualTo(1);
        assertThat(inTenantA(() -> files.findById(mine))).isEmpty();
        BankImport kept = inTenantA(() -> imports.findById(mine).orElseThrow());
        assertThat(kept.getFilePurgedAt()).isNotNull();
        assertThat(kept.getRetentionUntil()).isEqualTo(LocalDate.of(2020, 1, 1));
        assertThat(auditRows(mine, "BANK_IMPORT_FILE_PURGE")).isEqualTo(1);
        java.util.Optional<BankImportFile> theirFile = asTenant(
                TENANT_B, () -> new TransactionTemplate(transactionManager).execute(status -> files.findById(theirs)));
        assertThat(theirFile).as("the other tenant's file is untouched").isPresent();
        assertThatThrownBy(() -> inTenantA(() -> service.download(mine)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(BankRecErrorCode.BANK_IMPORT_FILE_NOT_FOUND));
    }

    private UUID expiredImport(UUID tenant) {
        return asTenant(
                tenant,
                () -> new TransactionTemplate(transactionManager).execute(status -> {
                    UUID owningAccount = account;
                    if (!TENANT_A.equals(tenant)) {
                        // The import's account belongs to its own tenant (composite FK, ADR-0062 §9).
                        otherTenantAccount = bankAccount();
                        owningAccount = otherTenantAccount;
                    }
                    BankImport expired = new BankImport();
                    expired.setGlAccountId(owningAccount);
                    expired.setCurrency("USD");
                    expired.setFormatCode("CSV");
                    expired.setFileSha256("b".repeat(64));
                    expired.setStatus(BankImportStatus.DISCARDED);
                    expired.setRetentionUntil(LocalDate.of(2020, 1, 1));
                    UUID importId = imports.saveAndFlush(expired).getImportId();
                    BankImportFile file = new BankImportFile(importId);
                    file.setFileBytes("date,description,amount".getBytes(StandardCharsets.UTF_8));
                    file.setRetentionUntil(LocalDate.of(2020, 1, 1));
                    files.saveAndFlush(file);
                    return importId;
                }));
    }
}
