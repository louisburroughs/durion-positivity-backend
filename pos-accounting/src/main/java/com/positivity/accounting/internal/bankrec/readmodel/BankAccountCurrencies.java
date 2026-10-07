package com.positivity.accounting.internal.bankrec.readmodel;

import com.positivity.accounting.internal.bankrec.entity.BankAccountProfile;
import com.positivity.accounting.internal.bankrec.repository.BankAccountProfileRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The currency a bank account's profile records (ADR-0067), for callers outside the bank
 * reconciliation core that may reach it only through its read model (the register float's Change
 * float, #2511). An account without a profile states no currency.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankAccountCurrencies {

    private final BankAccountProfileRepository profiles;

    /** The ISO 4217 code of the account's profile, if it has one. */
    public @NonNull Optional<String> currencyOf(@NonNull UUID glAccountId) {
        return profiles.findById(glAccountId).map(BankAccountProfile::getCurrency);
    }
}
