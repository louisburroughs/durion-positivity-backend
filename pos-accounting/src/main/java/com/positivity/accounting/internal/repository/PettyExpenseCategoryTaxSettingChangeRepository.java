package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.PettyExpenseCategoryTaxSettingChange;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** The bound tenant's petty-expense category tax setting history (CAP:550 S32d; AW52). */
public interface PettyExpenseCategoryTaxSettingChangeRepository
        extends JpaRepository<PettyExpenseCategoryTaxSettingChange, UUID> {

    @NonNull
    Optional<PettyExpenseCategoryTaxSettingChange> findByRequestId(@NonNull UUID requestId);

    /** The change in force for {@code code} at {@code at}: the latest that took effect at or before it. */
    @NonNull
    Optional<PettyExpenseCategoryTaxSettingChange>
            findFirstByCodeAndEffectiveFromLessThanEqualOrderByEffectiveFromDescCreatedAtDesc(
                    @NonNull String code, @NonNull Instant at);

    /** Every change, oldest first, for the settings read's history. */
    @NonNull
    List<PettyExpenseCategoryTaxSettingChange> findAllByOrderByEffectiveFromAscCreatedAtAsc();
}
