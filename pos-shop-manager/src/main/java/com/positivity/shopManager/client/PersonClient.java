package com.positivity.shopManager.client;

import com.positivity.shopManager.dto.PersonDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class PersonClient {
    private final RestTemplate restTemplate;

    @Value("${people.service.url:http://localhost:8080/api/people}")
    private String peopleServiceUrl;

    public PersonClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public PersonDTO getPersonById(Long id) {
        return restTemplate.getForObject(peopleServiceUrl + "/" + id, PersonDTO.class);
    }
}

