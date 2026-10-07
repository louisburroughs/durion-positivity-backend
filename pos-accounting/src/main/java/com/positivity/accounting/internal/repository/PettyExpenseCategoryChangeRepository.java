package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.PettyExpenseCategoryChange;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PettyExpenseCategoryChangeRepository extends JpaRepository<PettyExpenseCategoryChange, UUID> {

    @NonNull
    Optional<PettyExpenseCategoryChange> findByRequestId(@NonNull UUID requestId);

    /** Every change of the tenant's categories, oldest first, for the history of the read. */
    @NonNull
    List<PettyExpenseCategoryChange> findAllByOrderByChangedAtAscChangeIdAsc();
}
