package com.positivity.shopManager.internal.client;

import com.positivity.shopManager.internal.dto.ServiceEntityDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
