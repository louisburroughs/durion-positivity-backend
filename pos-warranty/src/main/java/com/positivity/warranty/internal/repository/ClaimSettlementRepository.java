package com.positivity.warranty.internal.repository;

import com.positivity.warranty.internal.entity.ClaimSettlement;
import com.positivity.warranty.internal.enums.SettlementStatus;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimSettlementRepository extends JpaRepository<ClaimSettlement, UUID> {

    @NonNull
    List<ClaimSettlement> findByClaimId(@NonNull UUID claimId);

    @NonNull
    List<ClaimSettlement> findByClaimIdAndStatus(@NonNull UUID claimId, @NonNull SettlementStatus status);
}
