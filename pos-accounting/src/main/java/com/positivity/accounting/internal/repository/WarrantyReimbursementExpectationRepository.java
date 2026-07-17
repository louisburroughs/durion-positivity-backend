package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.WarrantyReimbursementExpectation;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarrantyReimbursementExpectationRepository
        extends JpaRepository<WarrantyReimbursementExpectation, UUID> {}
