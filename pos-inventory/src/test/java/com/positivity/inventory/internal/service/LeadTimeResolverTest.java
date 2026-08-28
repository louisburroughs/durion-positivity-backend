package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.LeadTimeView;
import com.positivity.inventory.internal.entity.ReplenishmentPolicy;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Lead-time resolution for the forecast-aware orderpoint trigger (odoo-parity F2, issue
 * #1040; decision D-4 precedence: policy override [F3] → vendor feed → default).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LeadTimeResolver (F2, D-4 precedence)")
class LeadTimeResolverTest {

    private static final UUID PRODUCT_ID = UUID.fromString("01960003-0000-7000-8000-000000000001");
    private static final UUID LOCATION_ID = UUID.fromString("01960003-0000-7000-8000-000000000002");

    @Mock
    private InventoryLeadTimeService inventoryLeadTimeService;

    private ReplenishmentPolicy policy(String sku) {
        return ReplenishmentPolicy.builder()
                .locationId(LOCATION_ID)
                .itemSKU(sku)
                .minimumQuantity(5)
                .maximumQuantity(20)
                .build();
    }

    @Test
    @DisplayName("feed lead time wins over the default and uses the MAX day estimate (conservative)")
    void feedLeadTime_usesMaxDays() {
        LeadTimeResolver resolver = new LeadTimeResolver(inventoryLeadTimeService, 3);
        when(inventoryLeadTimeService.findLeadTime(PRODUCT_ID, LOCATION_ID, null))
                .thenReturn(Optional.of(LeadTimeView.builder()
                        .productId(PRODUCT_ID)
                        .locationId(LOCATION_ID)
                        .minDays(2)
                        .maxDays(5)
                        .build()));

        LeadTimeResolver.ResolvedLeadTime resolved = resolver.resolve(policy(PRODUCT_ID.toString()));

        assertThat(resolved.days()).isEqualTo(5);
        assertThat(resolved.source()).isEqualTo(LeadTimeResolver.LeadTimeSource.FEED);
    }

    @Test
    @DisplayName("no feed rows falls back to the configured default")
    void noFeedRows_fallsBackToDefault() {
        LeadTimeResolver resolver = new LeadTimeResolver(inventoryLeadTimeService, 3);
        when(inventoryLeadTimeService.findLeadTime(PRODUCT_ID, LOCATION_ID, null))
                .thenReturn(Optional.empty());

        LeadTimeResolver.ResolvedLeadTime resolved = resolver.resolve(policy(PRODUCT_ID.toString()));

        assertThat(resolved.days()).isEqualTo(3);
        assertThat(resolved.source()).isEqualTo(LeadTimeResolver.LeadTimeSource.DEFAULT);
    }

    @Test
    @DisplayName("non-UUID SKU skips the feed lookup entirely (feeds key on product UUID)")
    void nonUuidSku_skipsFeedLookup() {
        LeadTimeResolver resolver = new LeadTimeResolver(inventoryLeadTimeService, 2);

        LeadTimeResolver.ResolvedLeadTime resolved = resolver.resolve(policy("SKU-BOLT-M5"));

        assertThat(resolved.days()).isEqualTo(2);
        assertThat(resolved.source()).isEqualTo(LeadTimeResolver.LeadTimeSource.DEFAULT);
        verify(inventoryLeadTimeService, never()).findLeadTime(any(), any(), any());
    }

    @Test
    @DisplayName("feed view with only minDays still resolves from the feed")
    void feedWithOnlyMinDays_resolvesMinDays() {
        LeadTimeResolver resolver = new LeadTimeResolver(inventoryLeadTimeService, 0);
        when(inventoryLeadTimeService.findLeadTime(PRODUCT_ID, LOCATION_ID, null))
                .thenReturn(Optional.of(LeadTimeView.builder()
                        .productId(PRODUCT_ID)
                        .locationId(LOCATION_ID)
                        .minDays(4)
                        .build()));

        LeadTimeResolver.ResolvedLeadTime resolved = resolver.resolve(policy(PRODUCT_ID.toString()));

        assertThat(resolved.days()).isEqualTo(4);
        assertThat(resolved.source()).isEqualTo(LeadTimeResolver.LeadTimeSource.FEED);
    }

    @Test
    @DisplayName("negative configured default clamps to zero")
    void negativeDefault_clampsToZero() {
        LeadTimeResolver resolver = new LeadTimeResolver(inventoryLeadTimeService, -7);

        LeadTimeResolver.ResolvedLeadTime resolved = resolver.resolve(policy("SKU-NOT-UUID"));

        assertThat(resolved.days()).isZero();
        assertThat(resolved.source()).isEqualTo(LeadTimeResolver.LeadTimeSource.DEFAULT);
    }
}
