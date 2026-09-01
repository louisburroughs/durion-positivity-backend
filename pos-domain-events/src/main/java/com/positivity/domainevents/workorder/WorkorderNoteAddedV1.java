package com.positivity.domainevents.workorder;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a note about the customer was recorded on a workorder (issue #1584).
 *
 * <p>Published by pos-workorder on {@code workorder.events.v1} with
 * {@code eventType = "workorder.note.added.v1"} once, when the note is saved. pos-customer projects
 * it onto the party's CRM timeline as a {@code WORKORDER_NOTE} interaction and a {@code party_note}
 * row; the workorder owns the fact and CRM never edits it back.
 *
 * <p>This replaces the never-published {@code PartyNoteAdded} event on the non-conforming
 * {@code workorder-events} topic. The name states the workorder-side fact rather than the CRM-side
 * effect it causes, which is what lets other consumers subscribe without inheriting CRM's reading
 * of it.
 *
 * <p>PII-minimal by design: the customer and vehicle ride as ids only. {@code noteText} is the
 * exception and is inherent — the note is free text a person wrote about the customer, and a note
 * stripped of its text is not a note.
 *
 * @param workorderId workorder the note was recorded on (also the envelope aggregateId and record
 *     key, so a workorder's facts stay ordered on one partition)
 * @param workorderNumber human-readable workorder number (null until assigned)
 * @param noteId the workorder-side note row, carried so a consumer can trace back to the source
 * @param partyId customer party the note is about (null for a no-customer job — CRM skips those)
 * @param vehicleId vehicle the job is on (null for a no-vehicle job)
 * @param shopId shop location the workorder executes at
 * @param noteType caller-supplied classification, e.g. {@code CUSTOMER_REQUEST} (null when unset)
 * @param noteText the note as written
 * @param authoredBy the user who recorded the note (null when the actor is unknown)
 * @param addedAt when the note was recorded
 */
public record WorkorderNoteAddedV1(
        @NonNull UUID workorderId,
        @Nullable String workorderNumber,
        @NonNull UUID noteId,
        @Nullable UUID partyId,
        @Nullable UUID vehicleId,
        @Nullable UUID shopId,
        @Nullable String noteType,
        @NonNull String noteText,
        @Nullable String authoredBy,
        @NonNull Instant addedAt) {

    public static final String EVENT_TYPE = "workorder.note.added.v1";
    public static final int SCHEMA_VERSION = 1;

    public WorkorderNoteAddedV1 {
        if (workorderId == null) {
            throw new IllegalArgumentException("workorderId must not be null");
        }
        if (noteId == null) {
            throw new IllegalArgumentException("noteId must not be null");
        }
        if (noteText == null || noteText.isBlank()) {
            throw new IllegalArgumentException("noteText must not be blank");
        }
        if (addedAt == null) {
            throw new IllegalArgumentException("addedAt must not be null");
        }
    }
}
