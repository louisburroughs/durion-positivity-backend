package com.positivity.inventory.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.internal.config.OutboxEventWriter;
import com.positivity.inventory.internal.dto.asn.GoodsReceiptResponse;
import com.positivity.inventory.internal.dto.receiving.CreateReceivingSessionRequest;
import com.positivity.inventory.internal.dto.receiving.ReceiveItemsRequest;
import com.positivity.inventory.internal.dto.receiving.ReceiveItemsResponse;
import com.positivity.inventory.internal.dto.receiving.ReceiveLineRequest;
import com.positivity.inventory.internal.dto.receiving.ReceivingSessionResponse;
import com.positivity.inventory.internal.entity.ExtProductReplica;
import com.positivity.inventory.internal.entity.GoodsReceiptEntity;
import com.positivity.inventory.internal.exception.OverReceiptNotPermittedException;
import com.positivity.inventory.internal.receiving.service.AsnService;
import com.positivity.inventory.internal.receiving.service.ReceivingService;
import com.positivity.inventory.internal.repository.ExtProductReplicaRepository;
import com.positivity.inventory.internal.repository.GoodsReceiptRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.OutboxEventRepository;
import com.positivity.inventory.internal.service.PurchaseOrderProjectionTestSupport;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.tenancy.TenantResolver;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * #2455: receiving sessions guard over-receipt, are idempotent under a key, and record a goods
 * receipt per call. Runs the real service and persistence stack against H2.
 */
@DisplayName("Receiving session receipts (#2455)")
class ReceivingSessionReceiptIT extends BaseContractIntegrationTest {

    /** The outbox writer is Kafka-rails only; the IT context needs one to see the published facts. */
    @TestConfiguration
    static class OutboxTestConfig {
        @Bean
        OutboxEventWriter outboxEventWriter(
                Clock clock,
                ObjectMapper objectMapper,
                OutboxEventRepository outboxEventRepository,
                TenantResolver tenantResolver) {
            return new OutboxEventWriter(clock, objectMapper, outboxEventRepository, tenantResolver);
        }
    }

    private static final String ACTOR = "receipt-2455-it";
    private static final String OVERRIDE = "inventory:goods_receipt:override";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReceivingService receivingService;

    @Autowired
    private AsnService asnService;

    @Autowired
    private PurchaseOrderProjectionTestSupport projection;

    @Autowired
    private ExtProductReplicaRepository extProductReplicaRepository;

    @Autowired
    private GoodsReceiptRepository goodsReceiptRepository;

