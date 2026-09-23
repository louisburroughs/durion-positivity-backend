package com.positivity.order.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantResolver;
import com.positivity.tenancy.kafka.TenantKafkaHeaders;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins {@link InventoryCommandPublisher#requestReservation}'s command-id and payload shape
 * around {@code uomCode} normalization (#1418 review, ADR-0055 stage 3, #1415): a null or
 * whitespace-only {@code uomCode} must keep the pre-#1415 command-id shape (no {@code ":null"}
 * suffix) and never persists a blank string into the payload, while a real code suffixes the
 * idempotency key and is carried into the payload trimmed.
 */
class InventoryCommandPublisherTest {

    private static final UUID SALES_ORDER_LINE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STOCK_ITEM_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final BigDecimal QUANTITY = new BigDecimal("2.00");

    /** Deliberately not the transitional default tenant, so a header carrying it proves propagation. */
    private static final UUID TENANT_ID = UUID.fromString("01900000-0000-7000-8000-0000000000b2");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private InventoryCommandPublisher publisher;

    @BeforeEach
    void setUp() {
        TenantContext.bind(TENANT_ID);
        publisher =
                new InventoryCommandPublisher(kafkaTemplate, objectMapper, new TenantResolver(new TenancyProperties()));
        ReflectionTestUtils.setField(publisher, "inventoryCommandsTopic", "inventory.commands.v1");
        ReflectionTestUtils.setField(publisher, "sendTimeoutMs", 10000L);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> sentRecord() {
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }

    private JsonNode sentCommand() {
        return objectMapper.readTree(sentRecord().value());
    }

    private static UUID expectedCommandId(String idempotencyKey) {
        return UUID.nameUUIDFromBytes(
                (InventoryCommandPublisher.RESERVATION_REQUEST_COMMAND_TYPE + ":" + idempotencyKey)
                        .getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("null uomCode keeps the pre-#1415 command id shape (no \":null\" suffix)")
    void nullUomCode_keepsOldFormatKeyAndOmitsPayloadField() {
        publisher.requestReservation(SALES_ORDER_LINE_ID, STOCK_ITEM_ID, QUANTITY, LOCATION_ID, null);
        JsonNode command = sentCommand();

        UUID expected = expectedCommandId(SALES_ORDER_LINE_ID + ":" + STOCK_ITEM_ID + ":2");
        assertThat(command.path("commandId").stringValue(null)).isEqualTo(expected.toString());
        assertThat(command.path("payload").path("uomCode").stringValue(null)).isNull();
    }

    @Test
    @DisplayName("whitespace-only uomCode collapses to the same old-format key as null")
    void blankUomCode_collapsesToOldFormatKey() {
        publisher.requestReservation(SALES_ORDER_LINE_ID, STOCK_ITEM_ID, QUANTITY, LOCATION_ID, null);
        String nullCommandId = sentCommand().path("commandId").stringValue(null);
        clearInvocations(kafkaTemplate);

        publisher.requestReservation(SALES_ORDER_LINE_ID, STOCK_ITEM_ID, QUANTITY, LOCATION_ID, "   ");
        JsonNode blankCommand = sentCommand();

        assertThat(blankCommand.path("commandId").stringValue(null)).isEqualTo(nullCommandId);
        assertThat(blankCommand.path("payload").path("uomCode").stringValue(null))
                .isNull();
    }

    @Test
    @DisplayName("a real uomCode suffixes the key and is carried trimmed into the payload")
    void realUomCode_suffixesKeyAndCarriesTrimmedValue() {
        publisher.requestReservation(SALES_ORDER_LINE_ID, STOCK_ITEM_ID, QUANTITY, LOCATION_ID, " QT ");
        JsonNode command = sentCommand();

        UUID expected = expectedCommandId(SALES_ORDER_LINE_ID + ":" + STOCK_ITEM_ID + ":2:QT");
        assertThat(command.path("commandId").stringValue(null)).isEqualTo(expected.toString());
        assertThat(command.path("payload").path("uomCode").stringValue(null)).isEqualTo("QT");
    }

    /**
     * #2147: pos-inventory's record interceptor binds the tenant from this header, and falls back to
     * the transitional default when it is absent, so a bare record would apply the command under the
     * wrong tenant (and, once the default is unset, be rejected outright).
     */
    @Test
    @DisplayName("the command record carries the requesting tenant's header, keyed on the sales-order line")
    void commandRecordCarriesTheRequestingTenant() {
        publisher.requestReservation(SALES_ORDER_LINE_ID, STOCK_ITEM_ID, QUANTITY, LOCATION_ID, null);

        ProducerRecord<String, String> record = sentRecord();
        assertThat(record.topic()).isEqualTo("inventory.commands.v1");
        assertThat(record.key()).isEqualTo(SALES_ORDER_LINE_ID.toString());
        assertThat(TenantKafkaHeaders.read(record.headers())).contains(TENANT_ID);
    }

    @Test
    @DisplayName("with no tenant bound and no default configured, nothing is published")
    void noResolvableTenantPublishesNothing() {
        TenantContext.clear();

        assertThatThrownBy(() ->
                        publisher.requestReservation(SALES_ORDER_LINE_ID, STOCK_ITEM_ID, QUANTITY, LOCATION_ID, null))
                .isInstanceOf(IllegalStateException.class);

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }
}
