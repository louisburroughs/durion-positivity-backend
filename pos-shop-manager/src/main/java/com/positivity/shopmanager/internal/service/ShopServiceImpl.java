package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.client.ServiceEntityClient;
import com.positivity.shopmanager.internal.dto.PersonDTO;
import com.positivity.shopmanager.internal.dto.ServiceEntityDTO;
import com.positivity.shopmanager.internal.repository.ShopServiceRepository;
import com.positivity.shopmanager.internal.repository.TechnicianRepository;
import com.positivity.shopmanager.service.ShopService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Slf4j
@Service
public class ShopServiceImpl implements ShopService {

    private final TechnicianRepository technicianRepository;
    private final ServiceEntityClient serviceEntityClient;
    private final ShopServiceRepository shopServiceRepository;

    @Override
    public PersonDTO getTechnicianPerson(UUID locationId, UUID technicianId) {
        // Legacy: technician.person_id is a Long that predates UUIDv7 person ids, so it can
        // never address a real person — the retired sync lookup failed for every non-null id.
        // Returns 404 until the column is migrated to the UUID person id and this reads the
        // people-contact replica (follow-up filed with #877).
        technicianRepository.findByIdAndShopId(technicianId, locationId).orElseThrow();
        return null;
    }

    @Override
    public ServiceEntityDTO getShopServiceDetails(UUID locationId, UUID shopServiceId) {
        com.positivity.shopmanager.internal.entity.ShopServiceEntry shopService = shopServiceRepository
                .findByIdAndShopId(shopServiceId, locationId)
                .orElseThrow();
        if (shopService.getServiceEntityId() == null) return null;
        return serviceEntityClient.getServiceById(shopService.getServiceEntityId());
    }
}
