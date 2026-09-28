package com.positivity.accounting.internal.bankrec.intake;

import com.positivity.accounting.internal.bankrec.entity.BankAccountProfile;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.repository.BankAccountProfileRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.BankCashAccounts;
import com.positivity.accounting.internal.bankrec.service.BankCashAccounts.BankCashAccount;
import com.positivity.accounting.internal.bankrec.service.BankRecAuditRecorder;
import com.positivity.accounting.internal.bankrec.service.FunctionalCurrency;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.StatementHeader;
import java.time.Clock;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The read side of the intake port (SPEC §2.1, §4.2–§4.5; story S3, #2302). */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BankIntakeLookupImpl implements BankIntakeLookup {

    /** Rows that never raise a fingerprint collision at intake (R1, §4.5), as in the intake. */
    private static final Set<BankTransactionStatus> NOT_COLLIDING =
            EnumSet.of(BankTransactionStatus.EXCLUDED, BankTransactionStatus.REMOVED_BY_SOURCE);

    private final BankCashAccounts bankCashAccounts;
    private final FunctionalCurrency functionalCurrency;
    private final BankStatementRepository statements;
    private final BankTransactionRepository transactions;
    private final BankAccountProfileRepository profiles;
    private final BankRecAuditRecorder audit;
    private final Clock clock;

    @Override
    public @NonNull BankAccountTerms requireAccount(@NonNull UUID glAccountId) {
        BankCashAccount account = bankCashAccounts.requireForIntake(glAccountId);
        Optional<BankAccountProfile> profile = profiles.findById(glAccountId);
        String currency = profile.map(BankAccountProfile::getCurrency).orElseGet(functionalCurrency::code);
        int fractionDigits =
                Math.max(0, java.util.Currency.getInstance(currency).getDefaultFractionDigits());
        return new BankAccountTerms(
                account.glAccountId(),
                account.accountCode(),
                account.accountName(),
                currency,
                fractionDigits,
                profile.map(BankAccountProfile::getDefaultColumnMapping).orElse(null),
                profile.isPresent());
    }

    @Override
    public @NonNull HeaderCheck checkHeader(
            @NonNull UUID glAccountId, @NonNull StatementHeader header, @Nullable String gapAcknowledgement) {
        return StatementHeaderChecks.check(
                statements, functionalCurrency, clock, glAccountId, header, gapAcknowledgement);
    }

    @Override
    public @NonNull Map<String, UUID> collidingFingerprints(
            @NonNull UUID glAccountId, @NonNull Collection<String> fingerprints) {
        Map<String, UUID> earliest = new HashMap<>();
        if (fingerprints.isEmpty()) {
            return earliest;
        }
        List<BankTransaction> colliding = transactions.findByGlAccountIdAndFingerprintInAndStatusNotIn(
                glAccountId, Set.copyOf(fingerprints), NOT_COLLIDING);
        colliding.stream()
                .sorted(Comparator.comparing(
                                BankTransaction::getFirstObservedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(BankTransaction::getBankTransactionId))
                .forEach(t -> earliest.putIfAbsent(t.getFingerprint(), t.getBankTransactionId()));
        return earliest;
    }

    @Override
    @Transactional
    public boolean saveDefaultColumnMapping(
            @NonNull UUID glAccountId, @NonNull Map<String, Object> mapping, @NonNull String actor) {
        Optional<BankAccountProfile> found = profiles.findById(glAccountId);
        if (found.isEmpty()) {
            return false;
        }
        BankAccountProfile profile = found.get();
        Map<String, Object> previous = profile.getDefaultColumnMapping();
        profile.setDefaultColumnMapping(Map.copyOf(mapping));
        profiles.save(profile);
        audit.record(
                BankRecAuditRecorder.BANK_ACCOUNT_PROFILE,
                glAccountId,
                BankRecAuditRecorder.BANK_ACCOUNT_PROFILE_SET,
                actor,
                null,
                previous == null ? null : "defaultColumnMapping=" + previous,
                "defaultColumnMapping=" + mapping);
        return true;
    }
}
