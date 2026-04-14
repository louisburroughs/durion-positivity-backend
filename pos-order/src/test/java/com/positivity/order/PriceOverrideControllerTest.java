package com.positivity.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.order.internal.exception.PriceOverrideIdempotencyConflictException;
import com.positivity.order.internal.security.PriceOverridePermissions;
import com.positivity.order.service.PriceOverrideService;
import com.positivity.order.service.model.PriceOverrideDetail;
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

        mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/price-overrides")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        PriceOverridePermissions.PRICE_OVERRIDE_APPLY))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("PO-002: POST /v1/orders/price-overrides unauthorized returns 403 Forbidden")
    void applyPriceOverride_whenServiceDeniesAccess_thenReturns403Forbidden() throws Exception {
        when(priceOverrideService.applyPriceOverride(any())).thenThrow(new AccessDeniedException("Forbidden"));

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

        mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/price-overrides")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        PriceOverridePermissions.PRICE_OVERRIDE_APPLY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORDER_FORBIDDEN"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("PO-005: POST /v1/orders/price-overrides idempotency conflict returns 409 Conflict")
    void applyPriceOverride_whenIdempotencyConflict_thenReturns409Conflict() throws Exception {
        when(priceOverrideService.applyPriceOverride(any()))
                .thenThrow(new PriceOverrideIdempotencyConflictException("conflict"));

        String body = """
                {
                  "orderId": "00000000-0000-0000-0000-000000000001",
                  "orderLineId": "00000000-0000-0000-0000-000000000009",
                  "productId": "00000000-0000-0000-0000-000000000011",
                  "originalPrice": 100.00,
                  "overridePrice": 90.00,
                  "reasonCode": "PRICE_MATCH",
                  "idempotencyKey": "override-req-001"
                }
                """;

        mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/price-overrides")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        PriceOverridePermissions.PRICE_OVERRIDE_APPLY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_PRICE_OVERRIDE_IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("PO-003: Approve derives reviewer role from ROLE_* authority")
    void approvePriceOverride_whenRoleAuthorityPresent_thenUsesDerivedRole() throws Exception {
        UUID overrideId = UUID.randomUUID();
        when(priceOverrideService.approveOverride(any(), any())).thenReturn(sampleDetail("APPROVED"));

        mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/price-overrides/{overrideId}/approve", overrideId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"comments\":\"ok\"}"),
                        PriceOverridePermissions.PRICE_OVERRIDE_APPROVE + ",ROLE_MANAGER"))
                .andExpect(status().isOk());

        verify(priceOverrideService)
                .approveOverride(
                        any(),
                        argThat(command ->
                                "MANAGER".equals(command.approverRole()) && "ok".equals(command.comments())));
    }

    @Test
    @DisplayName("PO-004: Reject falls back to UNKNOWN when no ROLE_* authority exists")
    void rejectPriceOverride_whenNoRoleAuthority_thenUsesUnknownRole() throws Exception {
        UUID overrideId = UUID.randomUUID();
        when(priceOverrideService.rejectOverride(any(), any())).thenReturn(sampleDetail("REJECTED"));

        mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/price-overrides/{overrideId}/reject", overrideId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"policy\",\"comments\":\"no role\"}"),
                        PriceOverridePermissions.PRICE_OVERRIDE_REJECT))
                .andExpect(status().isOk());

        verify(priceOverrideService)
                .rejectOverride(
                        any(),
                        argThat(command -> "UNKNOWN".equals(command.reviewerRole())
                                && "policy".equals(command.reason())
                                && "no role".equals(command.comments())));
    }

    private PriceOverrideDetail sampleDetail(String status) {
        UUID id = UUID.randomUUID();
        String idString = id.toString();
        return new PriceOverrideDetail(
                id,
                idString,
                idString,
                idString,
                BigDecimal.TEN,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                "PRICE_MATCH",
                "test",
                status,
                false,
                false,
                idString,
                idString,
                null,
                null,
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null);
    }
}
