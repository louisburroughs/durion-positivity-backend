package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.PettyExpenseCategoryCreateRequest;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryDeactivateRequest;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryListResponse;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryRemapRequest;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryResponse;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryUpdateRequest;
import org.jspecify.annotations.NonNull;

/**
 * Petty-expense categories (#2511; SPEC-accounting-workspace §4.6, §7.1 "Petty-expense categories"; AW18):
 * stored as {@code REGISTER_CASH_MOVEMENT} mapping keys {@code PETTY_EXPENSE_<code>} with GL mappings bound to
 * that category and key. Codes are permanent; categories are deactivated, never deleted; there is no "Other".
 * Every command takes its actor from the security context and writes one history row.
 */
public interface PettyExpenseCategoryService {

    /** Every category with its current and later account and history. */
    @NonNull
    PettyExpenseCategoryListResponse list();

    /** Creates the category, its key and its mapping effective today. */
    @NonNull
    PettyExpenseCategoryResponse create(@NonNull PettyExpenseCategoryCreateRequest request);

    /** Changes the label and examples; the code never changes. */
    @NonNull
    PettyExpenseCategoryResponse update(@NonNull String code, @NonNull PettyExpenseCategoryUpdateRequest request);

    /** Deactivates the category; its mapping is kept. */
    @NonNull
    PettyExpenseCategoryResponse deactivate(
            @NonNull String code, @NonNull PettyExpenseCategoryDeactivateRequest request);

    /** Ends the current mapping at {@code effectiveFrom} and adds the new one from that date. */
    @NonNull
    PettyExpenseCategoryResponse remap(@NonNull String code, @NonNull PettyExpenseCategoryRemapRequest request);
}
