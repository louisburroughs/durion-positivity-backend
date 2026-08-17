package com.positivity.catalog.internal.config;

import com.positivity.catalog.internal.entity.Category;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.ProductStatus;
import com.positivity.catalog.internal.entity.ProductTrackingLevel;
import com.positivity.catalog.internal.entity.SubstitutionGroupMemberEntity;
import com.positivity.catalog.internal.entity.SupplierArticleCodeEntity;
import com.positivity.catalog.internal.repository.ProductUomRepository;
import com.positivity.catalog.internal.repository.SubstitutionGroupMemberRepository;
import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.catalog.ProductUpdatedV1;
import com.positivity.domainevents.catalog.SupplierArticleCodeUpdatedV1;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
 * <p>Since #1023 (inventory Odoo-parity Story X1, schema version 2) the payload additionally
 * carries the product's base UoM, tracking level, per-product UoM conversion set, and
 * substitution-group membership, read from the current persisted state at publish time. Because
 * the payload is always assembled here from entity state, any replay/re-emit path that goes
 * through {@link #publishProductUpdated(ProductEntity)} automatically includes the full contract.
 *
 * <p>pos-catalog's {@code ProductEntity} has no JPA {@code @Version}; the envelope's
 * {@code aggregateVersion} carries {@code updatedAt} as epoch millis (monotonic per product), which
 * consumers use as the stale-event guard. Callers mutating only attribute tables (product_uom,
 * substitution_group_member) must bump the product's {@code updatedAt} before publishing.
 */
@Slf4j
@Component
public class CatalogFactPublisher {

    private final Clock clock;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final ProductUomRepository productUomRepository;
    private final SubstitutionGroupMemberRepository substitutionGroupMemberRepository;

    public CatalogFactPublisher(
            Clock clock,
            ObjectProvider<OutboxEventWriter> outboxEventWriter,
            ProductUomRepository productUomRepository,
            SubstitutionGroupMemberRepository substitutionGroupMemberRepository) {
        this.clock = clock;
        this.outboxEventWriter = outboxEventWriter;
        this.productUomRepository = productUomRepository;
        this.substitutionGroupMemberRepository = substitutionGroupMemberRepository;
    }

    public void publishProductUpdated(@NonNull ProductEntity product) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        Category category = product.getCategory();
        long aggregateVersion =
                product.getUpdatedAt() == null ? 0L : product.getUpdatedAt().toEpochMilli();
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
                product.getStatus() == ProductStatus.ACTIVE,
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
        log.debug("Queued catalog.product.updated productId={} sku={}", product.getId(), product.getSku());
    }

    /**
     * Emits {@code catalog.supplier-article-code.updated} for one vendor+product pair (CAP-320
     * #1347). Called after {@code entry} is persisted, so {@code updatedAt} is already stamped by
     * auditing and can carry the same monotonic epoch-millis version scheme as
     * {@link #publishProductUpdated}.
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
        long aggregateVersion =
                entry.getUpdatedAt() == null ? 0L : entry.getUpdatedAt().toEpochMilli();
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
