package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.WorkorderNote;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A recorded workorder note.
 *
 * @param noteId the note
 * @param workorderId workorder the note was recorded on
 * @param noteType caller-supplied classification (null when unset)
 * @param noteText the note as written
 * @param authoredBy the user who recorded it (null when the actor was unknown)
 * @param createdAt when it was recorded
 */
@Schema(description = "A note about the customer, recorded against a workorder")
public record WorkorderNoteResponse(
        @NonNull UUID noteId,
        @NonNull UUID workorderId,
        @Nullable String noteType,
        @NonNull String noteText,
        @Nullable String authoredBy,
        @NonNull Instant createdAt) {

    public static WorkorderNoteResponse from(@NonNull WorkorderNote note) {
        return new WorkorderNoteResponse(
                note.getNoteId(),
                note.getWorkorderId(),
                note.getNoteType(),
                note.getNoteText(),
                note.getAuthoredBy(),
                note.getCreatedAt());
    }
}
