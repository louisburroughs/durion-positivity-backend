package com.positivity.order.internal.service;

import com.positivity.domainevents.catalog.ProductUpdatedV1;
import com.positivity.order.internal.entity.ExtProduct;
import com.positivity.order.internal.entity.ExtProductCode;
import com.positivity.order.internal.entity.ExtProductUomReplica;
import com.positivity.order.internal.entity.ProcessedEvent;
import com.positivity.order.internal.repository.ExtProductCodeRepository;
import com.positivity.order.internal.repository.ExtProductRepository;
import com.positivity.order.internal.repository.ExtProductUomReplicaRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code catalog.events.v1} into the {@code ext_product} replica (ADR-0044 §6, parity
 * story B1): SKU → productId/description/tracking-level resolution for pricing without synchronous
 * REST toward pos-catalog.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.order.kafka", name = "enabled", havingValue = "true")
public class ProductEventsListener {

    static final String OWNER = "catalog";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtProductRepository extProductRepository;
    private final ExtProductUomReplicaRepository extProductUomReplicaRepository;
    private final ExtProductCodeRepository extProductCodeRepository;

    @KafkaListener(
            topics = "${pos.order.kafka.catalog-events-topic:catalog.events.v1}",
            groupId = "${pos.order.kafka.catalog-events-consumer-group:pos-order-catalog-events}")
    @Transactional
    public void onCatalogEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable catalog event: {}", message, e);
            return;
        }
        if (!ProductUpdatedV1.EVENT_TYPE.equals(envelope.path("eventType").stringValue(null))) {
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping catalog event without eventId");
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            applyUpdate(envelope);
            processedEventRepository.save(ProcessedEvent.builder()
                    .eventId(eventId)
                    .owner(OWNER)
                    .processedAt(Instant.now(clock))
                    .build());
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed catalog event eventId={}", eventId, e);
        }
    }

    private void applyUpdate(JsonNode envelope) {
        JsonNode payload = envelope.path("payload");
        UUID productId = UUID.fromString(payload.path("productId").stringValue(null));
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0L);

        ExtProduct existing = extProductRepository.findById(productId).orElse(null);
        if (existing != null && existing.getAggregateVersion() >= aggregateVersion) {
            return;
        }
        ExtProduct replica = existing != null ? existing : new ExtProduct();
        replica.setProductId(productId);
        replica.setSku(payload.path("sku").stringValue(null));
        replica.setName(payload.path("name").stringValue(null));
        replica.setActive(payload.path("active").booleanValue(true));
        replica.setTrackingLevel(payload.path("trackingLevel").stringValue(null));
        replica.setBaseUom(payload.path("baseUom").stringValue(null));
        replica.setAggregateVersion(aggregateVersion);
        replica.setSyncedAt(Instant.now(clock));
        extProductRepository.save(replica);

        applyUomConversions(productId, payload);

        // The product's identity code, which is what lets a purchase-order line name an article the
        // vendor recognises (CAP-320 #1330). A cleared code is stored as null rather than by
        // deleting the row, so "carries no code" stays distinguishable from "never replicated".
        extProductCodeRepository.save(ExtProductCode.builder()
                .productId(productId)
                .codeType(trimToNull(payload.path("productCodeType").stringValue(null)))
                .code(trimToNull(payload.path("productCode").stringValue(null)))
                .aggregateVersion(aggregateVersion)
                .build());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Replaces the product's UoM conversion set (CAP-320 #1334).
     *
     * <p>Replaced wholesale rather than merged, because each fact carries the product's full set.
     * Merging would leave a UoM catalog has retired still convertible here, so a purchase-order
     * line could go on being priced and quantified against a factor its owner no longer publishes.
     */
    private void applyUomConversions(UUID productId, JsonNode payload) {
        extProductUomReplicaRepository.deleteByProductId(productId);
        for (JsonNode conversion : payload.path("uomConversions")) {
            String uomCode = conversion.path("uomCode").stringValue(null);
            if (uomCode == null || uomCode.isBlank()) {
                continue;
            }
            extProductUomReplicaRepository.save(ExtProductUomReplica.builder()
                    .productId(productId)
                    .uomCode(uomCode)
                    .uomType(conversion.path("uomType").stringValue(null))
                    .factorToBase(conversion.path("factorToBase").decimalValue())
                    .precisionScale(conversion.path("precisionScale").intValue(0))
                    .build());
        }
    }
}
