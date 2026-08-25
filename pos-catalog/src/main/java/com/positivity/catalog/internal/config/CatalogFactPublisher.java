package com.positivity.catalog.internal.config;

import com.positivity.catalog.internal.entity.Category;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.ProductStatus;
import com.positivity.catalog.internal.entity.ProductTrackingLevel;
import com.positivity.catalog.internal.entity.ServiceEntity;
import com.positivity.catalog.internal.entity.SubstitutionGroupMemberEntity;
import com.positivity.catalog.internal.entity.SupplierArticleCodeEntity;
import com.positivity.catalog.internal.repository.ProductUomRepository;
import com.positivity.catalog.internal.repository.SubstitutionGroupMemberRepository;
import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.catalog.CatalogServiceUpdatedV1;
import com.positivity.domainevents.catalog.ProductUpdatedV1;
import com.positivity.domainevents.catalog.SupplierArticleCodeUpdatedV1;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Emits this module's facts — {@code catalog.product.updated} after product mutations
 * (ADR-0044 §6, #924), {@code catalog.service.updated} after service mutations (#1306), and
 * {@code catalog.supplier-article-code.updated} (CAP-320 #1347) — to the catalog outbox.
 *
 * <p>No-op when the Kafka feature flag ({@code pos.catalog.kafka.enabled}) is off — the
 * {@link OutboxEventWriter} bean is conditional, so this publisher degrades gracefully. Must be
 * called inside the mutating transaction (the writer requires {@code MANDATORY} propagation).
 *
 * <p>Since #1023 (inventory Odoo-parity Story X1, schema version 2) the payload additionally
 * carries the product's base UoM, tracking level, per-product UoM conversion set, and
 * substitution-group membership, read from the current persisted state at publish time. Because
 * the payload is always assembled here from entity state, any replay/re-emit path that goes
 * through {@link #publishProductUpdated(ProductEntity)} automatically includes the full contract.
 *
 * <p>The three fact-carrying entities ({@code ProductEntity}, {@code ServiceEntity},
 * {@code SupplierArticleCodeEntity}) each carry a JPA {@code @Version}, and the envelope's
 * {@code aggregateVersion} is that counter (#1486): it strictly increments on every committed
 * mutation, so — unlike the retired {@code updatedAt}-epoch-millis convention — it can never tie
 * when two mutations land in the same millisecond. Migration V15 seeded it from the legacy
 * epoch-millis values, so the published sequence continues monotonically from what consumers
 * already hold. An update publisher flushes the pending mutation before reading the version, so
 * the increment Hibernate is about to apply is already reflected in the emitted fact. Callers
 * mutating only attribute tables (product_uom, substitution_group_member) must still dirty the
 * product row (bumping {@code updatedAt} does this) before publishing, so the flush here has an
 * actual increment to pick up.
 */
@Slf4j
@Component
public class CatalogFactPublisher {

    private final Clock clock;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final ProductUomRepository productUomRepository;
    private final SubstitutionGroupMemberRepository substitutionGroupMemberRepository;
    private final EntityManager entityManager;

    public CatalogFactPublisher(
            Clock clock,
            ObjectProvider<OutboxEventWriter> outboxEventWriter,
            ProductUomRepository productUomRepository,
            SubstitutionGroupMemberRepository substitutionGroupMemberRepository,
            EntityManager entityManager) {
        this.clock = clock;
        this.outboxEventWriter = outboxEventWriter;
        this.productUomRepository = productUomRepository;
        this.substitutionGroupMemberRepository = substitutionGroupMemberRepository;
        this.entityManager = entityManager;
    }

    /**
     * Whether facts published here actually reach the outbox — false when
     * {@code pos.catalog.kafka.enabled} is off and the {@link OutboxEventWriter} bean is absent.
     *
     * <p>Exposed so a bulk re-emit can refuse rather than report success for facts it silently
     * dropped: an ordinary write is right to carry on regardless (the business change is what
     * matters), but a replay exists only to produce facts, and one that queues none has done
     * nothing at all.
     */
    public boolean publicationEnabled() {
        return outboxEventWriter.getIfAvailable() != null;
    }

    /**
     * Emits {@code catalog.product.updated} after a product is created or updated.
     *
     * <p>Flushed before reading {@code aggregateVersion}: the mutation that triggered this call is
     * still pending in the persistence context, and flushing here forces Hibernate to apply the
     * {@code @Version} increment (and stamp {@code updatedAt}) so the envelope carries the version
     * the row is about to commit as, not the one it held before this write (#1486).
     */
    public void publishProductUpdated(@NonNull ProductEntity product) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        entityManager.flush();
        publishProduct(writer, product, product.getStatus() == ProductStatus.ACTIVE, product.getVersion());
    }

    /**
     * Emits the {@code catalog.product.updated} tombstone for a hard-deleted product (#1306).
     *
     * <p>{@code catalog-items} deletes a product row outright, and until now that removal was
     * announced to nobody: every replica kept the product active and went on resolving it. That
     * was invisible while consumers only read manufacturer and warranty terms from their replica,
     * and stopped being invisible when pos-marketing began answering "does this catalog reference
     * exist" from one — a deleted product, its SKU and its category all still resolved.
     *
     * <p>Published before the row is deleted, not after: the payload is assembled from the
     * product's UoM and substitution-group rows, which the delete takes with it. Same transaction
     * either way, so the fact and the deletion still commit together.
     *
     * <p>Versioned deterministically as {@code version + 1} (#1486) — one past every fact this
     * aggregate has ever published, without needing a clock comparison: the delete never persists a
     * new row, so there is no flush to pick a fresh {@code @Version} up from.
     */
    public void publishProductRemoved(@NonNull ProductEntity product) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        publishProduct(writer, product, false, product.getVersion() + 1);
    }

    private void publishProduct(
            OutboxEventWriter writer, ProductEntity product, boolean active, long aggregateVersion) {
        Category category = product.getCategory();
        ProductTrackingLevel trackingLevel =
                product.getTrackingLevel() == null ? ProductTrackingLevel.NONE : product.getTrackingLevel();
        SubstitutionGroupMemberEntity membership = substitutionGroupMemberRepository
                .findByProductId(product.getId())
                .orElse(null);
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
                active,
                product.getCreatedAt(),
                product.getUpdatedAt(),
                product.getUnitOfMeasure(),
                trackingLevel.name(),
                uomConversionsOf(product.getId()),
                membership == null ? null : membership.getGroup().getId(),
                membership == null ? null : substitutionMemberIdsOf(membership),
                product.getProductCode(),
                product.getProductCodeType() == null
                        ? null
                        : product.getProductCodeType().name());
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
        log.debug(
                "Queued catalog.product.updated productId={} sku={} active={}",
                product.getId(),
                product.getSku(),
                active);
    }

    /**
     * Emits {@code catalog.service.updated} after a service is created or updated (#1306).
     *
     * <p>Services had no fact of their own, so a consumer of {@code catalog.events.v1} could
     * resolve {@code product:} references and nothing else; pos-marketing needs {@code service:}
     * — the form a campaign's {@code catalogFocusRef} most often takes — to be resolvable without
     * a synchronous read across the catalog domain wall (ADR-0044 R1).
     *
     * <p>Flushed before reading {@code aggregateVersion}, the same way {@link #publishProductUpdated}
     * is: the {@code @Version} increment and the {@code updatedAt} stamp are both pending until then,
     * and the envelope must carry the version the row is about to commit as (#1486).
     */
    public void publishServiceUpdated(@NonNull ServiceEntity service) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        entityManager.flush();
        publishService(writer, service, true, service.getUpdatedAt(), service.getVersion());
    }

    /**
     * Emits the {@code catalog.service.updated} tombstone for a deleted service (#1306).
     *
     * <p>A deletion is the only retirement a service currently has, and a consumer that never
     * hears about it keeps resolving a reference to something no longer in the catalog. The
     * tombstone carries {@code active = false} and the name the service had, which is what lets a
     * replica answer "that service was removed" rather than either resolving it or forgetting it
     * ever existed.
     *
     * <p>The row is gone, so there is no fresh state to flush. Versioned deterministically as
     * {@code version + 1} (#1486) — one past every fact this aggregate has ever published, which is
     * why a service created and deleted inside the same millisecond still gets a tombstone that
     * outranks the upsert it supersedes.
     */
    public void publishServiceRemoved(@NonNull ServiceEntity service) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        publishService(writer, service, false, null, service.getVersion() + 1);
    }

    private void publishService(
            OutboxEventWriter writer,
            ServiceEntity service,
            boolean active,
            @Nullable Instant updatedAt,
            long aggregateVersion) {
        CatalogServiceUpdatedV1 payload = new CatalogServiceUpdatedV1(
                service.getId(),
                service.getName(),
                service.getShortDescription(),
                service.getLongDescription(),
                active,
                service.getCreatedAt(),
                updatedAt);
        DomainEventEnvelope<CatalogServiceUpdatedV1> envelope = DomainEventEnvelope.of(
                CatalogServiceUpdatedV1.EVENT_TYPE,
                CatalogServiceUpdatedV1.SCHEMA_VERSION,
                service.getId(),
                aggregateVersion,
                "pos-catalog",
                null,
                null,
                payload,
                clock);
        writer.publish(DomainTopics.events("catalog"), envelope);
        log.debug(
                "Queued catalog.service.updated serviceId={} name={} active={}",
                service.getId(),
                service.getName(),
                active);
    }

    /**
     * Emits {@code catalog.supplier-article-code.updated} for one vendor+product pair (CAP-320
     * #1347). Flushed before reading {@code aggregateVersion}, the same way
     * {@link #publishProductUpdated} is: {@code entry}'s pending {@code @Version} increment is
     * applied by the flush, so the envelope carries the version the row is about to commit as
     * (#1486).
     *
     * <p>The envelope's {@code aggregateId} is {@code entry.getId()}, not the product id: the same
     * product legitimately has a row per vendor, and using the product id would let two vendors'
     * independent {@code aggregateVersion} sequences collide under one aggregate — an update from
     * vendor B could look like it regressed vendor A's version. {@code entry}'s own id is stable
     * across updates (found by the {@code (vendorProfileId, productId)} unique constraint and
     * updated in place), so it already identifies exactly this vendor+product pair.
     */
    public void publishSupplierArticleCodeUpdated(@NonNull SupplierArticleCodeEntity entry) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        entityManager.flush();
        long aggregateVersion = entry.getVersion();
        SupplierArticleCodeUpdatedV1 payload = new SupplierArticleCodeUpdatedV1(
                entry.getVendorProfileId(),
                entry.getSupplierRef(),
                entry.getProductId(),
                entry.getSupplierArticleCode());
        DomainEventEnvelope<SupplierArticleCodeUpdatedV1> envelope = DomainEventEnvelope.of(
                SupplierArticleCodeUpdatedV1.EVENT_TYPE,
                SupplierArticleCodeUpdatedV1.SCHEMA_VERSION,
                entry.getId(),
                aggregateVersion,
                "pos-catalog",
                null,
                null,
                payload,
                clock);
        writer.publish(DomainTopics.events("catalog"), envelope);
        log.debug(
                "Queued catalog.supplier-article-code.updated productId={} vendorProfileId={}",
                entry.getProductId(),
                entry.getVendorProfileId());
    }

    private @Nullable List<ProductUpdatedV1.UomConversion> uomConversionsOf(UUID productId) {
        List<ProductUpdatedV1.UomConversion> conversions =
                productUomRepository.findByProductIdOrderByUomCodeAsc(productId).stream()
                        .map(row -> new ProductUpdatedV1.UomConversion(
                                row.getUomCode(),
                                row.getUomType() == null
                                        ? null
                                        : row.getUomType().name(),
                                row.getFactorToBase(),
                                row.getPrecisionScale()))
                        .toList();
        return conversions.isEmpty() ? null : conversions;
    }

    private List<UUID> substitutionMemberIdsOf(SubstitutionGroupMemberEntity membership) {
        return substitutionGroupMemberRepository
                .findByGroupIdOrderByCreatedAtAsc(membership.getGroup().getId())
                .stream()
                .map(member -> member.getProduct().getId())
                .toList();
    }
}
