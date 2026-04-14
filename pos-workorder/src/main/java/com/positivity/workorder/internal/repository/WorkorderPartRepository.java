package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.WorkorderPart;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkorderPartRepository extends JpaRepository<WorkorderPart, UUID> {
    List<WorkorderPart> findByChangeRequest_Id(UUID changeRequestId);

    /**
     * Find all parts directly associated with a workorder (CAP:004 Story #27).
     * This includes standalone parts not tied to a service.
     */
    List<WorkorderPart> findByWorkorderId(UUID workorderId);

    /**
     * Find only standalone parts (parts with direct workorder reference but no
     * service).
     * This avoids duplicates when parts have both workorder and workOrderService
     * set.
     * CAP:007 - Prevent duplicate parts in invoice line items.
     */
    List<WorkorderPart> findByWorkorderIdAndWorkOrderServiceIsNull(UUID workorderId);

    List<WorkorderPart> findByWorkOrderService_WorkOrder_Id(UUID workorderId);
}
