package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.AppointmentServiceRequest;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppointmentServiceRequestRepository extends JpaRepository<AppointmentServiceRequest, UUID> {
    List<AppointmentServiceRequest> findByAppointment_AppointmentId(@NonNull UUID appointmentId);

    /**
     * Batch form of {@link #findByAppointment_AppointmentId} (#2270): {@link
     * com.positivity.shopmanager.internal.service.AffectedAppointmentEvaluator} loads every
     * candidate appointment's service requests in one query rather than one per row.
     */
    @NonNull
    List<AppointmentServiceRequest> findByAppointment_AppointmentIdIn(@NonNull Collection<UUID> appointmentIds);
}
