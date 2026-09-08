package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.ExtStaffingAssignmentReplica;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtStaffingAssignmentReplicaRepository extends JpaRepository<ExtStaffingAssignmentReplica, UUID> {

    /**
     * Active assignments of one person that are effective on {@code asOf}: {@code status = ACTIVE},
     * {@code effectiveFrom <= asOf} and ({@code effectiveTo} null or {@code >= asOf}).
     *
     * <p>A row with a null {@code effectiveFrom} never matches (the comparison is unknown, not
     * true): pos-people declares the column {@code NOT NULL}, so such a row is malformed and fails
     * closed rather than widening reach.
     */
    @Query("select r from ExtStaffingAssignmentReplica r where r.personId = :personId"
            + " and r.status = '" + ExtStaffingAssignmentReplica.STATUS_ACTIVE + "'"
            + " and r.effectiveFrom <= :asOf"
            + " and (r.effectiveTo is null or r.effectiveTo >= :asOf)")
    @NonNull
    List<ExtStaffingAssignmentReplica> findActiveEffectiveOn(
            @Param("personId") @NonNull UUID personId, @Param("asOf") @NonNull LocalDate asOf);
}
