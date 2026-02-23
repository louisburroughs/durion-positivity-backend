package com.positivity.location.internal.service;

import com.positivity.location.internal.repository.ServiceAreaRepository;
import com.positivity.location.service.ServiceAreaService;
import org.springframework.stereotype.Service;

/**
 * Spring service implementation for service area APIs.
 *
 * Issue: #76
 */
@Service
public class ServiceAreaServiceImpl extends ServiceAreaService {

    public ServiceAreaServiceImpl(ServiceAreaRepository serviceAreaRepository) {
        super(serviceAreaRepository);
    }
}
