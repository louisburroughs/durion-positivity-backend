package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.PettyExpenseCategoryTaxSetting;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** The bound tenant's petty-expense category tax settings (CAP:550 S32d); the tenant filter and RLS scope reads. */
public interface PettyExpenseCategoryTaxSettingRepository extends JpaRepository<PettyExpenseCategoryTaxSetting, UUID> {

    @NonNull
    Optional<PettyExpenseCategoryTaxSetting> findByCode(@NonNull String code);

    /** Every setting in code order, for the settings read. */
    @NonNull
    List<PettyExpenseCategoryTaxSetting> findAllByOrderByCodeAsc();
}
