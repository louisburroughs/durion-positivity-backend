package com.positivity.order.internal.service;

import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.catalog.SupplierArticleCodeUpdatedV1;
import com.positivity.order.internal.entity.ExtSupplierArticleCode;
import com.positivity.order.internal.entity.ProcessedEvent;
import com.positivity.order.internal.repository.ExtSupplierArticleCodeRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code catalog.events.v1} into the {@code ext_supplier_article_code} replica (CAP-320
 * #1347, ADR-0044 §6): the vendor's own article code, per vendor and product, for purchase-order
 * transmission to a non-EAN vendor.
 *
 * <p>A distinct listener from {@link ProductEventsListener} rather than a branch inside it: that
 * listener's stale guard looks its existing row up by the payload's single id field
 * ({@code productId}), which is right for a fact that is one row per product. This fact is one row
 * per {@code (supplierRef, productId)} pair, so its existing-row lookup and its stale guard both
 * need the pair, not the id alone — a shape the other listener's contract does not fit.
 *
 * <p>The stale guard itself is {@link ReplicaVersionGuard} (#1486):
 * pos-catalog's {@code aggregateVersion} strictly advances, so a held row is stale only when its
 * version is strictly greater than the incoming fact's — an equal version applies, both because it
 * is an idempotent no-op for live traffic and because {@code POST .../facts/replay} depends on it
 * to repair a replica that holds the version number but wrong or missing rows.
 *
 * <p>Transaction shape (#2146): the listener method is not {@code @Transactional}; the handler
 * and its {@code processed_events} mark run together in a {@code REQUIRES_NEW} transaction of
 * their own. A permanent failure thrown through a transactional repository or service therefore
 * rolls back only that work and is logged and skipped, rather than marking a listener-wide
 * transaction rollback-only, whose commit would throw {@code UnexpectedRollbackException} and
 * send the record through the container's retry and dead-letter ladder. Transient database errors
 * still propagate for container retry, and since the mark commits with the work there is no
 * window in which one lands without the other.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.order.kafka", name = "enabled", havingValue = "true")
public class SupplierArticleCodeEventsListener {

    static final String OWNER = "catalog";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtSupplierArticleCodeRepository extSupplierArticleCodeRepository;

    /** The event's handler work and its processed mark, in a transaction of their own; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public SupplierArticleCodeEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtSupplierArticleCodeRepository extSupplierArticleCodeRepository,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extSupplierArticleCodeRepository = extSupplierArticleCodeRepository;
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @KafkaListener(
            topics = "${pos.order.kafka.catalog-events-topic:catalog.events.v1}",
            groupId = "${pos.order.kafka.supplier-article-code-consumer-group:pos-order-supplier-article-code}")
    public void onCatalogEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable catalog event: {}", message, e);
            return;
        }
        if (!SupplierArticleCodeUpdatedV1.EVENT_TYPE.equals(
                envelope.path("eventType").stringValue(null))) {
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
            handlerTransaction.executeWithoutResult(_ -> {
                applyUpdate(envelope);
                processedEventRepository.save(ProcessedEvent.builder()
                        .eventId(eventId)
                        .owner(OWNER)
                        .processedAt(Instant.now(clock))
                        .build());
            });
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed catalog event eventId={}", eventId, e);
        }
    }

    private void applyUpdate(JsonNode envelope) {
        JsonNode payload = envelope.path("payload");
        String supplierRef = payload.path("supplierRef").stringValue(null);
        String productIdValue = payload.path("productId").stringValue(null);
        String vendorProfileIdValue = payload.path("vendorProfileId").stringValue(null);
        String supplierArticleCode = payload.path("supplierArticleCode").stringValue(null);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0L);

        // Validated before parsing, so a missing field reads as "missing field" rather than
        // surfacing as a UUID-parse or null-pointer failure that means the same thing but hides it.
        // Left as a bare IllegalArgumentException (issue #1694 audit, category d): this is an
        // internal Kafka payload, never reachable from a controller, and the caller (line ~87)
        // already catches Exception broadly and logs+skips a malformed event.
        if (supplierRef == null
                || supplierRef.isBlank()
                || productIdValue == null
                || vendorProfileIdValue == null
                || supplierArticleCode == null
                || supplierArticleCode.isBlank()) {
            throw new IllegalArgumentException("supplier article code fact missing a required field");
        }
        UUID productId = UUID.fromString(productIdValue);
        UUID vendorProfileId = UUID.fromString(vendorProfileIdValue);

        ExtSupplierArticleCode existing = extSupplierArticleCodeRepository
                .findBySupplierRefAndProductId(supplierRef, productId)
                .orElse(null);
        // Strictly-newer-only skip: equal versions APPLY (#1486, ReplicaVersionGuard) — catalog's
        // aggregateVersion strictly advances, so equal means identical content, and replay resends
        // the held version deliberately to repair a replica with wrong or missing rows.
        if (existing != null && ReplicaVersionGuard.isStale(existing.getAggregateVersion(), aggregateVersion)) {
            return;
        }
        ExtSupplierArticleCode replica = existing != null ? existing : new ExtSupplierArticleCode();
        replica.setSupplierRef(supplierRef);
        replica.setVendorProfileId(vendorProfileId);
        replica.setProductId(productId);
        replica.setSupplierArticleCode(supplierArticleCode);
        replica.setAggregateVersion(aggregateVersion);
        extSupplierArticleCodeRepository.save(replica);
    }
}
