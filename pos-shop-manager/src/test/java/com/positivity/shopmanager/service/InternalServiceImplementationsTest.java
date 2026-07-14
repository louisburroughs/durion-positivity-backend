package com.positivity.shopmanager.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.shopmanager.internal.client.ServiceEntityClient;
import com.positivity.shopmanager.internal.dto.AppointmentCreateModel;
import com.positivity.shopmanager.internal.dto.PersonDTO;
import com.positivity.shopmanager.internal.dto.ServiceEntityDTO;
import com.positivity.shopmanager.internal.entity.ShopServiceEntry;
import com.positivity.shopmanager.internal.entity.Technician;
import com.positivity.shopmanager.internal.repository.ShopServiceRepository;
import com.positivity.shopmanager.internal.repository.TechnicianRepository;
import com.positivity.shopmanager.internal.service.AppointmentLoadServiceImpl;
import com.positivity.shopmanager.internal.service.ConflictDetectionServiceImpl;
import com.positivity.shopmanager.internal.service.ShopServiceImpl;
import com.positivity.shopmanager.internal.service.SourceEligibilityServiceImpl;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InternalServiceImplementationsTest {

    @Mock
    private TechnicianRepository technicianRepository;

    @Mock
    private ServiceEntityClient serviceEntityClient;

    @Mock
    private ShopServiceRepository shopServiceRepository;

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

    @Test
    void shopService_getTechnicianPerson_returnsNullWhenPersonIdMissing() {
        ShopServiceImpl service = new ShopServiceImpl(technicianRepository, serviceEntityClient, shopServiceRepository);
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID technicianId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Technician technician = new Technician();
        technician.setId(technicianId);
        technician.setPersonId(null);
        when(technicianRepository.findByIdAndShopId(technicianId, locationId)).thenReturn(Optional.of(technician));

        PersonDTO result = service.getTechnicianPerson(locationId, technicianId);

        assertNull(result);
    }

    @Test
    void shopService_getTechnicianPerson_legacyLongId_returnsNull() {
        ShopServiceImpl service = new ShopServiceImpl(technicianRepository, serviceEntityClient, shopServiceRepository);
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID technicianId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Technician technician = new Technician();
        technician.setId(technicianId);
        technician.setPersonId(42L);
        when(technicianRepository.findByIdAndShopId(technicianId, locationId)).thenReturn(Optional.of(technician));

        // Legacy Long ids predate UUIDv7 person ids and cannot resolve a person (#877).
        assertNull(service.getTechnicianPerson(locationId, technicianId));
    }

    @Test
    void shopService_getTechnicianPerson_throwsWhenTechnicianMissing() {
        ShopServiceImpl service = new ShopServiceImpl(technicianRepository, serviceEntityClient, shopServiceRepository);
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID technicianId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(technicianRepository.findByIdAndShopId(technicianId, locationId)).thenReturn(Optional.empty());

        assertThrows(
                java.util.NoSuchElementException.class, () -> service.getTechnicianPerson(locationId, technicianId));
    }

    @Test
    void shopService_getShopServiceDetails_returnsNullWhenServiceEntityIdMissing() {
        ShopServiceImpl service = new ShopServiceImpl(technicianRepository, serviceEntityClient, shopServiceRepository);
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID shopServiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ShopServiceEntry shopService = new ShopServiceEntry();
        shopService.setId(shopServiceId);
        shopService.setServiceEntityId(null);
        when(shopServiceRepository.findByIdAndShopId(shopServiceId, locationId)).thenReturn(Optional.of(shopService));

        ServiceEntityDTO result = service.getShopServiceDetails(locationId, shopServiceId);

        assertNull(result);
    }

    @Test
    void shopService_getShopServiceDetails_returnsServiceEntityWhenPresent() {
        ShopServiceImpl service = new ShopServiceImpl(technicianRepository, serviceEntityClient, shopServiceRepository);
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID shopServiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ShopServiceEntry shopService = new ShopServiceEntry();
        shopService.setId(shopServiceId);
        shopService.setServiceEntityId(77L);
        ServiceEntityDTO expected = new ServiceEntityDTO();
        expected.setId(77L);
        when(shopServiceRepository.findByIdAndShopId(shopServiceId, locationId)).thenReturn(Optional.of(shopService));
        when(serviceEntityClient.getServiceById(77L)).thenReturn(expected);

        ServiceEntityDTO result = service.getShopServiceDetails(locationId, shopServiceId);

        assertEquals(expected, result);
    }

    @Test
    void shopService_getShopServiceDetails_throwsWhenShopServiceMissing() {
        ShopServiceImpl service = new ShopServiceImpl(technicianRepository, serviceEntityClient, shopServiceRepository);
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID shopServiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(shopServiceRepository.findByIdAndShopId(shopServiceId, locationId)).thenReturn(Optional.empty());

        assertThrows(
                java.util.NoSuchElementException.class, () -> service.getShopServiceDetails(locationId, shopServiceId));
    }
}
