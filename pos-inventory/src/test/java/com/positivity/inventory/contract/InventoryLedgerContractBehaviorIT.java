package com.positivity.inventory.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.internal.dto.InventoryLedgerEntryDto;
import com.positivity.inventory.internal.dto.LedgerPage;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.service.InventoryLedgerService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@DisplayName("Inventory Ledger Contract Behavior")
class InventoryLedgerContractBehaviorIT extends BaseContractIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private InventoryLedgerService inventoryLedgerService;

  @Test
  @DisplayName("GET /v1/inventory/ledger with auth -> 200")
  void listLedger_withAuth_returns200() throws Exception {
    InventoryLedgerEntryDto entry = InventoryLedgerEntryDto.builder()
        .ledgerEntryId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        .stockItemId("SKU-1")
        .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
        .changeInQuantity(5)
        .quantityAfter(5)
        .timestamp(Instant.parse("2024-01-01T00:00:00Z"))
        .build();

    when(inventoryLedgerService.listLedgerEntries(any()))
        .thenReturn(LedgerPage.<InventoryLedgerEntryDto>builder()
            .entries(List.of(entry))
            .nextPageToken(null)
            .build());

    mockMvc.perform(withGatewayAuth(get("/v1/inventory/ledger")))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("GET /v1/inventory/ledger without auth -> 401 or 403")
  void listLedger_withoutAuth_returnsUnauthorizedOrForbidden() throws Exception {
    mockMvc.perform(get("/v1/inventory/ledger"))
        .andExpect(status().is4xxClientError());
  }

  @Test
  @DisplayName("GET /v1/inventory/ledger/{id} with known UUID -> 200")
  void getLedgerEntry_knownId_returns200() throws Exception {
    UUID entryId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    when(inventoryLedgerService.getLedgerEntry(entryId))
        .thenReturn(InventoryLedgerEntryDto.builder()
            .ledgerEntryId(entryId)
            .stockItemId("SKU-1")
            .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
            .changeInQuantity(2)
            .quantityAfter(2)
            .timestamp(Instant.parse("2024-01-01T00:00:00Z"))
            .build());

    mockMvc.perform(withGatewayAuth(get("/v1/inventory/ledger/{entryId}", entryId)))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("GET /v1/inventory/ledger/{id} unknown UUID -> 404")
  void getLedgerEntry_unknownId_returns404() throws Exception {
    UUID entryId = UUID.fromString("00000000-0000-0000-0000-000000000099");
    when(inventoryLedgerService.getLedgerEntry(entryId))
        .thenThrow(new ResourceNotFoundException("InventoryLedgerEntry", entryId.toString()));

    mockMvc.perform(withGatewayAuth(get("/v1/inventory/ledger/{entryId}", entryId)))
        .andExpect(status().isNotFound());
  }
}
