package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.WorkorderServiceLine;
import com.positivity.workorder.internal.enums.WorkorderItemStatus;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkorderServiceRepository extends JpaRepository<WorkorderServiceLine, UUID> {
    List<WorkorderServiceLine> findByChangeRequest_Id(UUID changeRequestId);

    List<WorkorderServiceLine> findByWorkOrder_Id(UUID workorderId);

    /** A service line's workorder, description and progress, read without loading the line. */
    interface ServiceLineGlance {
        UUID getWorkorderId();

        String getDescription();

        WorkorderItemStatus getStatus();

        Boolean getDeclined();
    }

    /**
     * The service lines of every listed workorder, in line order, as glances only (#2025). The
     * dispatch dashboard summarises a whole roster from this one query rather than loading each
     * workorder's lines.
     *
     * <p>Line order is creation order. The id alone cannot give it: a UUIDv7 carries random bits below
     * its millisecond, so two lines promoted in the same millisecond compare arbitrarily. The id only
     * breaks an exact {@code createdAt} tie, to keep the order stable.
     */
    @Query("SELECT s.workOrder.id AS workorderId, s.description AS description, s.status AS status,"
            + " s.declined AS declined FROM WorkorderServiceLine s WHERE s.workOrder.id IN :workorderIds"
            + " ORDER BY s.createdAt, s.id")
    @NonNull
    List<ServiceLineGlance> findGlancesByWorkorderIds(@Param("workorderIds") @NonNull Set<UUID> workorderIds);
}
