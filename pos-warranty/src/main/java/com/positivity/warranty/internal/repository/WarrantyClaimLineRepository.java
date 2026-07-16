package com.positivity.warranty.internal.repository;

import com.positivity.warranty.internal.entity.WarrantyClaimLine;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarrantyClaimLineRepository extends JpaRepository<WarrantyClaimLine, UUID> {

    /** Lines of one claim ({@code ClaimId} resolves to the {@code claim.id} path). */
    @NonNull
    List<WarrantyClaimLine> findByClaimId(@NonNull UUID claimId);
}
