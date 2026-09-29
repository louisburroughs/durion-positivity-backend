package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.dto.BankAccountListResponse;
import com.positivity.accounting.internal.bankrec.dto.BankAccountProfileRequest;
import com.positivity.accounting.internal.bankrec.dto.BankAccountProfileResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** Bank accounts in reconciliation scope and their profiles (SPEC §3.1, §4.1, §6.1, D21; #2301). */
public interface BankAccountService {

    /** Every active reconcilable {@code BANK_CASH} account with profile, baseline, frontiers and counts. */
    @NonNull
    BankAccountListResponse listBankAccounts(int page, int size);

    /** Creates or updates the thin profile; never touches the reconciliation baseline. */
    @NonNull
    BankAccountProfileResponse setProfile(@NonNull UUID glAccountId, @NonNull BankAccountProfileRequest request);
}
