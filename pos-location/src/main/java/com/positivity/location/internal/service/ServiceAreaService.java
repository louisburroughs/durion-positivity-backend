package com.positivity.location.internal.service;

import com.positivity.location.internal.dto.ServiceAreaPostalCodesRequest;
import com.positivity.location.internal.dto.ServiceAreaRequest;
import com.positivity.location.internal.dto.ServiceAreaResponse;
import java.util.List;
import java.util.Map;

public interface ServiceAreaService {

    ServiceAreaResponse create(Map<String, Object> request);

    ServiceAreaResponse create(ServiceAreaRequest request);

    ServiceAreaResponse patch(String id, Map<String, Object> patch);

    ServiceAreaResponse replacePostalCodes(String id, ServiceAreaPostalCodesRequest request);

    List<ServiceAreaResponse> list();
}
