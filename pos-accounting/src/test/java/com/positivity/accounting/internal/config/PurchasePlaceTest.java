package com.positivity.accounting.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CAP:550 S43 (#2604 ruling 5, §C10): the purchase place sent with the use-tax quote. {@code postal-code} is required,
 * at most 20 characters; {@code region-code} is optional, 1-3 letters or digits; each refusal names its property.
 */
@DisplayName("PurchasePlace: accounting.tax.purchase-place, checked at startup (S43)")
class PurchasePlaceTest {

    @Test
    @DisplayName("a region and a postal code start, trimmed; no region is null; 20 characters of postal code start")
    void accepted() {
        PurchasePlace place = new PurchasePlace(" ZA ", " 00000 ");
        assertThat(place.regionCode()).isEqualTo("ZA");
        assertThat(place.postalCode()).isEqualTo("00000");

        assertThat(new PurchasePlace(null, "00000").regionCode()).isNull();
        assertThat(new PurchasePlace("  ", "00000").regionCode()).isNull();
        assertThat(new PurchasePlace("1", "x".repeat(20)).postalCode()).hasSize(20);
        assertThat(new PurchasePlace("A9Z", "V6B 1A1").regionCode()).isEqualTo("A9Z");
    }

    @Test
    @DisplayName("a missing, blank or over-20-character postal code fails startup naming"
            + " accounting.tax.purchase-place.postal-code")
    void postalCodeRefused() {
        for (String postal : new String[] {null, "", "   ", "x".repeat(21)}) {
            assertThatThrownBy(() -> new PurchasePlace("ZA", postal))
                    .as("postal-code=%s", postal)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("accounting.tax.purchase-place.postal-code");
        }
    }

    @Test
    @DisplayName("a region code that is not 1-3 letters or digits fails startup naming"
            + " accounting.tax.purchase-place.region-code")
    void regionCodeRefused() {
        for (String region : new String[] {"ABCD", "Z-A", "Z A", "É"}) {
            assertThatThrownBy(() -> new PurchasePlace(region, "00000"))
                    .as("region-code=%s", region)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("accounting.tax.purchase-place.region-code");
        }
    }
}
