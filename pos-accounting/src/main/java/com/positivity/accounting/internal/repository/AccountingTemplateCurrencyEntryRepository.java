package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.AccountingTemplateCurrencyEntry;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The template's currency-conditional entries (CAP:550 S32d); read under the platform tenant only. */
public interface AccountingTemplateCurrencyEntryRepository
        extends JpaRepository<AccountingTemplateCurrencyEntry, UUID> {}
