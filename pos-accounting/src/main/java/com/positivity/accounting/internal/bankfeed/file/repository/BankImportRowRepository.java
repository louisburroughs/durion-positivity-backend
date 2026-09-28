package com.positivity.accounting.internal.bankfeed.file.repository;

import com.positivity.accounting.internal.bankfeed.file.entity.BankImportRow;
import com.positivity.accounting.internal.bankfeed.file.enums.BankImportRowStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link BankImportRow} parsed rows (SPEC §3.3; stories S1 #2300, S3 #2302). */
public interface BankImportRowRepository extends JpaRepository<BankImportRow, UUID> {

    /** Every row of an import in file order. */
    @NonNull
    List<BankImportRow> findByImportIdOrderByRowNumberAsc(@NonNull UUID importId);

    /** A page of an import's rows; the caller sorts by {@code rowNumber}. */
    @NonNull
    Page<BankImportRow> findByImportId(@NonNull UUID importId, @NonNull Pageable pageable);

    /** A page of an import's rows in one status; the caller sorts by {@code rowNumber}. */
    @NonNull
    Page<BankImportRow> findByImportIdAndRowStatus(
            @NonNull UUID importId, @NonNull BankImportRowStatus rowStatus, @NonNull Pageable pageable);

    Optional<BankImportRow> findByRowIdAndImportId(@NonNull UUID rowId, @NonNull UUID importId);
}
