package com.positivity.location.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

class PersonClientTest {

    private static final String BASE_URL = "http://api-gateway";

    private MockRestServiceServer mockServer;
    private PersonClient personClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        personClient = new PersonClient(builder, BASE_URL);
    }

    @Test
    void getPersonByIdReturnsNullWhenPeopleServiceReturnsNotFound() {
        mockServer
                .expect(requestTo(BASE_URL + "/people/v1/people/42"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(personClient.getPersonById(42L)).isNull();
        mockServer.verify();
    }

    @Test
    void getPersonByIdThrowsRuntimeExceptionWhenPeopleServiceFails() {
        mockServer
                .expect(requestTo(BASE_URL + "/people/v1/people/42"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> personClient.getPersonById(42L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to fetch person from People service")
                .hasCauseInstanceOf(HttpServerErrorException.InternalServerError.class);
        mockServer.verify();
    }
}
