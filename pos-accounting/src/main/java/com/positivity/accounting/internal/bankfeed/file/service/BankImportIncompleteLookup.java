package com.positivity.accounting.internal.bankfeed.file.service;

import com.positivity.accounting.internal.bankfeed.file.enums.BankImportStatus;
import com.positivity.accounting.internal.bankfeed.file.repository.BankImportRepository;
import com.positivity.accounting.internal.bankrec.intake.IncompleteImportLookup;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The file adapter's answer to close readiness (SPEC §5.3 {@code INCOMPLETE_IMPORTS}; story S6, #2305): imports
 * still {@code UPLOADED} or {@code VALIDATED} with a row dated on/before the day.
 */
@Component
@RequiredArgsConstructor
public class BankImportIncompleteLookup implements IncompleteImportLookup {

    private static final List<BankImportStatus> INCOMPLETE =
            List.of(BankImportStatus.UPLOADED, BankImportStatus.VALIDATED);

    private final BankImportRepository imports;

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<UUID> incompleteImportIds(@NonNull UUID glAccountId, @NonNull LocalDate day) {
        return imports.findIdsWithRowsOnOrBefore(glAccountId, INCOMPLETE, day);
    }
}
