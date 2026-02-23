package com.positivity.location.service;

import com.positivity.location.internal.dto.TravelBufferPolicyRequest;
import com.positivity.location.internal.dto.TravelBufferPolicyResponse;
import java.util.List;
import java.util.Map;

public interface TravelBufferPolicyService {

    TravelBufferPolicyResponse create(Map<String, Object> request);

    TravelBufferPolicyResponse create(TravelBufferPolicyRequest request);

    TravelBufferPolicyResponse patch(String id, Map<String, Object> patch);

    List<TravelBufferPolicyResponse> list();
}
