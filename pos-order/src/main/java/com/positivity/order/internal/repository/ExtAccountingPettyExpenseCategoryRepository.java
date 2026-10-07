package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.ExtAccountingPettyExpenseCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** pos-order's copy of accounting's petty-expense categories (#2512, ADR-0044 R3). */
public interface ExtAccountingPettyExpenseCategoryRepository
        extends JpaRepository<ExtAccountingPettyExpenseCategory, UUID> {

    Optional<ExtAccountingPettyExpenseCategory> findByCode(String code);

    List<ExtAccountingPettyExpenseCategory> findByStatusOrderByCodeAsc(String status);
}
