package com.positivity.shopmanager.internal.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Slf4j
public class AppointmentEventListener {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAppointmentCreated(AppointmentCreatedEvent event) {
        log.info("AppointmentCreated committed: appointmentId={} customerId={} vehicleId={}",
                event.appointmentId(), event.crmCustomerId(), event.crmVehicleId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAppointmentRescheduled(AppointmentRescheduledEvent event) {
        log.info("AppointmentRescheduled committed: appointmentId={}", event.appointmentId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAppointmentCancelled(AppointmentCancelledEvent event) {
        log.info("AppointmentCancelled committed: appointmentId={}", event.appointmentId());
    }
}
