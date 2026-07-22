package com.positivity.domainevents.workorder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a workorder was created or changed (ADR-0044 §6, issue #897 Phase 5.1).
 *
 * <p>Published by pos-workorder on {@code workorder.events.v1} with
 * {@code eventType = "workorder.workorder.updated"} — one snapshot per business transaction that
 * touched the workorder or its part lines. Consumers maintain read-only {@code ext_workorder}
 * replicas serving putaway/receiving line validation (pos-inventory: status + part
 * productEntityId) and invoice search (pos-invoice: workorder-number lookup and enrichment).
 *
 * @param workorderId workorder identifier (also the envelope aggregateId)
 * @param workorderNumber human-readable workorder number (null until assigned)
 * @param status owner {@code WorkorderStatus} name, e.g. {@code IN_PROGRESS}
 * @param shopId shop location the workorder executes at
 * @param customerId owning customer party
 * @param vehicleId serviced vehicle (pos-vehicle-inventory registry id, null for no-vehicle jobs)
 * @param invoiceId generated invoice back-reference (null until invoiced)
 * @param parts current part lines (full replacement set on every fact)
 * @param services current service/labor lines (full replacement set on every fact)
 * @param createdAt owner row creation timestamp
 * @param updatedAt owner row last-update timestamp
 *
 * <p>{@code vehicleId} and the {@code services} list plus the extended {@code PartLine} fields
 * (description, unitPrice, lineTotal, photoEvidenceUrl) are additive within schema v1
 * (ADR-0044 §3, #924): they let pos-warranty's candidate-line search read an {@code ext_workorder}
 * replica in place of the retired synchronous {@code WorkorderClient} detail call. Consumers that
 * only need the pre-existing fields (pos-inventory, pos-invoice) ignore them.
 */
public record WorkorderUpdatedV1(
        @NonNull UUID workorderId,
        @Nullable String workorderNumber,
        @Nullable String status,
        @Nullable UUID shopId,
        @Nullable UUID customerId,
        @Nullable UUID vehicleId,
        @Nullable UUID invoiceId,
        @Nullable List<PartLine> parts,
        @Nullable List<ServiceLine> services,
        @Nullable Instant createdAt,
        @Nullable Instant updatedAt) {

    public static final String EVENT_TYPE = "workorder.workorder.updated";
    public static final int SCHEMA_VERSION = 1;

    /**
     * One workorder part line.
     *
     * @param workorderLineId part line identifier
     * @param productEntityId pos-catalog product reference (null for non-inventory lines)
     * @param description snapshotted line description (additive, #924)
     * @param quantity snapshotted quantity
     * @param unitPrice snapshotted unit price (additive, #924)
     * @param lineTotal snapshotted line total = quantity × unitPrice (additive, #924)
     * @param photoEvidenceUrl photo evidence captured on the part line (additive, #924)
     */
    public record PartLine(
            @NonNull UUID workorderLineId,
            @Nullable UUID productEntityId,
            @Nullable String description,
            @Nullable BigDecimal quantity,
            @Nullable BigDecimal unitPrice,
            @Nullable BigDecimal lineTotal,
            @Nullable String photoEvidenceUrl) {

        /** Pre-#924 arity (product reference + quantity only). */
        public PartLine(@NonNull UUID workorderLineId, @Nullable UUID productEntityId, @Nullable BigDecimal quantity) {
            this(workorderLineId, productEntityId, null, quantity, null, null, null);
        }
    }

    /**
     * One workorder service / labor line (#924). For labor, {@code quantity} carries hours and
     * {@code unitPrice} the hourly rate, mirroring the owner's {@code WorkorderServiceLine}.
     *
     * @param workorderLineId service line identifier
     * @param description snapshotted line description
     * @param quantity snapshotted quantity (hours for labor)
     * @param unitPrice snapshotted unit price (hourly rate for labor)
     * @param lineTotal snapshotted line total = quantity × unitPrice
     * @param photoEvidenceUrl photo evidence captured on the service line
     */
    public record ServiceLine(
            @NonNull UUID workorderLineId,
            @Nullable String description,
            @Nullable BigDecimal quantity,
            @Nullable BigDecimal unitPrice,
            @Nullable BigDecimal lineTotal,
            @Nullable String photoEvidenceUrl) {}

    public WorkorderUpdatedV1 {
        if (workorderId == null) {
            throw new IllegalArgumentException("workorderId must not be null");
        }
    }

    /** Pre-#924 arity (no vehicleId, no service lines). */
    public WorkorderUpdatedV1(
            @NonNull UUID workorderId,
            @Nullable String workorderNumber,
            @Nullable String status,
            @Nullable UUID shopId,
            @Nullable UUID customerId,
            @Nullable UUID invoiceId,
            @Nullable List<PartLine> parts,
            @Nullable Instant createdAt,
            @Nullable Instant updatedAt) {
        this(
                workorderId,
                workorderNumber,
                status,
                shopId,
                customerId,
                null,
                invoiceId,
                parts,
                null,
                createdAt,
                updatedAt);
    }
}
