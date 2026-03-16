package com.positivity.securityservice.internal.client;

import com.positivity.securityservice.internal.client.dto.PeopleLinkUserRequest;
import com.positivity.securityservice.internal.client.dto.PeopleResolvePersonRequest;
import com.positivity.securityservice.internal.client.dto.PeopleResolvePersonResponse;
import com.positivity.securityservice.internal.client.dto.PeopleUserLinkResponse;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PeopleRegistrationClient {

    private final RestClient restClient;

    public PeopleRegistrationClient(@Qualifier("peopleRegistrationRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @NonNull
    public PeopleResolvePersonResponse resolvePerson(@NonNull PeopleResolvePersonRequest request) {
        PeopleResolvePersonResponse response = restClient.post()
                .uri("/v1/people/resolve")
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new IllegalStateException("People resolve request failed with status " + res.getStatusCode().value());
                })
                .body(PeopleResolvePersonResponse.class);
        if (response == null) {
            throw new IllegalStateException("People resolve returned null response");
        }
        return response;
    }

    @NonNull
    public List<UUID> getLinkedUserIds(@NonNull UUID personId) {
        List<UUID> response = restClient.get()
                .uri("/v1/people/{personId}/users", personId)
                .retrieve()
                .onStatus(statusCode -> statusCode.value() == 404, (req, res) -> {
                    throw new IllegalStateException("Resolved person no longer exists for linking");
                })
                .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                    throw new IllegalStateException("People linked-user lookup failed with status " + res.getStatusCode().value());
                })
                .body(new ParameterizedTypeReference<List<UUID>>() {
                });
        return response == null ? List.of() : response;
    }

    @NonNull
    public PeopleUserLinkResponse linkUserToPerson(@NonNull PeopleLinkUserRequest request) {
        PeopleUserLinkResponse response = restClient.post()
                .uri("/v1/people/users/link")
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new IllegalStateException("People link request failed with status " + res.getStatusCode().value());
                })
                .body(PeopleUserLinkResponse.class);
        if (response == null) {
            throw new IllegalStateException("People link returned null response");
        }
        return response;
    }

    public void deletePerson(@NonNull UUID personId) {
        restClient.delete()
                .uri("/v1/people/{personId}", personId)
                .retrieve()
                .onStatus(statusCode -> statusCode.value() == 404, (req, res) -> {
                    throw new IllegalStateException("Created person no longer exists for compensation");
                })
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new IllegalStateException("People delete request failed with status " + res.getStatusCode().value());
                })
                .toBodilessEntity();
    }
}
