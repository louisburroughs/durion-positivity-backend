package com.positivity.shopmanager.service;

import com.positivity.shopmanager.internal.dto.ShopAuditEntryResponse;
import com.positivity.shopmanager.internal.dto.ShopAuditFilter;
import com.positivity.shopmanager.internal.enums.ShopAuditEventType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Public service API for recording and querying the shop audit trail.
 *
 * <p>
 * Actor identity is NEVER sourced from callers; it is always resolved from
 * the active security context (ADR-0018).
 *
 * <p>
 * Story #61 — Audit Trail for Schedule and Assignment Changes.
 */
public interface ShopAuditService {

    /**
     * Records an audit entry for a schedule change (create / update / cancel).
     *
     * @param eventType     the specific schedule event type
     * @param appointmentId the appointment whose schedule changed
     * @param workorderId   the associated workorder identifier (may be null)
     * @param locationId    the shop location identifier (may be null)
     * @param changeSummary human-readable summary of the change
     * @param changePatch   JSON Patch (RFC 6902) structured diff (may be null)
     * @param reasonCode    structured reason code (may be null)
     * @param reasonNotes   free-text notes (may be null)
     * @return the persisted audit entry response
     */
    @NonNull
    ShopAuditEntryResponse recordScheduleChange(
            @NonNull ShopAuditEventType eventType,
            @NonNull String appointmentId,
            String workorderId,
            String locationId,
            String changeSummary,
            String changePatch,
            String reasonCode,
            String reasonNotes);

    /**
     * Records an audit entry for an assignment change (assign / unassign mechanic).
     *
     * @param eventType     the specific assignment event type
     * @param workorderId   the workorder receiving the assignment change
     * @param mechanicId    the mechanic being assigned or unassigned
     * @param locationId    the shop location identifier (may be null)
     * @param changeSummary human-readable summary of the change
     * @param changePatch   JSON Patch structured diff (may be null)
     * @param reasonCode    structured reason code (may be null)
     * @param reasonNotes   free-text notes (may be null)
     * @return the persisted audit entry response
     */
    @NonNull
    ShopAuditEntryResponse recordAssignmentChange(
            @NonNull ShopAuditEventType eventType,
            @NonNull String workorderId,
            @NonNull String mechanicId,
            String locationId,
            String changeSummary,
            String changePatch,
            String reasonCode,
            String reasonNotes);

    /**
     * Searches the audit trail by the supplied filter criteria.
     * At least one filter criterion must be present; the service MUST throw
     * {@link IllegalArgumentException} if the filter is empty.
     *
     * <p>
     * If no {@code fromDateTime} is supplied, a default of 90 days before now is
     * applied.
     *
     * @param filter search filter — never null
     * @return matching entries in reverse-chronological order
     */
    @NonNull
    List<ShopAuditEntryResponse> search(@NonNull ShopAuditFilter filter);

    /**
     * Finds a single audit entry by its unique identifier.
     *
     * @param id the audit entry UUID
     * @return the entry if found
     */
    @NonNull
    Optional<ShopAuditEntryResponse> findById(@NonNull UUID id);
}
