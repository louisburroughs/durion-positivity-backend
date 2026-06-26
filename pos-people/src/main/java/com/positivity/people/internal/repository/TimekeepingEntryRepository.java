package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.TimekeepingEntry;
import com.positivity.people.internal.enums.ApprovalStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TimekeepingEntryRepository extends JpaRepository<TimekeepingEntry, UUID> {

    boolean existsByTenantIdAndSourceSystemAndSourceSessionId(UUID tenantId, String sourceSystem, UUID sourceSessionId);

    @Query("SELECT DISTINCT t.employeeId FROM TimekeepingEntry t")
    List<UUID> findDistinctEmployeeIds();

    List<TimekeepingEntry> findByEmployeeIdAndSessionStartTimeGreaterThanEqualAndSessionEndTimeLessThanEqual(
            UUID employeeId, Instant periodStart, Instant periodEnd);

    List<TimekeepingEntry>
            findByEmployeeIdAndApprovalStatusAndSessionStartTimeGreaterThanEqualAndSessionEndTimeLessThanEqual(
                    UUID employeeId, ApprovalStatus approvalStatus, Instant periodStart, Instant periodEnd);
}
