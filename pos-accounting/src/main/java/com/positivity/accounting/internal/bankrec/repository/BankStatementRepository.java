package com.positivity.accounting.internal.bankrec.repository;

import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link BankStatement} headers (SPEC §3.1; story S1, #2300). */
public interface BankStatementRepository extends JpaRepository<BankStatement, UUID> {}
