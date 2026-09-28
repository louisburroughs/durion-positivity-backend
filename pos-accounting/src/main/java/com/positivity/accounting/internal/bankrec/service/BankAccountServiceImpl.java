package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.dto.BankAccountListResponse;
import com.positivity.accounting.internal.bankrec.dto.BankAccountProfileRequest;
import com.positivity.accounting.internal.bankrec.dto.BankAccountProfileResponse;
import com.positivity.accounting.internal.bankrec.dto.BankAccountResponse;
import com.positivity.accounting.internal.bankrec.dto.BankFeedLinkState;
import com.positivity.accounting.internal.bankrec.entity.BankAccountProfile;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.repository.BankAccountProfileRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.BankCashAccounts.BankCashAccount;
import com.positivity.security.common.SecurityContextHelper;
import java.time.LocalDate;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bank-account list and the thin profile (SPEC-manual-bank-reconciliation §3.1, §4.1, §6.1,
 * D5, D18, D21; story S2, #2301). The profile endpoint never touches the reconciliation baseline:
 * only an acknowledged statement moves it.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class BankAccountServiceImpl implements BankAccountService {

    private static final String SYSTEM = "SYSTEM";

    private final BankCashAccounts bankCashAccounts;
    private final FunctionalCurrency functionalCurrency;
    private final BankAccountProfileRepository profiles;
    private final BankStatementRepository statements;
    private final BankTransactionRepository transactions;
    private final BankReconciliationRepository reconciliations;
    private final BankReconciliationOutstandingItemRepository outstandingItems;
    private final BankRecAuditRecorder audit;

    @Override
    @Transactional(readOnly = true)
    public @NonNull BankAccountListResponse listBankAccounts(int page, int size) {
        // Validates the bounds; the in-scope accounts of a tenant are few, so the page is cut in memory.
        BankRecPaging.page(page, size, org.springframework.data.domain.Sort.unsorted());
        List<BankCashAccount> accounts = bankCashAccounts.listActive();
        int fromIndex = (int) Math.min((long) page * size, accounts.size());
        int toIndex = Math.min(fromIndex + size, accounts.size());
        List<BankAccountResponse> rows = accounts.subList(fromIndex, toIndex).stream()
                .map(this::describe)
                .toList();
        int totalPages = (accounts.size() + size - 1) / size;
        return new BankAccountListResponse(rows, (long) accounts.size(), page, size, totalPages);
    }

    @Override
    public @NonNull BankAccountProfileResponse setProfile(
            @NonNull UUID glAccountId, @NonNull BankAccountProfileRequest request) {
        BankCashAccount account = bankCashAccounts.requireByPath(glAccountId);
        String currency = validate(request);
        if (!functionalCurrency.code().equals(currency)) {
            throw BankRecException.field(
                    BankRecErrorCode.CURRENCY_NOT_SUPPORTED,
                    "Currency " + currency + " is not the ledger currency " + functionalCurrency.code(),
                    "currency",
                    "expected " + functionalCurrency.code());
        }
        String actor = currentActor();
        Optional<BankAccountProfile> existing = profiles.findById(glAccountId);
        String before = existing.map(BankAccountServiceImpl::summary).orElse(null);
        BankAccountProfile profile = existing.orElseGet(() -> {
            BankAccountProfile created = new BankAccountProfile(glAccountId);
            created.setCreatedBy(actor);
            return created;
        });
        profile.setBankName(blankToNull(request.getBankName()));
        profile.setAccountMask(blankToNull(request.getAccountMask()));
        profile.setCurrency(currency);
        profile.setDefaultColumnMapping(request.getDefaultColumnMapping());
        profile.setStatementCycleHint(blankToNull(request.getStatementCycleHint()));
        BankAccountProfile saved = profiles.saveAndFlush(profile);
        audit.record(
                BankRecAuditRecorder.BANK_ACCOUNT_PROFILE,
                glAccountId,
                BankRecAuditRecorder.BANK_ACCOUNT_PROFILE_SET,
                actor,
                null,
                before,
                summary(saved));
        return BankAccountProfileResponse.builder()
                .glAccountId(glAccountId)
                .accountCode(account.accountCode())
                .accountName(account.accountName())
                .bankName(saved.getBankName())
                .accountMask(saved.getAccountMask())
                .currency(saved.getCurrency())
                .defaultColumnMapping(saved.getDefaultColumnMapping())
                .statementCycleHint(saved.getStatementCycleHint())
                .reconciliationBaselineDate(saved.getReconciliationBaselineDate())
                .updatedAt(saved.getUpdatedAt())
                .build();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private BankAccountResponse describe(BankCashAccount account) {
        UUID id = account.glAccountId();
        Optional<BankAccountProfile> profile = profiles.findById(id);
        LocalDate baseline =
                profile.map(BankAccountProfile::getReconciliationBaselineDate).orElse(null);
        long unexplained = baseline == null
                ? transactions.countByGlAccountIdAndStatusIn(id, BankTransactionServiceImpl.UNEXPLAINED)
                : transactions.countByGlAccountIdAndStatusInAndTransactionDateGreaterThanEqual(
                        id, BankTransactionServiceImpl.UNEXPLAINED, baseline);
        long openItems = baseline == null
                ? outstandingItems.countByGlAccountIdAndStatus(id, OutstandingItemStatus.OPEN)
                : outstandingItems.countByGlAccountIdAndStatusAndItemDateGreaterThanEqual(
                        id, OutstandingItemStatus.OPEN, baseline);
        return BankAccountResponse.builder()
                .glAccountId(id)
                .accountCode(account.accountCode())
                .accountName(account.accountName())
                .bankName(profile.map(BankAccountProfile::getBankName).orElse(null))
                .accountMask(profile.map(BankAccountProfile::getAccountMask).orElse(null))
                .currency(profile.map(BankAccountProfile::getCurrency).orElse(null))
                .profileExists(profile.isPresent())
                .reconciliationBaselineDate(baseline)
                .coverageFrontier(statements
                        .findFirstByGlAccountIdAndStatusOrderByEndDateDesc(id, BankStatementStatus.COMMITTED)
                        .map(BankStatement::getEndDate)
                        .orElse(null))
                .reconciledFrontier(reconciledFrontier(id, baseline))
                .unexplainedBankTransactionCount(unexplained)
                .openOutstandingItemCount(openItems)
                .feedLinkState(BankFeedLinkState.NONE)
                .build();
    }

    /**
     * The end of the contiguous chain of FINALIZED reconciliations from the baseline (§4.1): windows
     * that end before the baseline are skipped; the chain stops at the first gap.
     */
    private @Nullable LocalDate reconciledFrontier(UUID glAccountId, @Nullable LocalDate baseline) {
        LocalDate next = baseline;
        LocalDate frontier = null;
        for (BankReconciliation reconciliation :
                reconciliations.findByGlAccount_GlAccountIdAndStatusOrderByStatementStartDateAsc(
                        glAccountId, ReconciliationStatus.FINALIZED)) {
            LocalDate start = reconciliation.getStatementStartDate();
            LocalDate end = reconciliation.getStatementEndDate();
            if (start == null || end == null || (next != null && end.isBefore(next))) {
                continue;
            }
            if (next != null && start.isAfter(next)) {
                break;
            }
            frontier = end;
            next = end.plusDays(1);
        }
        return frontier;
    }

    private static String validate(BankAccountProfileRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        String currency = request.getCurrency() == null
                ? null
                : request.getCurrency().trim().toUpperCase(Locale.ROOT);
        if (currency == null || currency.isEmpty()) {
            errors.put("currency", "is required");
        } else if (!isIsoCurrency(currency)) {
            errors.put("currency", "must be an ISO 4217 code");
        }
        if (request.getBankName() != null && request.getBankName().length() > 100) {
            errors.put("bankName", "at most 100 characters");
        }
        if (request.getAccountMask() != null && request.getAccountMask().length() > 8) {
            errors.put("accountMask", "at most 8 characters");
        }
        if (request.getStatementCycleHint() != null
                && request.getStatementCycleHint().length() > 32) {
            errors.put("statementCycleHint", "at most 32 characters");
        }
        if (!errors.isEmpty()) {
            throw new BankRecException(BankRecErrorCode.VALIDATION_ERROR, "The profile request is invalid", errors);
        }
        return currency;
    }

    private static boolean isIsoCurrency(String code) {
        if (code.length() != 3) {
            return false;
        }
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException unknown) {
            return false;
        }
    }

    private static String summary(BankAccountProfile profile) {
        return "bankName=" + profile.getBankName() + ", accountMask=" + profile.getAccountMask() + ", currency="
                + profile.getCurrency() + ", statementCycleHint=" + profile.getStatementCycleHint()
                + ", defaultColumnMapping=" + (profile.getDefaultColumnMapping() == null ? "none" : "set");
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String currentActor() {
        return SecurityContextHelper.isAuthenticated()
                ? SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM)
                : SYSTEM;
    }
}
