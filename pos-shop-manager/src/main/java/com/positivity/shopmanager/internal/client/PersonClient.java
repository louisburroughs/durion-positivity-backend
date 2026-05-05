package com.positivity.shopmanager.internal.client;

import com.positivity.shopmanager.internal.dto.PersonDTO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PersonClient {
    private final RestClient restClient;

    @Value("${people.service.url:http://api-gateway}")
    private String peopleServiceUrl;

    public PersonClient(@Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    public PersonDTO getPersonById(Long id) {
        return restClient.get().uri(peopleServiceUrl + "/people/v1/people/{id}", id).retrieve().body(PersonDTO.class);
    }
}
