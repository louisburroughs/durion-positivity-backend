package com.positivity.domainevents.inventory;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a pick task was created or changed state (ADR-0044 §6, issue #901 Phase 5.5).
 *
 * <p>Published by pos-inventory on {@code inventory.events.v1} with
 * {@code eventType = "inventory.pick-task.updated"} — one snapshot per pick task touched in a
 * business transaction. Consumers (pos-workorder) keep an {@code ext_pick_task} replica serving
 * scan resolution and pick-progress reads.
 *
 * <p><b>Schema v2 (issue #1479):</b> adds the additive nullable {@code workorderLineId} — the
 * demand line the task fulfils. Without it a consumer holds pick tasks it cannot map back to the
 * workorder part they were generated from, which is why consuming a picked part never moved
 * {@code workorder_part.quantityConsumed}. Events emitted under schema v1 deserialize with the
 * field absent, and a task generated from a source that has no demand line still carries null.
 *
 * <p><b>Schema v2 additions (issue #2217):</b> adds the additive nullable {@code productCode},
 * {@code locationName}, and {@code locationBarcode} — human-readable codes the mobile pick facade
 * needs to verify a barcode scan without calling pos-catalog or pos-location synchronously
 * (ADR-0044 R1/R3). Older facts deserialize with all three absent.
 *
 * @param pickTaskId pick task identifier (aggregate id of the fact)
 * @param pickListId owning pick list
 * @param workorderId workorder the owning pick list serves
 * @param skuId product/SKU to pick (pos-inventory {@code productId})
 * @param locationId suggested (or scanned) storage location
 * @param quantityRequired quantity to pick
 * @param quantityPicked quantity picked so far
 * @param status pick task status name (PENDING, PICKED, ...)
 * @param sortOrder pick route ordering
 * @param workorderLineId demand line the task fulfils (pos-workorder {@code workorder_part.id});
 *     {@code null} on a v1 event or a task with no demand line
 * @param productCode the SKU's scannable EAN/UPC code, sourced from pos-catalog's {@code
 *     productCode} (ADR-0053 §5, issue #2217). Additive within schema v2 (ADR-0044 §3); {@code
 *     null} when the SKU carries no EAN/UPC code, when its code type is neither (e.g. MPN or an
 *     internal SKU, which are not scan codes), or on a fact emitted before this field existed. The
 *     pick facade compares a scanned code against this field rather than calling pos-catalog
 *     synchronously, which ADR-0044 R1 forbids.
 * @param locationName the suggested storage location's name, which pos-location's replica uses as
 *     its human-readable code. Additive within schema v2, same reason as {@code productCode};
 *     {@code null} when the location replica had not arrived when this fact was built, or on a
 *     fact emitted before this field existed.
 * @param locationBarcode the suggested storage location's barcode, when it carries one. Additive
 *     within schema v2, same reason as {@code productCode}; {@code null} when the location has no
 *     barcode, its replica had not arrived, or on a fact emitted before this field existed.
 */
public record PickTaskUpdatedV1(
        @NonNull UUID pickTaskId,
        @Nullable UUID pickListId,
        @Nullable UUID workorderId,
        @Nullable UUID skuId,
        @Nullable UUID locationId,
        int quantityRequired,
        int quantityPicked,
        @NonNull String status,
        int sortOrder,
        @Nullable UUID workorderLineId,
        @Nullable String productCode,
        @Nullable String locationName,
        @Nullable String locationBarcode) {

    public static final String EVENT_TYPE = "inventory.pick-task.updated";
    public static final int SCHEMA_VERSION = 2;

    public PickTaskUpdatedV1 {
        if (pickTaskId == null) {
            throw new IllegalArgumentException("pickTaskId must not be null");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
    }
}