    @Autowired
    private InventoryLedgerEntryRepository ledgerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("CREATE SEQUENCE IF NOT EXISTS purchase_order_number_seq START WITH 1 INCREMENT BY 1");
        authenticate();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String... authorities) {
        var authentication = new UsernamePasswordAuthenticationToken(
                ACTOR,
                "N/A",
                java.util.Arrays.stream(authorities)
                        .map(SimpleGrantedAuthority::new)
                        .toList());
        authentication.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, ACTOR));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private record OpenSession(UUID purchaseOrderId, UUID productId, ReceivingSessionResponse session) {
        UUID lineId() {
            return session.getLines().getFirst().getLineId();
        }
    }

    private OpenSession openSession(int quantity) {
        UUID productId = UUID.randomUUID();
        extProductReplicaRepository.save(ExtProductReplica.builder()
                .productId(productId)
                .baseUom("EA")
                .trackingLevel("NONE")
                .aggregateVersion(1L)
                .build());
        UUID purchaseOrderId = projection.projectReceivable(
                "APPROVED", UUID.randomUUID(), productId, String.valueOf(quantity), 1_000L);
        return new OpenSession(
                purchaseOrderId,
                productId,
                receivingService.createReceivingSession(
                        new CreateReceivingSessionRequest(purchaseOrderId.toString(), "MANUAL"), ACTOR));
    }

    private ReceiveItemsResponse receive(OpenSession open, String quantity, String key) {
        return receivingService.receiveItemsIntoStaging(
                open.session().getSessionId(),
                new ReceiveItemsRequest(
                        List.of(new ReceiveLineRequest(open.lineId(), new BigDecimal(quantity), null, null, null)),
                        key),
                ACTOR);
    }

    private int goodsReceiptFacts(UUID purchaseOrderId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from event_outbox where record_key = ? and payload like '%goodsreceipt.recorded%'",
                Integer.class, purchaseOrderId.toString());
    }

    private BigDecimal ledgerTotal(UUID productId) {
        return ledgerRepository.findAll().stream()
                .filter(entry -> productId.toString().equals(entry.getStockItemId()))
                .map(entry -> entry.getChangeInQuantity())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    @DisplayName("a session receipt appears as a goods receipt, linked to the session and its line")
    void sessionReceipt_appearsInGoodsReceiptReads() {
        OpenSession open = openSession(10);

        receive(open, "4", null);

        List<GoodsReceiptEntity> receipts = goodsReceiptRepository.findByPurchaseOrderId(open.purchaseOrderId());
        assertThat(receipts).hasSize(1);
        GoodsReceiptEntity receipt = receipts.getFirst();
        assertThat(receipt.getReceivingSessionId()).isEqualTo(open.session().getSessionId());
        GoodsReceiptResponse read = asnService.getGoodsReceipt(receipt.getReceiptId());
        assertThat(read.getPoId()).isEqualTo(open.purchaseOrderId());
        assertThat(read.getLines()).singleElement().satisfies(line -> {
            assertThat(line.getSku()).isEqualTo(open.productId().toString());
            assertThat(line.getQuantityReceived()).isEqualByComparingTo("4");
        });
        assertThat(goodsReceiptFacts(open.purchaseOrderId())).isEqualTo(1);
    }

    @Test
    @DisplayName("over-receipt is rejected without the override, posting and publishing nothing")
    void overReceipt_withoutOverride_isRejected() {
        OpenSession open = openSession(10);

        assertThatThrownBy(() -> receive(open, "11", null)).isInstanceOf(OverReceiptNotPermittedException.class);

        assertThat(ledgerTotal(open.productId())).isEqualByComparingTo("0");
        assertThat(goodsReceiptRepository.findByPurchaseOrderId(open.purchaseOrderId()))
                .isEmpty();
        assertThat(goodsReceiptFacts(open.purchaseOrderId())).isZero();
    }

    @Test
    @DisplayName("over-receipt is accepted with the override")
    void overReceipt_withOverride_isAccepted() {
        OpenSession open = openSession(10);
        authenticate(OVERRIDE);

        receive(open, "11", null);

        assertThat(ledgerTotal(open.productId())).isEqualByComparingTo("11");
        assertThat(goodsReceiptFacts(open.purchaseOrderId())).isEqualTo(1);
    }

    @Test
    @DisplayName("received quantity and the guard are cumulative across calls")
    void receivedQuantity_isCumulative() {
        OpenSession open = openSession(10);

        receive(open, "6", null);
        assertThatThrownBy(() -> receive(open, "5", null)).isInstanceOf(OverReceiptNotPermittedException.class);
        receive(open, "4", null);

        ReceivingSessionResponse after =
                receivingService.getReceivingSession(open.session().getSessionId());
        assertThat(after.getLines().getFirst().getReceivedQuantity()).isEqualByComparingTo("10");
        assertThat(ledgerTotal(open.productId())).isEqualByComparingTo("10");
        assertThat(goodsReceiptRepository.findByPurchaseOrderId(open.purchaseOrderId()))
                .hasSize(2);
    }

    @Test
    @DisplayName("a retry with the same key posts and publishes once and returns the original response")
    void retryWithSameKey_isANoOp() {
        OpenSession open = openSession(10);

        ReceiveItemsResponse first = receive(open, "4", "retry-key");
        ReceiveItemsResponse retry = receive(open, "4", "retry-key");

        assertThat(retry).isEqualTo(first);
        assertThat(ledgerTotal(open.productId())).isEqualByComparingTo("4");
        assertThat(goodsReceiptRepository.findByPurchaseOrderId(open.purchaseOrderId()))
                .hasSize(1);
        assertThat(goodsReceiptFacts(open.purchaseOrderId())).isEqualTo(1);
        assertThat(receivingService
                        .getReceivingSession(open.session().getSessionId())
                        .getLines()
                        .getFirst()
                        .getReceivedQuantity())
                .isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("over HTTP: Idempotency-Key header retry replays, a different payload is 409, over-receipt is 422")
    void http_idempotencyHeaderAndErrorCodes() throws Exception {
        OpenSession open = openSession(10);
        String url = "/v1/inventory/receiving/sessions/" + open.session().getSessionId() + "/receive";
        String body = "{\"lines\":[{\"lineId\":\"%s\",\"receivedQuantity\":%s}]}";

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post(url)
                            .header("X-User", ACTOR)
                            .header("X-Authorities", "inventory:receiving:complete")
                            .header("Idempotency-Key", "http-key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body.formatted(open.lineId(), 4)))
                    .andExpect(status().isOk());
        }
        assertThat(ledgerTotal(open.productId())).isEqualByComparingTo("4");

        mockMvc.perform(post(url)
                        .header("X-User", ACTOR)
                        .header("X-Authorities", "inventory:receiving:complete")
                        .header("Idempotency-Key", "http-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(open.lineId(), 3)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        mockMvc.perform(post(url)
                        .header("X-User", ACTOR)
                        .header("X-Authorities", "inventory:receiving:complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(open.lineId(), 7)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OVER_RECEIPT_NOT_PERMITTED"));

        mockMvc.perform(post(url)
                        .header("X-User", ACTOR)
                        .header("X-Authorities", "inventory:receiving:complete")
                        .header("Idempotency-Key", "header-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"idempotencyKey\":\"body-key\",\"lines\":[{\"lineId\":\"%s\",\"receivedQuantity\":1}]}"
                                        .formatted(open.lineId())))
                .andExpect(status().isBadRequest());

        assertThat(ledgerTotal(open.productId())).isEqualByComparingTo("4");
    }
}
