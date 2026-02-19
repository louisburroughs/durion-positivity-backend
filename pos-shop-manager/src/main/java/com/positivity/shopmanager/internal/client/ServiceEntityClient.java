package com.positivity.shopmanager.internal.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.positivity.shopmanager.internal.dto.ServiceEntityDTO;

@Component
public class ServiceEntityClient {
    private final RestClient restClient;

    @Value("${catalog.service.url:http://localhost:8080/api/services}")
    private String catalogServiceUrl;

    public ServiceEntityClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public ServiceEntityDTO getServiceById(Long id) {
        return restClient.get()
                .uri(catalogServiceUrl + "/{id}", id)
                .retrieve()
                .body(ServiceEntityDTO.class);
    }
}
