package com.positivity.shopmanager.internal.client;

import com.positivity.shopmanager.internal.dto.ServiceEntityDTO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ServiceEntityClient {
    private final RestClient restClient;

    @Value("${catalog.service.url:http://api-gateway}")
    private String catalogServiceUrl;

    public ServiceEntityClient(@Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    public ServiceEntityDTO getServiceById(Long id) {
        return restClient
                .get()
                .uri(catalogServiceUrl + "/catalog/v1/services/{id}", id)
                .retrieve()
                .body(ServiceEntityDTO.class);
    }
}
