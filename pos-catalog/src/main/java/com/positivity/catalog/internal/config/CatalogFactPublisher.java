package com.positivity.catalog.internal.config;

import com.positivity.catalog.internal.entity.Category;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.ProductStatus;
import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.catalog.ProductUpdatedV1;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Emits {@code catalog.product.updated} to the catalog outbox after product mutations
 * (ADR-0044 §6, #924).
 *
 * <p>No-op when the Kafka feature flag ({@code pos.catalog.kafka.enabled}) is off — the
 * {@link OutboxEventWriter} bean is conditional, so this publisher degrades gracefully. Must be
 * called inside the mutating transaction (the writer requires {@code MANDATORY} propagation).
 *
 * <p>pos-catalog's {@code ProductEntity} has no JPA {@code @Version}; the envelope's
 * {@code aggregateVersion} carries {@code updatedAt} as epoch millis (monotonic per product), which
 * consumers use as the stale-event guard.
 */
@Slf4j
@Component
public class CatalogFactPublisher {

    private final Clock clock;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;

    public CatalogFactPublisher(Clock clock, ObjectProvider<OutboxEventWriter> outboxEventWriter) {
        this.clock = clock;
        this.outboxEventWriter = outboxEventWriter;
    }

    public void publishProductUpdated(@NonNull ProductEntity product) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        Category category = product.getCategory();
        long aggregateVersion =
                product.getUpdatedAt() == null ? 0L : product.getUpdatedAt().toEpochMilli();
        ProductUpdatedV1 payload = new ProductUpdatedV1(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getManufacturerId(),
                product.getManufacturerName(),
                product.getManufacturerBrand(),
                category == null ? null : category.getId(),
                category == null ? null : category.getName(),
                product.getWarranty(),
                product.getManufacturerWarranty(),
                product.getStatus() == ProductStatus.ACTIVE,
                product.getCreatedAt(),
                product.getUpdatedAt());
        DomainEventEnvelope<ProductUpdatedV1> envelope = DomainEventEnvelope.of(
                ProductUpdatedV1.EVENT_TYPE,
                ProductUpdatedV1.SCHEMA_VERSION,
                product.getId(),
                aggregateVersion,
                "pos-catalog",
                null,
                null,
                payload,
                clock);
        writer.publish(DomainTopics.events("catalog"), envelope);
        log.debug("Queued catalog.product.updated productId={} sku={}", product.getId(), product.getSku());
    }
}
