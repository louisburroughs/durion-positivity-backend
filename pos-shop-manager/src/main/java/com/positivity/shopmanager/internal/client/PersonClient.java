package com.positivity.shopmanager.internal.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.positivity.shopmanager.internal.dto.PersonDTO;

@Component
public class PersonClient {
    private final RestClient restClient;

    @Value("${people.service.url:http://localhost:8080/api/people}")
    private String peopleServiceUrl;

    public PersonClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public PersonDTO getPersonById(Long id) {
        return restClient.get()
                .uri(peopleServiceUrl + "/{id}", id)
                .retrieve()
                .body(PersonDTO.class);
    }
}
