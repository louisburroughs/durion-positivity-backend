package com.positivity.inventory.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.domainevents.inventory.TransferOrderUpdatedV1;
import com.positivity.inventory.internal.config.OutboxEventWriter;
import com.positivity.inventory.internal.entity.LocationRefEntity;
import com.positivity.inventory.internal.entity.TransferOrder;
import com.positivity.inventory.internal.entity.TransferOrderLine;
import com.positivity.inventory.internal.enums.TransferOrderStatus;
import com.positivity.inventory.internal.repository.LocationRefRepository;
import com.positivity.inventory.internal.repository.OutboxEventRepository;
import com.positivity.inventory.internal.repository.TransferOrderRepository;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract behavior ITs for the transfer-order lifecycle (odoo-parity C1, issue #1035).
 *
 * <p>Covers the C1 acceptance shape: create validates distinct ACTIVE sites
 * (DECISION-INVENTORY-009) and non-empty positive lines; approve is a 409 while the
 * approval flag is off (decision D-8, default); cancel works before dispatch and conflicts
 * after; every state change queues a {@link TransferOrderUpdatedV1} fact; endpoints are
 * permission-gated. The approval-flag-on path is covered by
 * {@link TransferOrderApprovalRequiredIT}; dispatch/receive posting is parity-C2.
 */
@DisplayName("Transfer Order Contract Behavior")
class TransferOrderContractBehaviorIT extends BaseContractIntegrationTest {

    @TestConfiguration
    static class OutboxTestConfig {
        @Bean
        OutboxEventWriter outboxEventWriter(
                Clock clock, ObjectMapper objectMapper, OutboxEventRepository outboxEventRepository) {
            return new OutboxEventWriter(clock, objectMapper, outboxEventRepository);
        }
    }

    private static final UUID SOURCE_SITE = UUID.fromString("01970001-0001-7000-8000-000000000001");
    private static final UUID DESTINATION_SITE = UUID.fromString("01970001-0001-7000-8000-000000000002");
    private static final UUID INACTIVE_SITE = UUID.fromString("01970001-0001-7000-8000-000000000003");
    private static final UUID PENDING_SITE = UUID.fromString("01970001-0001-7000-8000-000000000004");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransferOrderRepository transferOrderRepository;

    @Autowired
    private LocationRefRepository locationRefRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        transferOrderRepository.deleteAll();
        locationRefRepository.deleteAll();
        seedSite(SOURCE_SITE, "Source Site", "ACTIVE");
        seedSite(DESTINATION_SITE, "Destination Site", "ACTIVE");
        seedSite(INACTIVE_SITE, "Closed Site", "INACTIVE");
        seedSite(PENDING_SITE, "Opening-Soon Site", "PENDING");
    }

    // ─── Create ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("create returns DRAFT with lines and queues the TransferOrderUpdatedV1 fact")
    void create_returnsDraftAndQueuesFact() throws Exception {
        MvcResult result = mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(SOURCE_SITE, DESTINATION_SITE, "SKU-A", 6)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.sourceLocationId").value(SOURCE_SITE.toString()))
                .andExpect(jsonPath("$.destinationLocationId").value(DESTINATION_SITE.toString()))
                .andExpect(jsonPath("$.createdBy").value("contract-test-user"))
                .andExpect(jsonPath("$.lines[0].sku").value("SKU-A"))
                .andExpect(jsonPath("$.lines[0].requestedQty").value(6))
                .andExpect(jsonPath("$.lines[0].dispatchedQty").value(0))
                .andExpect(jsonPath("$.lines[0].receivedQty").value(0))
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        String transferOrderId = response.get("transferOrderId").asString();

        assertThat(outboxEventRepository.findAll())
                .anyMatch(row -> row.getPayload().contains(TransferOrderUpdatedV1.EVENT_TYPE)
                        && row.getPayload().contains(transferOrderId)
                        && row.getPayload().contains("DRAFT"));
    }

    @Test
    @DisplayName("create with identical source and destination site returns 400")
    void create_sameSite_returns400() throws Exception {
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(SOURCE_SITE, SOURCE_SITE, "SKU-A", 6)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("create with an INACTIVE source site returns the deterministic 422")
    void create_inactiveSource_returns422() throws Exception {
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(INACTIVE_SITE, DESTINATION_SITE, "SKU-A", 6)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSFER_LOCATION_NOT_ELIGIBLE"));
    }

    @Test
    @DisplayName("create with a PENDING destination site returns the deterministic 422")
    void create_pendingDestination_returns422() throws Exception {
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(SOURCE_SITE, PENDING_SITE, "SKU-A", 6)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSFER_LOCATION_NOT_ELIGIBLE"));

        assertThat(transferOrderRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("create with a site missing from the roster returns 404")
    void create_unknownSite_returns404() throws Exception {
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID.randomUUID(), DESTINATION_SITE, "SKU-A", 6)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("create with empty lines returns 400")
    void create_emptyLines_returns400() throws Exception {
        String body = "{\"sourceLocationId\":\"" + SOURCE_SITE + "\",\"destinationLocationId\":\"" + DESTINATION_SITE
                + "\",\"lines\":[]}";
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("create with non-positive line quantity returns 400")
    void create_nonPositiveQuantity_returns400() throws Exception {
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(SOURCE_SITE, DESTINATION_SITE, "SKU-A", 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ─── Get / list ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("get returns the order; list filters by status, source, and destination")
    void getAndList_filter() throws Exception {
        String transferOrderId = createOrder("SKU-B", 4);

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/transfer-orders/{id}", transferOrderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferOrderId").value(transferOrderId))
                .andExpect(jsonPath("$.lines[0].sku").value("SKU-B"));

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/transfer-orders")
                        .param("status", "DRAFT")
                        .param("sourceLocationId", SOURCE_SITE.toString())
                        .param("destinationLocationId", DESTINATION_SITE.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].transferOrderId").value(transferOrderId));

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/transfer-orders").param("status", "CANCELLED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/transfer-orders/{id}", UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    // ─── Approve (flag off — default) ─────────────────────────────────────────

    @Test
    @DisplayName("approve returns 409 while the approval flag is off (decision D-8 default)")
    void approve_flagOff_returns409() throws Exception {
        String transferOrderId = createOrder("SKU-C", 2);

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/approve", transferOrderId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/transfer-orders/{id}", transferOrderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    // ─── Cancel ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("cancel before dispatch transitions to CANCELLED and queues the fact")
    void cancel_beforeDispatch_cancels() throws Exception {
        String transferOrderId = createOrder("SKU-D", 3);
        outboxEventRepository.deleteAll();

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/cancel", transferOrderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(outboxEventRepository.findAll())
                .anyMatch(row -> row.getPayload().contains(TransferOrderUpdatedV1.EVENT_TYPE)
                        && row.getPayload().contains("CANCELLED"));
    }

    @Test
    @DisplayName("cancel after dispatch returns 409 (spec C1: post-dispatch resolves via short-close)")
    void cancel_afterDispatch_returns409() throws Exception {
        TransferOrder dispatched = TransferOrder.builder()
                .sourceLocationId(SOURCE_SITE)
                .destinationLocationId(DESTINATION_SITE)
                .status(TransferOrderStatus.DISPATCHED)
                .createdBy("contract-test-user")
                .dispatchedBy("contract-test-user")
                .build();
        dispatched.addLine(TransferOrderLine.builder()
                .lineNumber(1)
                .sku("SKU-E")
                .requestedQty(5)
                .dispatchedQty(5)
                .build());
        dispatched = transferOrderRepository.save(dispatched);

        mockMvc.perform(withGatewayAuth(
                        post("/v1/inventory/transfer-orders/{id}/cancel", dispatched.getTransferOrderId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(transferOrderRepository
                        .findById(dispatched.getTransferOrderId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(TransferOrderStatus.DISPATCHED);
    }

    // ─── Permission gating ────────────────────────────────────────────────────

    @Test
    @DisplayName("create without inventory:transfer:create is rejected with 403")
    void createWithoutPermission_returns403() throws Exception {
        mockMvc.perform(withTransferViewOnlyAuth(post("/v1/inventory/transfer-orders"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(SOURCE_SITE, DESTINATION_SITE, "SKU-A", 6)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("list without inventory:transfer:view is rejected with 403")
    void listWithoutPermission_returns403() throws Exception {
        mockMvc.perform(withCreateOnlyAuth(get("/v1/inventory/transfer-orders")))
                .andExpect(status().isForbidden());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private MockHttpServletRequestBuilder withTransferViewOnlyAuth(MockHttpServletRequestBuilder requestBuilder) {
        return requestBuilder.header("X-User", "contract-test-user").header("X-Authorities", "inventory:transfer:view");
    }

    private String createOrder(String sku, int quantity) throws Exception {
        MvcResult result = mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(SOURCE_SITE, DESTINATION_SITE, sku, quantity)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("transferOrderId")
                .asString();
    }

    private String createBody(UUID source, UUID destination, String sku, int quantity) {
        return "{\"sourceLocationId\":\"" + source + "\",\"destinationLocationId\":\"" + destination
                + "\",\"lines\":[{\"sku\":\"" + sku + "\",\"requestedQty\":" + quantity + "}]}";
    }

    private void seedSite(UUID siteId, String name, String status) {
        locationRefRepository.save(LocationRefEntity.builder()
                .locationId(siteId)
                .name(name)
                .status(status)
                .active("ACTIVE".equals(status))
                .build());
    }
}
