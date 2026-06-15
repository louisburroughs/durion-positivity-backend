package com.positivity.shopmanager.internal.client;

import com.positivity.shopmanager.internal.dto.PersonDTO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PersonClient {
    private final RestClient restClient;

    public PersonClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder,
            @Value("${pos.people.service-id:people}") String serviceId) {
        this.restClient = builder.baseUrl("http://" + serviceId)
                .defaultHeader("X-User", "pos-shop-manager")
                .defaultHeader("X-Authorities", "people:person:view")
                .build();
    }

    public PersonDTO getPersonById(Long id) {
        return restClient.get().uri("/v1/people/{id}", id).retrieve().body(PersonDTO.class);
    }
}
