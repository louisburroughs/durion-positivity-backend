package com.positivity.accounting.internal.bankrec.readmodel;

import com.positivity.accounting.internal.bankrec.entity.BankAccountProfile;
import com.positivity.accounting.internal.bankrec.repository.BankAccountProfileRepository;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The display labels a bank account's profile records (bank name and masked number), for callers outside the bank
 * reconciliation core that may reach it only through its read model (the AP pay-from read, #2670). The mask is
 * CONFIDENTIAL (ADR-0072): served, never logged. An account without a profile has no label.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankAccountLabels {

    private final BankAccountProfileRepository profiles;

    /** The bank name and masked number of one profile; either may be null. */
    public record Label(@Nullable String bankName, @Nullable String accountMask) {

        @Override
        public String toString() {
            // accountMask is CONFIDENTIAL (ADR-0072): never printed.
            return "Label[bankName=" + bankName + "]";
        }
    }

    /** The labels of the accounts among {@code glAccountIds} that have a profile, in one query. */
    public @NonNull Map<UUID, Label> labelsOf(@NonNull Collection<UUID> glAccountIds) {
        if (glAccountIds.isEmpty()) {
            return Map.of();
        }
        return profiles.findAllById(glAccountIds).stream()
                .collect(Collectors.toMap(
                        BankAccountProfile::getGlAccountId,
                        profile -> new Label(profile.getBankName(), profile.getAccountMask())));
    }
}
