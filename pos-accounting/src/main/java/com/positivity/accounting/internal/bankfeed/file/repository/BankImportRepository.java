package com.positivity.accounting.internal.bankfeed.file.repository;

import com.positivity.accounting.internal.bankfeed.file.entity.BankImport;
import com.positivity.accounting.internal.bankfeed.file.enums.BankImportStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository for {@link BankImport} sessions (SPEC §3.3; stories S1 #2300, S3 #2302). */
public interface BankImportRepository extends JpaRepository<BankImport, UUID>, JpaSpecificationExecutor<BankImport> {

    /** The import a {@code requestId} created (§6.3 replay). */
    Optional<BankImport> findByRequestId(@NonNull UUID requestId);

    /** The account's import of these exact bytes in a status (COMMITTED: the whole-file duplicate, §4.3). */
    Optional<BankImport> findFirstByGlAccountIdAndFileSha256AndStatus(
            @NonNull UUID glAccountId, @NonNull String fileSha256, @NonNull BankImportStatus status);

    /** Imports whose raw file is past retention and not yet purged (D13), within the bound tenant. */
    @NonNull
    List<BankImport> findByRetentionUntilBeforeAndFilePurgedAtIsNull(@NonNull LocalDate today);

    /**
     * Ids of the account's imports in the given states with a row dated on/before {@code day} (close readiness
     * {@code INCOMPLETE_IMPORTS}, SPEC §5.3; story S6, #2305).
     */
    @Query("SELECT DISTINCT i.importId FROM BankImport i WHERE i.glAccountId = :account AND i.status IN :statuses"
            + " AND EXISTS (SELECT 1 FROM BankImportRow r WHERE r.importId = i.importId AND r.transactionDate <= :day)")
    @NonNull
    List<UUID> findIdsWithRowsOnOrBefore(
            @Param("account") @NonNull UUID glAccountId,
            @Param("statuses") @NonNull Collection<BankImportStatus> statuses,
            @Param("day") @NonNull LocalDate day);
}
