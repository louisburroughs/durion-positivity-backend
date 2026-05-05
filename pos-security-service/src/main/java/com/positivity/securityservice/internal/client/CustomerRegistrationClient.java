package com.positivity.securityservice.internal.client;

import com.positivity.securityservice.internal.client.dto.CustomerPersonSearchResponse;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class CustomerRegistrationClient {

    private final RestClient restClient;

    public CustomerRegistrationClient(@Qualifier("customerRegistrationRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @NonNull
    public List<CustomerPersonSearchResponse> searchPersons(
            @Nullable String name, @Nullable String email, @Nullable String phone) {
        List<CustomerPersonSearchResponse> response = restClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/customer/v1/crm/persons")
                        .queryParamIfPresent("name", java.util.Optional.ofNullable(name))
                        .queryParamIfPresent("email", java.util.Optional.ofNullable(email))
                        .queryParamIfPresent("phone", java.util.Optional.ofNullable(phone))
                        .queryParam("limit", 10)
                        .queryParam("offset", 0)
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                    throw new IllegalStateException("Customer person search failed with status "
                            + res.getStatusCode().value());
                })
                .body(new ParameterizedTypeReference<List<CustomerPersonSearchResponse>>() {
                });
        return response == null ? List.of() : response;
    }
}
