package com.positivity.warranty.internal.repository;

import com.positivity.warranty.internal.entity.ClaimSettlement;
import com.positivity.warranty.internal.enums.SettlementStatus;
import com.positivity.warranty.internal.enums.SettlementType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimSettlementRepository extends JpaRepository<ClaimSettlement, UUID> {

    @NonNull
    List<ClaimSettlement> findByClaimId(@NonNull UUID claimId);

    @NonNull
    List<ClaimSettlement> findByClaimIdAndStatus(@NonNull UUID claimId, @NonNull SettlementStatus status);

    /** Reconciliation worklist scan (issue #922): FAILED pos-invoice-writing settlements. */
    @NonNull
    List<ClaimSettlement> findByStatusAndSettlementTypeInOrderByCreatedAtAsc(
            @NonNull SettlementStatus status, @NonNull Collection<SettlementType> settlementTypes);
}
