package com.positivity.warranty.internal.repository;

import com.positivity.warranty.internal.entity.ClaimStatusHistory;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimStatusHistoryRepository extends JpaRepository<ClaimStatusHistory, UUID> {

    @NonNull
    List<ClaimStatusHistory> findByClaimIdOrderByCreatedAtAsc(@NonNull UUID claimId);
}
