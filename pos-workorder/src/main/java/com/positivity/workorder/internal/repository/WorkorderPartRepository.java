package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.WorkorderPart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkorderPartRepository extends JpaRepository<WorkorderPart, UUID> {
    List<WorkorderPart> findByChangeRequestId(UUID changeRequestId);

    /**
     * Find all parts directly associated with a workorder (CAP:004 Story #27).
     * This includes standalone parts not tied to a service.
     */
    List<WorkorderPart> findByWorkorderId(UUID workorderId);
}
