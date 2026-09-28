package com.positivity.accounting.internal.bankrec.repository;

import com.positivity.accounting.internal.bankrec.entity.BankAccountProfile;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link BankAccountProfile} rows, keyed by GL account id (SPEC §3.1, D21; story S1, #2300). */
public interface BankAccountProfileRepository extends JpaRepository<BankAccountProfile, UUID> {}
