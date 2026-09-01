package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.WorkorderNote;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkorderNoteRepository extends JpaRepository<WorkorderNote, UUID> {

    List<WorkorderNote> findByWorkorderIdOrderByCreatedAtDesc(UUID workorderId);
}
