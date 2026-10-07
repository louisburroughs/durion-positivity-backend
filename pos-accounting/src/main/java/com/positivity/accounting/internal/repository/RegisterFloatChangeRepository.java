package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.RegisterFloatChange;
import com.positivity.accounting.internal.enums.RegisterFloatChangeKind;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegisterFloatChangeRepository extends JpaRepository<RegisterFloatChange, UUID> {

    @NonNull
    Optional<RegisterFloatChange> findByRequestId(@NonNull UUID requestId);

    /** The GO_LIVE or CHANGE row that posted this entry, if a float command did. */
    @NonNull
    Optional<RegisterFloatChange> findByJournalEntryIdAndKindIn(
            @NonNull UUID journalEntryId, @NonNull Collection<RegisterFloatChangeKind> kinds);

    /** The float's rows of these kinds that still stand (not reversed). */
    @NonNull
    List<RegisterFloatChange> findByRegisterFloatIdAndKindInAndReversalJournalEntryIdIsNull(
            @NonNull UUID registerFloatId, @NonNull Collection<RegisterFloatChangeKind> kinds);

    /** The float's latest row, for the date and entry a republish names. */
    @NonNull
    Optional<RegisterFloatChange> findFirstByRegisterFloatIdOrderByCreatedAtDescChangeIdDesc(
            @NonNull UUID registerFloatId);
}
