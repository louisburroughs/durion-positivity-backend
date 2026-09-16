package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.dto.OpeningSearchQuery;
import com.positivity.shopmanager.internal.dto.OpeningSearchResponse;
import org.jspecify.annotations.NonNull;

/**
 * Duration-aware eligible opening search (#2022): ranked windows in which the whole job fits,
 * unbroken, in one eligible bay, with a technician rostered that day and free in the window.
 * Advisory — the submit-time conflict tier is authoritative (DECISION-SHOPMGMT-011), which is why
 * every opening states what it evaluated (spec D11).
 */
public interface OpeningSearchService {

    @NonNull
    OpeningSearchResponse search(@NonNull OpeningSearchQuery query);
}
