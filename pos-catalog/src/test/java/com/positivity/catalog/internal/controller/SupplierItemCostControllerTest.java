package com.positivity.catalog.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.internal.exception.CatalogValidationException;

import com.positivity.catalog.config.TestSecurityConfig;
import com.positivity.catalog.internal.dto.SupplierItemCostDto;
import com.positivity.catalog.service.SupplierItemCostService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SupplierItemCostController.class)
@Import({ TestSecurityConfig.class, CatalogExceptionHandler.class })
@ActiveProfiles("test")
@SuppressWarnings({ "java:S6813", "java:S100", "java:S1192" })
class SupplierItemCostControllerTest {

  private static final UUID ITEM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID SUPPLIER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID COST_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

  @Autowired
  MockMvc mockMvc;

  @MockitoBean
  java.time.Clock clock;

  @MockitoBean
  org.springframework.cache.CacheManager cacheManager;

  @MockitoBean
  SupplierItemCostService supplierItemCostService;

  @BeforeEach
  void setUpClock() {
    lenient().when(clock.instant()).thenReturn(Instant.EPOCH);
  }

  // ─── GET /v1/products/supplier-costs?itemId={uuid} — 200 OK ─────────────

  @Test
  void listCostStructures_withItemId_returns200() throws Exception {
    SupplierItemCostDto dto = new SupplierItemCostDto();
    dto.setId(COST_ID);
    dto.setItemId(ITEM_ID);
    dto.setSupplierId(SUPPLIER_ID);

    when(supplierItemCostService.listCostStructures(eq(ITEM_ID), isNull(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(dto)));

    mockMvc.perform(get("/v1/products/supplier-costs")
        .header("X-Authorities", "catalog:supplier_cost:read")
        .param("itemId", ITEM_ID.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(COST_ID.toString()));
  }

  // ─── GET /v1/products/supplier-costs?supplierId={uuid} — 200 OK ─────────

  @Test
  void listCostStructures_withSupplierId_returns200() throws Exception {
    SupplierItemCostDto dto = new SupplierItemCostDto();
    dto.setId(COST_ID);
    dto.setItemId(ITEM_ID);
    dto.setSupplierId(SUPPLIER_ID);

    when(supplierItemCostService.listCostStructures(isNull(), eq(SUPPLIER_ID), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(dto)));

    mockMvc.perform(get("/v1/products/supplier-costs")
        .header("X-Authorities", "catalog:supplier_cost:read")
        .param("supplierId", SUPPLIER_ID.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(COST_ID.toString()));
  }

  // ─── GET /v1/products/supplier-costs — 400 Bad Request (no filters) ──────

  @Test
  void listCostStructures_withNoFilters_returns400() throws Exception {
    doThrow(new CatalogValidationException("MISSING_FILTER: At least one of itemId or supplierId must be provided"))
        .when(supplierItemCostService).listCostStructures(isNull(), isNull(), any(Pageable.class));

    mockMvc.perform(get("/v1/products/supplier-costs")
        .header("X-Authorities", "catalog:supplier_cost:read"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("MISSING_FILTER")));
  }
}
