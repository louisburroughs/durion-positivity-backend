package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * #1794: the ratified glossary defines "running low" as available-to-promise below an active
 * {@code ReplenishmentPolicy.minimumQuantity} (BusinessGlossary 2026-09-05.2, ratified in #1688).
 * The assistant could reach the ATP half and not the minimum half — no tool read
 * {@code replenishment_policy} — so a business definition the owner agreed to was unanswerable,
 * and any answer would have rested on a substitute definition that silently diverged from it.
 */
@DisplayName("InventoryFacadeTool — reading replenishment policies")
class ReplenishmentPolicyToolTest {

    private static final String BASE = "http://pos-api-gateway";
    private static final String POLICIES = "/inventory/v1/inventory/replenishment/policies";

    private MockRestServiceServer server;
    private InventoryFacadeTool tool;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        tool = new InventoryFacadeTool(
                builder,
                BASE,
                "/inventory/v1/inventory/availability/by-sku?productSku={productSku}",
                "/inventory/v1/inventory/availability/by-sku?productSku={productSku}",
                "/inventory/v1/inventory/locations/{locationId}/inventory-inquiry",
                POLICIES);
    }

    @Test
    @DisplayName("lists every policy when no location is given")
    void listsAllPolicies() {
        server.expect(requestTo(BASE + POLICIES))
                .andRespond(withSuccess("{\"content\":[]}", MediaType.APPLICATION_JSON));

        assertThat(tool.listReplenishmentPolicies(null)).contains("content");
        server.verify();
    }

    @Test
    @DisplayName("a location filter reaches the query string")
    void locationFilterIsSent() {
        server.expect(requestTo(BASE + POLICIES + "?locationId=96dd346a-047c-86f5-3c9a-7c8cac53da86"))
                .andRespond(withSuccess("{\"content\":[]}", MediaType.APPLICATION_JSON));

        tool.listReplenishmentPolicies("96dd346a-047c-86f5-3c9a-7c8cac53da86");
        server.verify();
    }
}
