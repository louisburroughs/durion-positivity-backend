package com.positivity.accounting.internal.bankfeed.file.repository;

import com.positivity.accounting.internal.bankfeed.file.entity.BankImport;
import com.positivity.accounting.internal.bankfeed.file.enums.BankImportStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

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
}
