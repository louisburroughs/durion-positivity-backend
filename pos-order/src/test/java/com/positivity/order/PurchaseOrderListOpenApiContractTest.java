package com.positivity.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Guards the query-parameter contract of the paged purchase-order listing (issue #1465).
 *
 * <p>Spring binds flat query parameters (vendorId=, page=, size=), so the OpenAPI spec must
 * declare them flat as well. Without {@code @ParameterObject} springdoc renders the
 * {@code @ModelAttribute} filter and {@code Pageable} as object-typed parameters, and generated
 * clients then send {@code filter[vendorId]=..} and {@code pageable[size]=..} — which Spring
 * silently ignores, serving default paging with no filter applied.
 */
class PurchaseOrderListOpenApiContractTest extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("listPurchaseOrders declares flat filter and paging query parameters, not object-typed ones")
    @SuppressWarnings("unchecked")
    void listPurchaseOrders_declaresFlatQueryParameters() throws Exception {
        MvcResult result = mockMvc.perform(withGatewayAuth(get("/v3/api-docs")))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> openApiSpec =
                objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        Map<String, Object> paths = (Map<String, Object>) openApiSpec.get("paths");
        Map<String, Object> pathItem = (Map<String, Object>) paths.get("/v1/orders/purchase-orders");
        assertThat(pathItem).as("path /v1/orders/purchase-orders should exist").isNotNull();

        Map<String, Object> operation = (Map<String, Object>) pathItem.get("get");
        assertThat(operation)
                .as("GET /v1/orders/purchase-orders operation should exist")
                .isNotNull();

        List<Map<String, Object>> parameters = (List<Map<String, Object>>) operation.get("parameters");
        assertThat(parameters).as("parameters should be declared").isNotNull();
        List<String> parameterNames =
                parameters.stream().map(p -> (String) p.get("name")).toList();

        assertThat(parameterNames)
                .as("filter fields and paging must be flat query parameters matching what Spring binds")
                .contains("vendorId", "status", "currency", "locationId", "page", "size", "sort");
        assertThat(parameterNames)
                .as("object-typed filter/pageable parameters generate clients whose requests Spring "
                        + "silently ignores")
                .doesNotContain("filter", "pageable");
    }
}
