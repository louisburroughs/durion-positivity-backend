package com.positivity.shopmanager.internal.client;

import com.positivity.shopmanager.internal.dto.PersonDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PersonClient {
    private final RestClient restClient;

    @Value("${people.service.url:http://localhost:8080/v1/people}")
    private String peopleServiceUrl;

    public PersonClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public PersonDTO getPersonById(Long id) {
        return restClient.get().uri(peopleServiceUrl + "/{id}", id).retrieve().body(PersonDTO.class);
    }
}
