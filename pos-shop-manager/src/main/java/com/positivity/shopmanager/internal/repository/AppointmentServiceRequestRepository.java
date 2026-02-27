package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.AppointmentServiceRequest;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppointmentServiceRequestRepository extends JpaRepository<AppointmentServiceRequest, UUID> {
    List<AppointmentServiceRequest> findByAppointmentId(@NonNull UUID appointmentId);
}
