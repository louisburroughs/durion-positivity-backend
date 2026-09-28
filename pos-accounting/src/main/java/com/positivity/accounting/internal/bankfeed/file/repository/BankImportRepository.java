package com.positivity.accounting.internal.bankfeed.file.repository;

import com.positivity.accounting.internal.bankfeed.file.entity.BankImport;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link BankImport} sessions (SPEC §3.3; story S1, #2300). */
public interface BankImportRepository extends JpaRepository<BankImport, UUID> {}
