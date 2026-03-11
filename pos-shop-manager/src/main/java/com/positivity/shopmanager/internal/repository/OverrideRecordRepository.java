package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.OverrideRecord;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OverrideRecordRepository extends JpaRepository<OverrideRecord, UUID> {
    List<OverrideRecord> findByAppointment_AppointmentId(@NonNull UUID appointmentId);
}
