package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.TimePeriod;
import com.positivity.people.internal.enums.TimePeriodStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TimePeriodRepository extends JpaRepository<TimePeriod, UUID> {

    List<TimePeriod> findAllByTenantIdOrderByStartDateDesc(UUID tenantId);

    Optional<TimePeriod> findByTimePeriodIdAndTenantId(UUID timePeriodId, UUID tenantId);

    List<TimePeriod> findByStatusAndEndDateBefore(TimePeriodStatus status, LocalDate endDateExclusive);

    /**
     * True when the tenant already has a period overlapping the candidate range. Overlap on
     * inclusive date ranges: {@code existing.start <= candidate.end AND existing.end >=
     * candidate.start}, so the parameters are the candidate's end then start.
     */
    boolean existsByTenantIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            UUID tenantId, LocalDate candidateEndDate, LocalDate candidateStartDate);

    @Query("SELECT DISTINCT p.tenantId FROM TimePeriod p")
    List<UUID> findDistinctTenantIds();
}
