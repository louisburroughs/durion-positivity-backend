package com.positivity.inventory.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.service.InventoryReferenceDataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@DisplayName("Inventory Reference Data Contract Behavior")
class InventoryReferenceDataContractBehaviorIT extends BaseContractIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private InventoryReferenceDataService inventoryReferenceDataService;

  @Test
  @DisplayName("GET /v1/inventory/locations with auth -> 200")
  void listLocations_withAuth_returns200() throws Exception {
    when(inventoryReferenceDataService.listLocations(isNull(), any(Pageable.class)))
        .thenReturn(Page.empty());

    mockMvc.perform(withGatewayAuth(get("/v1/inventory/locations")))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("GET /v1/inventory/locations without auth -> 401 or 403")
  void listLocations_withoutAuth_returnsUnauthorizedOrForbidden() throws Exception {
    mockMvc.perform(get("/v1/inventory/locations"))
        .andExpect(status().is4xxClientError());
  }

  @Test
  @DisplayName("GET /v1/inventory/storage-locations with auth -> 200")
  void listStorageLocations_withAuth_returns200() throws Exception {
    when(inventoryReferenceDataService.listStorageLocations(isNull(), any(Pageable.class)))
        .thenReturn(Page.empty());

    mockMvc.perform(withGatewayAuth(get("/v1/inventory/storage-locations")))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("GET /v1/inventory/location-zones with auth -> 200")
  void listLocationZones_withAuth_returns200() throws Exception {
    when(inventoryReferenceDataService.listLocationZones(isNull(), any(Pageable.class)))
        .thenReturn(Page.empty());

    mockMvc.perform(withGatewayAuth(get("/v1/inventory/location-zones")))
        .andExpect(status().isOk());
  }
}
