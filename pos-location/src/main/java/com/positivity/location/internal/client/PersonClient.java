package com.positivity.location.internal.client;

import com.positivity.location.internal.dto.PersonDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class PersonClient {

    private final RestClient restClient;

    public PersonClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.people.service-id:people}") String serviceId) {
        this.restClient = restClientBuilder.baseUrl("http://" + serviceId).build();
    }

    public PersonDTO getPersonById(Long id) {
        try {
            return restClient
                    .get()
                    .uri("/v1/people/{id}", id)
                    .header("X-Authorities", "people:person:view")
                    .header("X-User", "pos-location")
                    .retrieve()
                    .body(PersonDTO.class);
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("Person not found: id={}", id);
            return null;
        } catch (Exception e) {
            log.error("Failed to fetch person {}: {}", id, e.getMessage(), e);
            throw new RuntimeException("Failed to fetch person from People service: " + e.getMessage(), e);
        }
    }
}
