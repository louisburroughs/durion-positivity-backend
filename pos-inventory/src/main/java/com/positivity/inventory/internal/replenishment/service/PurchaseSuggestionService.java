package com.positivity.inventory.internal.replenishment.service;

import com.positivity.inventory.internal.dto.purchasesuggestion.ConvertPurchaseSuggestionsRequest;
import com.positivity.inventory.internal.dto.purchasesuggestion.ConvertPurchaseSuggestionsResponse;
import com.positivity.inventory.internal.dto.purchasesuggestion.PurchaseSuggestionResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Purchase suggestion lifecycle (odoo-parity F4, issue #1044; spec F4, plan D-3):
 * SUGGESTED → ACCEPTED → CONVERTED | DISMISSED. Suggestions are created by the
 * replenishment scan only; every transition here is human-initiated (D-3: the scan never
 * accepts or converts — conversion produces a DRAFT purchase order that still passes the
 * existing PO approval workflow).
 */
public interface PurchaseSuggestionService {

    /** Lists suggestions, optionally filtered by status, SKU, and/or destination location. */
    @NonNull
    Page<PurchaseSuggestionResponse> listPurchaseSuggestions(
            @Nullable String status, @Nullable String itemSKU, @Nullable UUID locationId, @NonNull Pageable pageable);

    @NonNull
    PurchaseSuggestionResponse getPurchaseSuggestion(@NonNull UUID suggestionId);

    /** SUGGESTED → ACCEPTED; any other current status is a 409 conflict. */
    @NonNull
    PurchaseSuggestionResponse acceptPurchaseSuggestion(@NonNull UUID suggestionId, @NonNull String actorId);

    /** SUGGESTED/ACCEPTED → DISMISSED with a mandatory reason; terminal statuses are a 409 conflict. */
    @NonNull
    PurchaseSuggestionResponse dismissPurchaseSuggestion(
            @NonNull UUID suggestionId, @NonNull String reason, @NonNull String actorId);

    /**
     * Converts ACCEPTED suggestions sharing one vendor into a single multi-line DRAFT
     * purchase order through the existing PO service, stamping each suggestion CONVERTED
     * with the created PO id. Violated preconditions raise
     * {@code PurchaseSuggestionConversionException} (422).
     */
    @NonNull
    ConvertPurchaseSuggestionsResponse convertPurchaseSuggestions(
            @NonNull ConvertPurchaseSuggestionsRequest request, @NonNull String actorId);
}
