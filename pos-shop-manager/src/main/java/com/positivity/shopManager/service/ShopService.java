package com.positivity.shopManager.service;

import com.positivity.shopManager.client.PersonClient;
import com.positivity.shopManager.client.ServiceEntityClient;
import com.positivity.shopManager.dto.PersonDTO;
import com.positivity.shopManager.dto.ServiceEntityDTO;
import com.positivity.shopManager.entity.Technician;
import com.positivity.shopManager.repository.TechnicianRepository;
import com.positivity.shopManager.repository.ShopServiceRepository;
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

    public PersonDTO getTechnicianPerson(Long technicianId) {
        Technician tech = technicianRepository.findById(technicianId).orElseThrow();
        if (tech.getPersonId() == null) return null;
        return personClient.getPersonById(tech.getPersonId());
    }

    public ServiceEntityDTO getShopServiceDetails(Long shopServiceId) {
        com.positivity.shopManager.entity.ShopService shopService = shopServiceRepository.findById(shopServiceId).orElseThrow();
        if (shopService.getServiceEntityId() == null) return null;
        return serviceEntityClient.getServiceById(shopService.getServiceEntityId());
    }
}
