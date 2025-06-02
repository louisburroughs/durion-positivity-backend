package com.positivity.shopManager.client;

import com.positivity.shopManager.dto.ServiceEntityDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class ServiceEntityClient {
    private final RestTemplate restTemplate;

    @Value("${catalog.service.url:http://localhost:8080/api/services}")
    private String catalogServiceUrl;

    public ServiceEntityClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public ServiceEntityDTO getServiceById(Long id) {
        return restTemplate.getForObject(catalogServiceUrl + "/" + id, ServiceEntityDTO.class);
    }
}

