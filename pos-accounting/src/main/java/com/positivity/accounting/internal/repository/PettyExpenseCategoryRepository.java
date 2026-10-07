package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.PettyExpenseCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PettyExpenseCategoryRepository extends JpaRepository<PettyExpenseCategory, UUID> {

    @NonNull
    Optional<PettyExpenseCategory> findByCode(@NonNull String code);

    boolean existsByCode(@NonNull String code);

    /** Every category of the tenant in code order, for the read and the bootstrap republish. */
    @NonNull
    List<PettyExpenseCategory> findAllByOrderByCodeAsc();
}
