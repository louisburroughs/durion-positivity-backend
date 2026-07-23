package com.positivity.inventory.internal.service;

import static com.positivity.inventory.internal.service.PurchaseSuggestionCreationService.roundedSuggestionQuantity;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MOQ / pack / order-multiple rounding interplay vectors (odoo-parity F4, issue #1044):
 * {@code qty = max(minOrderQty, roundUpToMultiple(need, max(orderMultiple, packSize)))} —
 * MOQ is a floor, pack size and order multiple are multiples, the larger multiple wins,
 * and the MOQ floor applies verbatim after rounding (the vendor's own stated minimum is
 * trusted to be orderable as-is even when not itself a pack multiple).
 */
@DisplayName("Purchase suggestion rounding interplay (F4)")
class PurchaseSuggestionRoundingTest {

    @Test
    @DisplayName("order multiple alone rounds up (need 7, multiple 6 → 12)")
    void orderMultipleAloneRoundsUp() {
        assertThat(roundedSuggestionQuantity(7, 6, null, null)).isEqualTo(12);
    }

    @Test
    @DisplayName("pack size alone rounds up exactly like an order multiple")
    void packSizeAloneRoundsUp() {
        assertThat(roundedSuggestionQuantity(7, null, null, 6)).isEqualTo(12);
    }

    @Test
    @DisplayName("order multiple and pack size together: the larger multiple wins (4 vs 6 → 6)")
    void largerMultipleWins() {
        assertThat(roundedSuggestionQuantity(7, 4, null, 6)).isEqualTo(12);
        assertThat(roundedSuggestionQuantity(7, 6, null, 4)).isEqualTo(12);
        assertThat(roundedSuggestionQuantity(13, 4, null, 6)).isEqualTo(18);
    }

    @Test
    @DisplayName("an exact multiple stays as-is")
    void exactMultipleUnchanged() {
        assertThat(roundedSuggestionQuantity(12, 6, null, null)).isEqualTo(12);
        assertThat(roundedSuggestionQuantity(12, null, null, 6)).isEqualTo(12);
    }

    @Test
    @DisplayName("MOQ is a floor applied after multiple rounding (need 5, pack 6, MOQ 24 → 24)")
    void moqFloorsAfterRounding() {
        assertThat(roundedSuggestionQuantity(5, null, 24, 6)).isEqualTo(24);
    }

    @Test
    @DisplayName("an MOQ that is not a pack multiple applies verbatim (need 5, pack 6, MOQ 10 → 10)")
    void nonMultipleMoqAppliesVerbatim() {
        assertThat(roundedSuggestionQuantity(5, null, 10, 6)).isEqualTo(10);
    }

    @Test
    @DisplayName("rounded need above the MOQ keeps the rounded value (need 25, pack 6, MOQ 10 → 30)")
    void roundedNeedAboveMoqKeepsRounding() {
        assertThat(roundedSuggestionQuantity(25, null, 10, 6)).isEqualTo(30);
    }

    @Test
    @DisplayName("no constraints passes the need through")
    void noConstraintsPassThrough() {
        assertThat(roundedSuggestionQuantity(7, null, null, null)).isEqualTo(7);
        assertThat(roundedSuggestionQuantity(7, 1, null, 1)).isEqualTo(7);
    }
}
