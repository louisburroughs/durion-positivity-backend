package com.positivity.location.internal.service;

import com.positivity.location.internal.repository.TravelBufferPolicyRepository;
import com.positivity.location.service.TravelBufferPolicyService;
import org.springframework.stereotype.Service;

/**
 * Spring service implementation for travel buffer policy APIs.
 *
 * Issue: #76
 */
@Service
public class TravelBufferPolicyServiceImpl extends TravelBufferPolicyService {

    public TravelBufferPolicyServiceImpl(TravelBufferPolicyRepository repository) {
        super(repository);
    }
}
