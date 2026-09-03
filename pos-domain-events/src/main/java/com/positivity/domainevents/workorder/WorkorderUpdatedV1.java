package com.positivity.domainevents.workorder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
 * @param locationId the site the work is performed at — {@code shopId} names the owning shop,
 *     this names the physical location whose bays and mobile units the work occupies
 * @param resourceId the bay or mobile unit the workorder currently occupies (null when unassigned)
 * @param resourceType what {@code resourceId} points at, {@code BAY} or {@code MOBILE_UNIT};
 *     carried as the owner's enum name so a consumer never has to infer the kind from the id
 * @param mechanicIds every technician assigned to the workorder — a list, because a job may carry
 *     more than one, and the first element is not privileged
 * @param promisedAt the time the vehicle is promised back to the customer
 * @param scheduledDate the day the work is scheduled for (owner's {@code scheduled_date})
 *
 * <p>{@code vehicleId} and the {@code services} list plus the extended {@code PartLine} fields
 * (description, unitPrice, lineTotal, photoEvidenceUrl) are additive within schema v1
 * (ADR-0044 §3, #924): they let pos-warranty's candidate-line search read an {@code ext_workorder}
 * replica in place of the retired synchronous {@code WorkorderClient} detail call. Consumers that
 * only need the pre-existing fields (pos-inventory, pos-invoice) ignore them.
 *
 * <p>The assignment block — {@code locationId}, {@code resourceId}, {@code resourceType},
 * {@code mechanicIds}, {@code promisedAt}, {@code scheduledDate} — is additive within schema v1
 * (ADR-0044 §3, #1658). It exists so pos-shop-manager's shop dashboard can answer "what is on
 * every bay and mobile unit at this location, and who is working it" from a local replica instead
 * of a synchronous call into pos-workorder, which ADR-0044 R1 forbids. {@code mechanicIds} is a
 * list on purpose: the owner stores a JSON array and a job may carry more than one technician, so
 * a scalar would silently drop assignments.
 *
 * <p><strong>{@code promisedAt} is null in every fact published today.</strong> The owner's
 * {@code Workorder} aggregate has no promise-time field, so there is nothing to snapshot. The
 * field is declared here because it is part of the contract consumers sort on; it starts carrying
 * a value the day pos-workorder grows the column, with no consumer change.
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
        @Nullable Instant updatedAt,
        @Nullable UUID locationId,
        @Nullable UUID resourceId,
        @Nullable String resourceType,
        @Nullable List<UUID> mechanicIds,
        @Nullable Instant promisedAt,
        @Nullable LocalDate scheduledDate) {

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
     * @param declined customer declined the line (additive, order parity story E1 #1077)
     * @param returnable explicit settled-line returnability (resolved order-spec Q6; additive,
     *     story E1) — never inferred by consumers, null means "not marked"
     */
    public record PartLine(
            @NonNull UUID workorderLineId,
            @Nullable UUID productEntityId,
            @Nullable String description,
            @Nullable BigDecimal quantity,
            @Nullable BigDecimal unitPrice,
            @Nullable BigDecimal lineTotal,
            @Nullable String photoEvidenceUrl,
            @Nullable Boolean declined,
            @Nullable Boolean returnable) {

        /** Pre-#924 arity (product reference + quantity only). */
        public PartLine(@NonNull UUID workorderLineId, @Nullable UUID productEntityId, @Nullable BigDecimal quantity) {
            this(workorderLineId, productEntityId, null, quantity, null, null, null, null, null);
        }

        /** Pre-#1077 arity (no declined/returnable). */
        public PartLine(
                @NonNull UUID workorderLineId,
                @Nullable UUID productEntityId,
                @Nullable String description,
                @Nullable BigDecimal quantity,
                @Nullable BigDecimal unitPrice,
                @Nullable BigDecimal lineTotal,
                @Nullable String photoEvidenceUrl) {
            this(
                    workorderLineId,
                    productEntityId,
                    description,
                    quantity,
                    unitPrice,
                    lineTotal,
                    photoEvidenceUrl,
                    null,
                    null);
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
     * @param declined customer declined the line (additive, order parity story E1 #1077)
     */
    public record ServiceLine(
            @NonNull UUID workorderLineId,
            @Nullable String description,
            @Nullable BigDecimal quantity,
            @Nullable BigDecimal unitPrice,
            @Nullable BigDecimal lineTotal,
            @Nullable String photoEvidenceUrl,
            @Nullable Boolean declined) {

        /** Pre-#1077 arity (no declined). */
        public ServiceLine(
                @NonNull UUID workorderLineId,
                @Nullable String description,
                @Nullable BigDecimal quantity,
                @Nullable BigDecimal unitPrice,
                @Nullable BigDecimal lineTotal,
                @Nullable String photoEvidenceUrl) {
            this(workorderLineId, description, quantity, unitPrice, lineTotal, photoEvidenceUrl, null);
        }
    }

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

    /** Pre-#1658 arity (no assignment block). */
    public WorkorderUpdatedV1(
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
        this(
                workorderId,
                workorderNumber,
                status,
                shopId,
                customerId,
                vehicleId,
                invoiceId,
                parts,
                services,
                createdAt,
                updatedAt,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
