package com.positivity.shopmanager.internal.client;

import com.positivity.shopmanager.internal.dto.ServiceEntityDTO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ServiceEntityClient {
    private final RestClient restClient;

    public ServiceEntityClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder,
            @Value("${pos.catalog.service-id:catalog}") String serviceId) {
        this.restClient = builder.baseUrl("http://" + serviceId)
                .defaultHeader("X-User", "pos-shop-manager")
                .defaultHeader("X-Authorities", "catalog:product:read")
                .build();
    }

    public ServiceEntityDTO getServiceById(Long id) {
        return restClient.get().uri("/v1/services/{id}", id).retrieve().body(ServiceEntityDTO.class);
    }
}
