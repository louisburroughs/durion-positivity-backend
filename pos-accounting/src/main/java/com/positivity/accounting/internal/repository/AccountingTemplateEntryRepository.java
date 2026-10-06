package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.AccountingTemplateEntry;
import com.positivity.accounting.internal.enums.TemplateEntryOutcome;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** The bound tenant's record of applied template entries (#2526). */
public interface AccountingTemplateEntryRepository extends JpaRepository<AccountingTemplateEntry, UUID> {

    /** The entries in any of the given outcomes, in entry-key order, for the status read. */
    @NonNull
    List<AccountingTemplateEntry> findByOutcomeInOrderByEntryKeyAsc(@NonNull Collection<TemplateEntryOutcome> outcomes);
}
