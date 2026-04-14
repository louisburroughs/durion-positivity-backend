package com.positivity.catalog.service;

import com.positivity.catalog.internal.dto.PriceBookCreateRequestDto;
import com.positivity.catalog.internal.dto.PriceBookDto;
import com.positivity.catalog.internal.dto.PriceBookRuleCreateRequestDto;
import com.positivity.catalog.internal.dto.PriceBookRuleDto;
import com.positivity.catalog.internal.dto.ResolvePriceRequestDto;
import com.positivity.catalog.internal.dto.ResolvePriceResponseDto;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface PriceBookService {
    PriceBookDto createPriceBook(@NonNull PriceBookCreateRequestDto request);

    PriceBookDto getPriceBook(@NonNull UUID priceBookId);

    PriceBookDto updatePriceBook(@NonNull UUID priceBookId, @NonNull PriceBookCreateRequestDto request);

    PriceBookRuleDto createRule(@NonNull UUID priceBookId, @NonNull PriceBookRuleCreateRequestDto request);

    PriceBookRuleDto updateRule(
            @NonNull UUID priceBookId, @NonNull UUID ruleId, @NonNull PriceBookRuleCreateRequestDto request);

    void deactivateRule(@NonNull UUID priceBookId, @NonNull UUID ruleId);

    List<PriceBookRuleDto> listRules(@NonNull UUID priceBookId);

    ResolvePriceResponseDto resolvePrice(@NonNull ResolvePriceRequestDto request);
}
