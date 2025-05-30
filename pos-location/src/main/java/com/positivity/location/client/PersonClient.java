package com.positivity.location.client;

import com.positivity.location.dto.PersonDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
public class PersonClient {
    private final RestTemplate restTemplate;

    @Value("${people.service.url:http://localhost:8080/api/people}")
    private String peopleServiceUrl;

    public PersonDTO getPersonById(Long id) {
        return restTemplate.getForObject(peopleServiceUrl + "/" + id, PersonDTO.class);
    }
}

