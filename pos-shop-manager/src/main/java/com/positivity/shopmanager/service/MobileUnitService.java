package com.positivity.shopmanager.service;

public interface MobileUnitService {

    Object getMobileUnits(Long locationId, Long bayId);

    Object createMobileUnit(Long locationId, Object request);

    Object manageMobileUnits(Object request);

    void deleteMobileUnit(Long locationId, Long bayId);
}
