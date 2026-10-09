package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.bankrec.readmodel.BankAccountLabels;
import com.positivity.accounting.internal.dto.ApPayFromAccountListResponse;
import com.positivity.accounting.internal.dto.VendorBillExpenseCategoryListResponse;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.GLMapping;
import com.positivity.accounting.internal.entity.MappingKey;
import com.positivity.accounting.internal.entity.PostingCategory;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.GLMappingRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ApChoicesService}: the expense keys come from {@link VendorBillExpenseKeys}, the test the vendor AP settings
 * write uses, and the pay-from accounts from {@link ApPayFromAccounts}, the rule the pay command uses, so neither read
 * can offer what its command refuses.
 */
@Service
@RequiredArgsConstructor
public class ApChoicesServiceImpl implements ApChoicesService {

    /** By label case-insensitively (a null label sorts as its key), then by key. */
    static final Comparator<VendorBillExpenseCategoryListResponse.Category> CATEGORY_ORDER = Comparator.comparing(
                    (VendorBillExpenseCategoryListResponse.Category c) ->
                            (c.label() == null ? c.mappingKey() : c.label()).toLowerCase(Locale.ROOT))
            .thenComparing(VendorBillExpenseCategoryListResponse.Category::mappingKey);

    private final VendorBillExpenseKeys expenseKeys;
    private final GLMappingRepository glMappings;
    private final GLAccountRepository glAccounts;
    private final ApPayFromAccounts payFromAccounts;
    private final BankAccountLabels bankAccountLabels;

    @Override
    @Transactional(readOnly = true)
    public @NonNull VendorBillExpenseCategoryListResponse expenseCategories() {
        LocalDate asOf = payFromAccounts.executionDate(payFromAccounts.businessDate());
        Optional<PostingCategory> category = expenseKeys.category();
        if (category.isEmpty()) {
            return new VendorBillExpenseCategoryListResponse(asOf, List.of());
        }
        LocalDateTime at = asOf.atStartOfDay();
        Map<String, UUID> resolved = new HashMap<>();
        List<MappingKey> keys = expenseKeys.listed(category.get());
        for (MappingKey key : keys) {
            UUID account = resolve(category.get(), key, at);
            if (account != null) {
                resolved.put(key.getKeyName(), account);
            }
        }
        Map<UUID, GLAccount> accounts = resolved.isEmpty()
                ? Map.of()
                : glAccounts.findAllById(resolved.values()).stream()
                        .collect(Collectors.toMap(GLAccount::getGlAccountId, Function.identity()));
        List<VendorBillExpenseCategoryListResponse.Category> categories = keys.stream()
                .map(key -> {
                    UUID accountId = resolved.get(key.getKeyName());
                    GLAccount account = accountId == null ? null : accounts.get(accountId);
                    return new VendorBillExpenseCategoryListResponse.Category(
                            key.getKeyName(),
                            key.getDescription(),
                            account == null ? null : account.getAccountCode(),
                            account == null ? null : account.getAccountName());
                })
                .sorted(CATEGORY_ORDER)
                .toList();
        return new VendorBillExpenseCategoryListResponse(asOf, categories);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull ApPayFromAccountListResponse payFromAccounts() {
        LocalDate asOf = payFromAccounts.executionDate(payFromAccounts.businessDate());
        List<GLAccount> eligible = payFromAccounts.eligible(asOf);
        Map<UUID, BankAccountLabels.Label> labels = bankAccountLabels.labelsOf(
                eligible.stream().map(GLAccount::getGlAccountId).toList());
        List<ApPayFromAccountListResponse.Account> accounts = eligible.stream()
                .sorted(Comparator.comparing(
                        GLAccount::getAccountCode, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(account -> {
                    BankAccountLabels.Label label = labels.get(account.getGlAccountId());
                    return new ApPayFromAccountListResponse.Account(
                            account.getGlAccountId(),
                            account.getAccountCode(),
                            account.getAccountName(),
                            label == null ? null : label.bankName(),
                            label == null ? null : label.accountMask());
                })
                .toList();
        return new ApPayFromAccountListResponse(
                asOf,
                payFromAccounts.currencyCode(),
                payFromAccounts.defaultFor(eligible).orElse(null),
                accounts);
    }

    /**
     * The account {@code key} resolves to at {@code at}, or null when no mapping is effective then: {@link
     * GLMappingResolver}'s category-default resolution (a key without dimensions), read without its refusal, which
     * would mark this read-only transaction rollback-only.
     */
    private @Nullable UUID resolve(PostingCategory category, MappingKey key, LocalDateTime at) {
        return glMappings
                .findEffectiveMapping(category.getPostingCategoryId(), key.getMappingKeyId(), at)
                .map(GLMapping::getGlAccountId)
                .orElse(null);
    }
}
