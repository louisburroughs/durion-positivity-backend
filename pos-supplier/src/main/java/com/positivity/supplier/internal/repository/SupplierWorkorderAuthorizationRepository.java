package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.SupplierWorkorderAuthorizationEntity;
import com.positivity.supplier.internal.enums.WorkorderApprovalStatus;
import com.positivity.supplier.internal.enums.WorkorderAuthorizationStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Fleet workorder authorizations (CAP-323 #1229). */
@Repository
public interface SupplierWorkorderAuthorizationRepository
        extends JpaRepository<SupplierWorkorderAuthorizationEntity, UUID> {

    /**
     * The live authorization for a workorder at a vendor.
     *
     * <p>Scoped by profile as well as workorder because the uniqueness constraint is on the pair: a
     * workorder could in principle be authorized by two fleet programs, and looking one up without
     * saying which vendor would silently pick whichever row came back first.
     */
    @NonNull
    Optional<SupplierWorkorderAuthorizationEntity> findByVendorProfileIdAndWorkorderId(
            @NonNull UUID vendorProfileId, @NonNull UUID workorderId);

    /**
     * Any authorization recorded for a workorder, newest first.
     *
     * <p>The read surface is asked by workorder alone, because a shop asking "is this job
     * authorized" does not know or care which vendor profile answered.
     */
    @NonNull
    List<SupplierWorkorderAuthorizationEntity> findByWorkorderIdOrderByRequestedAtDesc(@NonNull UUID workorderId);

    /**
     * Authorizations still waiting on a vendor, least recently attended to first.
     *
     * <p>Ordering by last poll rather than by age spreads attention evenly: ordering by age would
     * let one old stuck row absorb every tick while newer ones went unpolled.
     *
     * <p>A never-polled row orders by when it was requested, via {@code COALESCE}, rather than by
     * relying on null ordering. That is not tidiness: Postgres sorts nulls last on an ascending
     * sort and H2 sorts them first, so a derived query would have put brand-new authorizations at
     * the back of the queue in production and at the front in every test that checked it.
     */
    @Query("""
            select a from SupplierWorkorderAuthorizationEntity a
            where a.status = :status
            order by coalesce(a.lastPolledAt, a.requestedAt) asc
            """)
    @NonNull
    List<SupplierWorkorderAuthorizationEntity> findAwaitingPoll(
            @Param("status") @NonNull WorkorderAuthorizationStatus status, @NonNull Limit limit);

    /** Completions whose vendor sign-off has not yet succeeded. */
    @NonNull
    List<SupplierWorkorderAuthorizationEntity> findByApprovalStatusOrderByUpdatedAtAsc(
            @NonNull WorkorderApprovalStatus approvalStatus, @NonNull Limit limit);

    /**
     * Everything a human needs to look at, newest first.
     *
     * <p>Both kinds of stuck row in one query, because to an operator they are one queue: an
     * authorization nobody could obtain and a completion nobody could get signed off are both work
     * that will not be paid for unless somebody acts. Splitting them into two screens is how the
     * quieter of the two stops being looked at.
     */
    @Query("""
            select a from SupplierWorkorderAuthorizationEntity a
            where a.status = com.positivity.supplier.internal.enums.WorkorderAuthorizationStatus.MANUAL_REVIEW
               or a.approvalStatus = com.positivity.supplier.internal.enums.WorkorderApprovalStatus.MANUAL_REVIEW
            order by a.updatedAt desc
            """)
    @NonNull
    List<SupplierWorkorderAuthorizationEntity> findNeedingReview(@NonNull Limit limit);
}
