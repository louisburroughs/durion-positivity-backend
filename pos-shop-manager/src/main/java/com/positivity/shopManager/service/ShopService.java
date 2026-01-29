package com.positivity.shopManager.service;

import com.positivity.shopManager.internal.client.PersonClient;
import com.positivity.shopManager.internal.client.ServiceEntityClient;
import com.positivity.shopManager.internal.dto.PersonDTO;
import com.positivity.shopManager.internal.dto.ServiceEntityDTO;
import com.positivity.shopManager.internal.entity.Technician;
import com.positivity.shopManager.internal.repository.TechnicianRepository;
import com.positivity.shopManager.internal.repository.ShopServiceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Slf4j
@Service
public class ShopService {

    private final TechnicianRepository technicianRepository;
    private final PersonClient personClient;
    private final ServiceEntityClient serviceEntityClient;
    private final ShopServiceRepository shopServiceRepository;

    public PersonDTO getTechnicianPerson(Long locationId, Long technicianId) {
        Technician tech = technicianRepository.findByIdAndShopId(technicianId, locationId).orElseThrow();
        if (tech.getPersonId() == null)
            return null;
        return personClient.getPersonById(tech.getPersonId());
    }

    public ServiceEntityDTO getShopServiceDetails(Long locationId, Long shopServiceId) {
        com.positivity.shopManager.internal.entity.ShopService shopService = shopServiceRepository
                .findByIdAndShopId(shopServiceId, locationId).orElseThrow();
        if (shopService.getServiceEntityId() == null)
            return null;
        return serviceEntityClient.getServiceById(shopService.getServiceEntityId());
    }
}
