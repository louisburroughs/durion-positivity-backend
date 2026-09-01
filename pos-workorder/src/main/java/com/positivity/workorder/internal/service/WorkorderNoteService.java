package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.dto.AddWorkorderNoteRequest;
import com.positivity.workorder.internal.dto.WorkorderNoteResponse;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Notes about the customer, recorded while a workorder is being worked (issue #1584).
 *
 * <p>Adding a note publishes {@code workorder.note.added.v1}; pos-customer projects it onto the
 * party's CRM timeline. The note is written here and read back here — CRM's copy is a projection,
 * never an editable second original.
 */
public interface WorkorderNoteService {

    /**
     * Record a note against a workorder and publish the fact.
     *
     * @param workorderId the workorder
     * @param request the note
     * @param authoredBy the user recording it, or null when the actor is unknown
     * @return the saved note
     * @throws com.positivity.workorder.internal.exception.WorkorderNotFoundException if no such
     *     workorder exists
     */
    @NonNull
    WorkorderNoteResponse addNote(
            @NonNull UUID workorderId, @NonNull AddWorkorderNoteRequest request, @Nullable String authoredBy);

    /** The workorder's notes, most recent first. */
    @NonNull
    List<WorkorderNoteResponse> listNotes(@NonNull UUID workorderId);
}
