package com.positivity.shopmanager.service;

import java.util.UUID;

import com.positivity.shopmanager.internal.dto.PersonDTO;
import com.positivity.shopmanager.internal.dto.ServiceEntityDTO;

public interface ShopService {

    PersonDTO getTechnicianPerson(UUID locationId, UUID technicianId);

    ServiceEntityDTO getShopServiceDetails(UUID locationId, UUID shopServiceId);

}