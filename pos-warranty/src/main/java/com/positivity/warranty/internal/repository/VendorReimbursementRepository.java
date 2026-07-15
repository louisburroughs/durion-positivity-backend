package com.positivity.warranty.internal.repository;

import com.positivity.warranty.internal.entity.VendorReimbursement;
import com.positivity.warranty.internal.enums.ReimbursementStatus;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorReimbursementRepository extends JpaRepository<VendorReimbursement, UUID> {

    @NonNull
    List<VendorReimbursement> findByClaimId(@NonNull UUID claimId);

    /** Back-office worklist (PRD §8 {@code GET /v1/warranty/reimbursements?status=&providerId=}). */
    @NonNull
    List<VendorReimbursement> findByStatus(@NonNull ReimbursementStatus status);

    @NonNull
    List<VendorReimbursement> findByProviderId(@NonNull UUID providerId);

    @NonNull
    List<VendorReimbursement> findByStatusAndProviderId(@NonNull ReimbursementStatus status, @NonNull UUID providerId);
}
