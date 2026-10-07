package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.UndepositedSessionDrop;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** The bank drops of the undeposited-sessions read model (CAP:550 S18, #2514). */
public interface UndepositedSessionDropRepository extends JpaRepository<UndepositedSessionDrop, UUID> {

    /** The drops of the given sessions, in the order they were recorded at the drawer. */
    @NonNull
    List<UndepositedSessionDrop> findByUndepositedSessionIdInOrderByOccurredAtAscMovementIdAsc(
            @NonNull Collection<UUID> undepositedSessionIds);
}
