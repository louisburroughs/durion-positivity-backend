package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.WorkorderNote;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkorderNoteRepository extends JpaRepository<WorkorderNote, UUID> {

    /**
     * Newest first. {@code noteId} breaks the tie because it is UUIDv7 — two notes recorded in the
     * same instant would otherwise come back in arbitrary order.
     */
    @NonNull
    List<WorkorderNote> findByWorkorderIdOrderByCreatedAtDescNoteIdDesc(@NonNull UUID workorderId);
}
