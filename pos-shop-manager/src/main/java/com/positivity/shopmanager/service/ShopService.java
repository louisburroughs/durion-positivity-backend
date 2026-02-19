package com.positivity.shopmanager.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.positivity.shopmanager.internal.client.PersonClient;
import com.positivity.shopmanager.internal.client.ServiceEntityClient;
import com.positivity.shopmanager.internal.dto.PersonDTO;
import com.positivity.shopmanager.internal.dto.ServiceEntityDTO;
import com.positivity.shopmanager.internal.entity.Technician;
import com.positivity.shopmanager.internal.repository.ShopServiceRepository;
import com.positivity.shopmanager.internal.repository.TechnicianRepository;

@RequiredArgsConstructor
@Slf4j
@Service
public class ShopService {

    private final TechnicianRepository technicianRepository;
    private final PersonClient personClient;
    private final ServiceEntityClient serviceEntityClient;
    private final ShopServiceRepository shopServiceRepository;

    public PersonDTO getTechnicianPerson(UUID locationId, UUID technicianId) {
        Technician tech = technicianRepository.findByIdAndShopId(technicianId, locationId).orElseThrow();
        if (tech.getPersonId() == null)
            return null;
        return personClient.getPersonById(tech.getPersonId());
    }

    public ServiceEntityDTO getShopServiceDetails(UUID locationId, UUID shopServiceId) {
        com.positivity.shopmanager.internal.entity.ShopService shopService = shopServiceRepository
                .findByIdAndShopId(shopServiceId, locationId).orElseThrow();
        if (shopService.getServiceEntityId() == null)
            return null;
        return serviceEntityClient.getServiceById(shopService.getServiceEntityId());
    }
}
