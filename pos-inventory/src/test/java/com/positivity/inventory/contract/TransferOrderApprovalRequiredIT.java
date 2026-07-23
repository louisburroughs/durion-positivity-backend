package com.positivity.inventory.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.domainevents.inventory.TransferOrderUpdatedV1;
import com.positivity.inventory.internal.config.OutboxEventWriter;
import com.positivity.inventory.internal.entity.LocationRefEntity;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Transfer-order approval step with the config flag ON (odoo-parity C1, issue #1035; decision
 * D-8: {@code pos.inventory.transfer.approval-required=true} inserts DRAFT → APPROVED before
 * dispatch).
 */
@DisplayName("Transfer Order Approval Required Behavior")
@TestPropertySource(properties = "pos.inventory.transfer.approval-required=true")
class TransferOrderApprovalRequiredIT extends BaseContractIntegrationTest {

    @TestConfiguration
    static class OutboxTestConfig {
        @Bean
        OutboxEventWriter outboxEventWriter(
                Clock clock, ObjectMapper objectMapper, OutboxEventRepository outboxEventRepository) {
            return new OutboxEventWriter(clock, objectMapper, outboxEventRepository);
        }
    }

    private static final UUID SOURCE_SITE = UUID.fromString("01970002-0001-7000-8000-000000000001");
    private static final UUID DESTINATION_SITE = UUID.fromString("01970002-0001-7000-8000-000000000002");

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
        seedSite(SOURCE_SITE, "Approval Source Site");
        seedSite(DESTINATION_SITE, "Approval Destination Site");
    }

    @Test
    @DisplayName("with the flag on, approve transitions DRAFT to APPROVED and queues the fact")
    void approve_flagOn_approvesDraft() throws Exception {
        String transferOrderId = createOrder();
        outboxEventRepository.deleteAll();

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/approve", transferOrderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value("contract-test-user"));

        assertThat(outboxEventRepository.findAll())
                .anyMatch(row -> row.getPayload().contains(TransferOrderUpdatedV1.EVENT_TYPE)
                        && row.getPayload().contains("APPROVED"));
    }

    @Test
    @DisplayName("approve is idempotent-hostile: a second approve on APPROVED returns 409")
    void approve_nonDraft_returns409() throws Exception {
        String transferOrderId = createOrder();

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/approve", transferOrderId)))
                .andExpect(status().isOk());
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/approve", transferOrderId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("cancel still works on an APPROVED order (before dispatch)")
    void cancel_approvedOrder_cancels() throws Exception {
        String transferOrderId = createOrder();
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/approve", transferOrderId)))
                .andExpect(status().isOk());

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/cancel", transferOrderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    private String createOrder() throws Exception {
        String body = "{\"sourceLocationId\":\"" + SOURCE_SITE + "\",\"destinationLocationId\":\"" + DESTINATION_SITE
                + "\",\"lines\":[{\"sku\":\"SKU-APPROVAL\",\"requestedQty\":3}]}";
        MvcResult result = mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("transferOrderId")
                .asString();
    }

    private void seedSite(UUID siteId, String name) {
        locationRefRepository.save(LocationRefEntity.builder()
                .locationId(siteId)
                .name(name)
                .status("ACTIVE")
                .active(true)
                .build());
    }
}
