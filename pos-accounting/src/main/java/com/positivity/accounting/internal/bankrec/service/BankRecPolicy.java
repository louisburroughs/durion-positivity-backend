package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import java.math.BigDecimal;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * The tenant's bank reconciliation policy keys this story reads (SPEC §4.7, §6.4; story S4, #2303). The policy
 * endpoint that writes them is story S6's; until then no row exists and every key reads as unset.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BankRecPolicy {

    /** {@code accounting_configuration} key: the {@code OTHER} adjustment amount above which approve is needed. */
    public static final String OTHER_APPROVAL_THRESHOLD = "BANK_REC_OTHER_APPROVAL_THRESHOLD";

    private final AccountingConfigurationRepository configuration;

    /**
     * The tenant's {@code BANK_REC_OTHER_APPROVAL_THRESHOLD}; empty when no row exists (unset). A value that is
     * not a non-negative amount reads as unset, so the stricter rule applies.
     */
    public @NonNull Optional<BigDecimal> otherApprovalThreshold() {
        return configuration
                .findByConfigKey(OTHER_APPROVAL_THRESHOLD)
                .map(AccountingConfiguration::getConfigValue)
                .flatMap(BankRecPolicy::parse);
    }

    private static Optional<BigDecimal> parse(String value) {
        try {
            BigDecimal amount = new BigDecimal(value.trim());
            return amount.signum() >= 0 ? Optional.of(amount) : Optional.empty();
        } catch (NumberFormatException e) {
            log.warn("{} holds '{}', not an amount; treating it as unset", OTHER_APPROVAL_THRESHOLD, value);
            return Optional.empty();
        }
    }
}
