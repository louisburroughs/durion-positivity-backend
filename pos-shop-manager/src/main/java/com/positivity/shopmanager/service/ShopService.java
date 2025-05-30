package com.positivity.shopmanager.service;

import com.positivity.shopmanager.client.PersonClient;
import com.positivity.shopmanager.client.ServiceEntityClient;
import com.positivity.shopmanager.dto.PersonDTO;
import com.positivity.shopmanager.dto.ServiceEntityDTO;
import com.positivity.shopmanager.entity.Technician;
import com.positivity.shopmanager.repository.TechnicianRepository;
import com.positivity.shopmanager.repository.ShopServiceRepository;
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
        com.positivity.shopmanager.entity.ShopService shopService = shopServiceRepository.findById(shopServiceId).orElseThrow();
        if (shopService.getServiceEntityId() == null) return null;
        return serviceEntityClient.getServiceById(shopService.getServiceEntityId());
    }
}
