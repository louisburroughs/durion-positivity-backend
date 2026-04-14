package com.positivity.shopmanager.service;

public interface BayService {

    Object getBays(Long locationId, Long bayId);

    Object createBay(Long locationId, Object request);

    Object manageBays(Object request);

    void deleteBay(Long locationId, Long bayId);
}
