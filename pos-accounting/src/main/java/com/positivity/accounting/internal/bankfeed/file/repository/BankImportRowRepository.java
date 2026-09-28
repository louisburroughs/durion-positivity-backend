package com.positivity.accounting.internal.bankfeed.file.repository;

import com.positivity.accounting.internal.bankfeed.file.entity.BankImportRow;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link BankImportRow} parsed rows (SPEC §3.3; story S1, #2300). */
public interface BankImportRowRepository extends JpaRepository<BankImportRow, UUID> {}
