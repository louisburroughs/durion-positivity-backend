package com.positivity.shopmanager.service;

import com.positivity.shopmanager.internal.dto.PersonDTO;
import com.positivity.shopmanager.internal.dto.ServiceEntityDTO;
import java.util.UUID;

public interface ShopService {

    PersonDTO getTechnicianPerson(UUID locationId, UUID technicianId);

    ServiceEntityDTO getShopServiceDetails(UUID locationId, UUID shopServiceId);
}
