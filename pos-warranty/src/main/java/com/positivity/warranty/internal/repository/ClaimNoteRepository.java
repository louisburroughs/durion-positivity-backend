package com.positivity.warranty.internal.repository;

import com.positivity.warranty.internal.entity.ClaimNote;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimNoteRepository extends JpaRepository<ClaimNote, UUID> {

    @NonNull
    List<ClaimNote> findByClaimIdOrderByCreatedAtAsc(@NonNull UUID claimId);
}
