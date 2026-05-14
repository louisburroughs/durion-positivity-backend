package com.positivity.catalog.internal.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.BaseContractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class CatalogOpenApiContractTest extends BaseContractIntegrationTest {

    @Test
    @DisplayName("openApiSpec_catalogCostEndpoints_haveDescriptions")
    @SuppressWarnings("unchecked")
    void openApiSpec_catalogCostEndpoints_haveDescriptions() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        Map<String, Object> openApiSpec = objectMapper.readValue(result.getResponse().getContentAsByteArray(), Map.class);
        Map<String, Object> paths = (Map<String, Object>) openApiSpec.get("paths");

        assertOperationDescription(paths, "/v1/products/supplier-costs", "post");
        assertOperationDescription(paths, "/v1/products/supplier-costs/{id}", "get");
        assertOperationDescription(paths, "/v1/products/supplier-costs/{id}", "put");
        assertOperationDescription(paths, "/v1/products/supplier-costs/{id}", "delete");
        assertOperationDescription(paths, "/v1/products/items/{itemId}/standard-cost", "put");
        assertOperationDescription(paths, "/v1/products/items/{itemId}/costs", "get");
        assertOperationDescription(paths, "/v1/products/items/{itemId}/costs/audit", "get");
    }

    @SuppressWarnings("unchecked")
    private static void assertOperationDescription(Map<String, Object> paths, String path, String method) {
        Map<String, Object> pathItem = (Map<String, Object>) paths.get(path);
        org.assertj.core.api.Assertions.assertThat(pathItem).as("path %s should exist", path).isNotNull();

        Map<String, Object> operation = (Map<String, Object>) pathItem.get(method);
        org.assertj.core.api.Assertions.assertThat(operation)
                .as("%s %s operation should exist", method.toUpperCase(), path)
                .isNotNull();
        org.assertj.core.api.Assertions.assertThat(operation.get("description"))
                .as("%s %s should have a non-empty description", method.toUpperCase(), path)
                .isInstanceOf(String.class)
                .asString()
                .isNotBlank();
    }
}
