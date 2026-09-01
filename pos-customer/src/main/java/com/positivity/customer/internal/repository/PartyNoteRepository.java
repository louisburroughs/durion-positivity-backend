package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.PartyNote;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartyNoteRepository extends JpaRepository<PartyNote, UUID> {

    /** Redelivery guard for the workorder-note projection (#1584). */
    boolean existsBySourceEventId(@NonNull String sourceEventId);
}
