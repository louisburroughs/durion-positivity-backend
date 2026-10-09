package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.MappingKey;
import com.positivity.accounting.internal.entity.PostingCategory;
import com.positivity.accounting.internal.repository.MappingKeyRepository;
import com.positivity.accounting.internal.repository.PostingCategoryRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Which {@code VENDOR_BILL} expense keys exist (AW39, AW47; AP reads #2670): the {@code EXPENSE_} prefix, a code after
 * it, and {@code isActive}. The expense-category read ({@code GET /v1/accounting/vendor-bills/expense-categories}) and
 * the vendor AP settings write ({@code defaultExpenseMappingKey}) both ask {@link #isListed(String, Boolean)}, so a
 * key the read offers is a key the write accepts, and a key it does not offer is refused.
 */
@Component
@RequiredArgsConstructor
public class VendorBillExpenseKeys {

    private final PostingCategoryRepository postingCategories;
    private final MappingKeyRepository mappingKeys;

    /**
     * The one active-key test: an {@code EXPENSE_<CODE>} key name with a non-empty code, and active.
     *
     * @param keyName  the key's name as stored (upper case)
     * @param isActive the key's {@code isActive}
     */
    static boolean isListed(@Nullable String keyName, @Nullable Boolean isActive) {
        return keyName != null
                && keyName.startsWith(VendorBillPostingService.EXPENSE_KEY_PREFIX)
                && keyName.length() > VendorBillPostingService.EXPENSE_KEY_PREFIX.length()
                && Boolean.TRUE.equals(isActive);
    }

    /** The {@code VENDOR_BILL} posting category, when the tenant has one. */
    public @NonNull Optional<PostingCategory> category() {
        return postingCategories.findByCategoryName(VendorBillPostingService.POSTING_CATEGORY);
    }

    /** Whether {@code keyName} (as stored, upper case) names a listed expense key of the tenant. */
    public boolean isActive(@NonNull String keyName) {
        return category()
                .flatMap(category -> mappingKeys.findByPostingCategory_PostingCategoryIdAndKeyName(
                        category.getPostingCategoryId(), keyName))
                .filter(key -> isListed(keyName, key.getIsActive()))
                .isPresent();
    }

    /** Every listed expense key of {@code category}, in no particular order. */
    public @NonNull List<MappingKey> listed(@NonNull PostingCategory category) {
        return mappingKeys.findByPostingCategory_PostingCategoryId(category.getPostingCategoryId()).stream()
                .filter(key -> isListed(key.getKeyName(), key.getIsActive()))
                .toList();
    }
}
