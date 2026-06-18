package com.positivity.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.LocationInventoryItemsResponse;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository.LocationOnHand;
import com.positivity.inventory.internal.service.LocationInventoryInquiryServiceImpl;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link LocationInventoryInquiryServiceImpl#listLocationInventoryItems}.
 */
@ExtendWith(MockitoExtension.class)
class LocationInventoryInquiryServiceImplTest {

    private static final UUID LOCATION_ID = UUID.fromString("01960004-0001-7000-8000-000000000047");

    @Mock
    private InventoryLedgerEntryRepository ledgerRepository;

    private LocationOnHand onHand(String sku, Long qty) {
        return new LocationOnHand() {
            @Override
            public String getStockItemId() {
                return sku;
            }

            @Override
            public Long getOnHandQuantity() {
                return qty;
            }
        };
    }

    @Test
    void listLocationInventoryItems_mapsPositiveOnHandRowsToItems() {
        var service = new LocationInventoryInquiryServiceImpl(ledgerRepository);
        when(ledgerRepository.findPositiveOnHandByLocation(eq(LOCATION_ID), any()))
                .thenReturn(List.of(onHand("MICH-XC2-0001", 24L), onHand("MICH-DEF-0002", 8L)));

        LocationInventoryItemsResponse response = service.listLocationInventoryItems(LOCATION_ID);

        assertThat(response.getLocationId()).isEqualTo(LOCATION_ID);
        assertThat(response.getItems())
                .extracting(LocationInventoryItemsResponse.Item::getStockItemId)
                .containsExactly("MICH-XC2-0001", "MICH-DEF-0002");
        assertThat(response.getItems())
                .extracting(LocationInventoryItemsResponse.Item::getOnHandQuantity)
                .containsExactly(24L, 8L);
    }

    @Test
    void listLocationInventoryItems_returnsEmptyListForLocationWithoutStock() {
        var service = new LocationInventoryInquiryServiceImpl(ledgerRepository);
        when(ledgerRepository.findPositiveOnHandByLocation(eq(LOCATION_ID), any())).thenReturn(List.of());

        LocationInventoryItemsResponse response = service.listLocationInventoryItems(LOCATION_ID);

        assertThat(response.getLocationId()).isEqualTo(LOCATION_ID);
        assertThat(response.getItems()).isEmpty();
    }

    @Test
    void listLocationInventoryItems_treatsNullQuantityAsZero() {
        var service = new LocationInventoryInquiryServiceImpl(ledgerRepository);
        when(ledgerRepository.findPositiveOnHandByLocation(eq(LOCATION_ID), any()))
                .thenReturn(List.of(onHand("WIXF-XP57356", null)));

        LocationInventoryItemsResponse response = service.listLocationInventoryItems(LOCATION_ID);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getOnHandQuantity()).isZero();
    }
}
