package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.BillIntakeReadingAllowance;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for the reading-allowance replica; the key is the tenant's own id (#2619). */
public interface BillIntakeReadingAllowanceRepository extends JpaRepository<BillIntakeReadingAllowance, UUID> {}
