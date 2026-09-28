package com.positivity.accounting.internal.bankfeed.file.repository;

import com.positivity.accounting.internal.bankfeed.file.entity.BankImportFile;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link BankImportFile} raw bytes, keyed by import id (SPEC §3.3, D13; story S1, #2300). */
public interface BankImportFileRepository extends JpaRepository<BankImportFile, UUID> {}
