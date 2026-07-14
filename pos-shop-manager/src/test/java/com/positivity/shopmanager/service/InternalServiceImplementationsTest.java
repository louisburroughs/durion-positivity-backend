package com.positivity.shopmanager.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.positivity.shopmanager.internal.dto.AppointmentCreateModel;
import com.positivity.shopmanager.internal.service.AppointmentLoadServiceImpl;
import com.positivity.shopmanager.internal.service.ConflictDetectionServiceImpl;
import com.positivity.shopmanager.internal.service.SourceEligibilityServiceImpl;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InternalServiceImplementationsTest {

    @Test
    void sourceEligibilityService_returnsExpectedStubValues() {
        SourceEligibilityServiceImpl service = new SourceEligibilityServiceImpl();

        service.validateEstimateEligibility("EST-1", "FAC-1");
        service.validateWorkOrderEligibility("WO-1", "FAC-1");

        assertEquals("APPROVED", service.getEstimateStatus("EST-1", "FAC-1"));
        assertEquals("OPEN", service.getWorkOrderStatus("WO-1", "FAC-1"));
        assertNull(service.getExistingAppointmentId("ESTIMATE", "EST-1", "FAC-1"));
    }

    @Test
    void conflictDetectionService_returnsExpectedDefaults() {
        ConflictDetectionServiceImpl service = new ConflictDetectionServiceImpl();

        service.detectConflicts(null, UUID.fromString("00000000-0000-0000-0000-000000000001"));

        assertTrue(service.isWithinOperatingHours("FAC-1", "start", "end", "UTC"));
        assertTrue(service.checkMechanicAvailability("FAC-1", "start", "end").isEmpty());
        assertTrue(service.checkBayAvailability("FAC-1", "start", "end").isEmpty());
    }

    @Test
    void appointmentLoadService_returnsCreateModelAndUtcTimeZone() {
        AppointmentLoadServiceImpl service = new AppointmentLoadServiceImpl();
        UUID facilityId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        AppointmentCreateModel model = service.loadCreateModel(
                "ESTIMATE", "SRC-1", facilityId, UUID.fromString("00000000-0000-0000-0000-000000000001"));

        assertNotNull(model);
        assertEquals("UTC", service.getFacilityTimeZoneId(facilityId));
    }
}
