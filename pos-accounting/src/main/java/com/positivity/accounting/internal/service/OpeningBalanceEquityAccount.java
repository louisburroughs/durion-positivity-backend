package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.GLMapping;
import com.positivity.accounting.internal.repository.GLMappingRepository;
import com.positivity.accounting.internal.repository.MappingKeyRepository;
import com.positivity.accounting.internal.repository.PostingCategoryRepository;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * The account the go-live float's counter side posts to on a date (#2511, AW17): {@code REGISTER_FLOAT} /
 * {@code OPENING_BALANCE_EQUITY}, 3900 in the template. Resolved through the mapping, never hard-coded; empty
 * when the tenant has no such mapping. It never throws, so a caller inside a period close's transaction is never
 * marked rollback-only by a missing mapping.
 */
@Component
@RequiredArgsConstructor
public class OpeningBalanceEquityAccount {

    static final String CATEGORY = "REGISTER_FLOAT";
    static final String KEY = "OPENING_BALANCE_EQUITY";

    private final PostingCategoryRepository postingCategories;
    private final MappingKeyRepository mappingKeys;
    private final GLMappingRepository glMappings;

    /** The account the mapping names on {@code date}, if any. */
    public @NonNull Optional<UUID> on(@NonNull LocalDate date) {
        return postingCategories
                .findByCategoryName(CATEGORY)
                .flatMap(category -> mappingKeys.findByPostingCategory_PostingCategoryIdAndKeyName(
                        category.getPostingCategoryId(), KEY))
                .flatMap(key -> glMappings
                        .findAllEffectiveMappings(
                                key.getPostingCategoryId(), key.getMappingKeyId(), date.atStartOfDay())
                        .stream()
                        .filter(m ->
                                m.getDimensions() == null || m.getDimensions().isEmpty())
                        .findFirst())
                .map(GLMapping::getGlAccountId);
    }
}
