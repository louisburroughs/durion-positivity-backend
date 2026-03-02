package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.WorkOrderAppointmentMapping;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkOrderAppointmentMappingRepository extends JpaRepository<WorkOrderAppointmentMapping, UUID> {

    Optional<WorkOrderAppointmentMapping> findByWorkOrderId(UUID workOrderId);
}
