package com.positivity.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.order.internal.security.PriceOverridePermissions;
import com.positivity.order.service.PriceOverrideService;
import com.positivity.order.service.model.PriceOverrideResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("PriceOverride Controller HTTP Contract Tests")
class PriceOverrideControllerTest extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PriceOverrideService priceOverrideService;

    @Test
    @DisplayName("PO-001: POST /v1/orders/price-overrides authorized returns 201 Created")
    void applyPriceOverride_whenAuthorized_thenReturns201Created() throws Exception {
        PriceOverrideResult result = new PriceOverrideResult(
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                new BigDecimal("100.00"),
                new BigDecimal("90.00"),
                new BigDecimal("10.00"),
                new BigDecimal("10.00"),
                "PRICE_MATCH",
                null,
                "APPROVED",
                false,
                true,
                "user-001",
                Instant.now(),
                "Approved and applied immediately");

        when(priceOverrideService.applyPriceOverride(any())).thenReturn(result);

        String body = """
                {
                  "orderId": "00000000-0000-0000-0000-000000000001",
                  "orderLineId": "00000000-0000-0000-0000-000000000009",
                  "productId": "00000000-0000-0000-0000-000000000011",
                  "originalPrice": 100.00,
                  "overridePrice": 90.00,
                  "reasonCode": "PRICE_MATCH"
                }
                """;

        mockMvc.perform(withGatewayAuth(post("/v1/orders/price-overrides")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body), PriceOverridePermissions.PRICE_OVERRIDE_APPLY))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("PO-002: POST /v1/orders/price-overrides unauthorized returns 403 Forbidden")
    void applyPriceOverride_whenServiceDeniesAccess_thenReturns403Forbidden() throws Exception {
        when(priceOverrideService.applyPriceOverride(any()))
                .thenThrow(new AccessDeniedException("Forbidden"));

        String body = """
                {
                  "orderId": "00000000-0000-0000-0000-000000000001",
                  "orderLineId": "00000000-0000-0000-0000-000000000009",
                  "productId": "00000000-0000-0000-0000-000000000011",
                  "originalPrice": 100.00,
                  "overridePrice": 90.00,
                  "reasonCode": "PRICE_MATCH"
                }
                """;

        mockMvc.perform(withGatewayAuth(post("/v1/orders/price-overrides")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body), PriceOverridePermissions.PRICE_OVERRIDE_APPLY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORDER_FORBIDDEN"))
                .andExpect(jsonPath("$.status").value(403));
    }
}
