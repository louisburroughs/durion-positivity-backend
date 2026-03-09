package com.positivity.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class SecurityGatewayConfigTest {

    @Test
    void shouldEnrichHeadersAndForwardBearerForIntrospectionCalls() {
        List<ClientRequest> securityCalls = new ArrayList<>();
        WebClient securityWebClient = WebClient.builder()
                .exchangeFunction(securityExchangeFunction(securityCalls))
                .build();

        GlobalFilter filter = new SecurityGatewayConfig(false, Set.of("HS256")).authFilter(securityWebClient);
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/people")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                        .build());

        AtomicReference<HttpHeaders> downstreamHeaders = new AtomicReference<>();
        GatewayFilterChain chain = chainedExchange -> {
            downstreamHeaders.set(chainedExchange.getRequest().getHeaders());
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(downstreamHeaders.get()).isNotNull();
        assertThat(Arrays.asList(downstreamHeaders.get().getFirst("X-Authorities").split(",")))
                .containsExactlyInAnyOrder("people:person:view", "people:person:create");
        assertThat(downstreamHeaders.get().getFirst("X-User")).isEqualTo("alice");
        assertThat(downstreamHeaders.get().getFirst("X-User-Id")).isNull();

        assertThat(securityCalls).hasSize(3);
        assertThat(securityCalls)
                .allMatch(call -> "Bearer test-token".equals(call.headers().getFirst(HttpHeaders.AUTHORIZATION)));
    }

    @Test
    void shouldReturnUnauthorizedWhenAuthorizationHeaderMissing() {
        List<ClientRequest> securityCalls = new ArrayList<>();
        WebClient securityWebClient = WebClient.builder()
                .exchangeFunction(securityExchangeFunction(securityCalls))
                .build();

        GlobalFilter filter = new SecurityGatewayConfig(false, Set.of("HS256")).authFilter(securityWebClient);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/people/v1/people").build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(securityCalls).isEmpty();
    }

    @Test
    void shouldRejectAlgNoneTokenBeforeCallingSecurityServiceWhenStrictModeEnabled() {
        List<ClientRequest> securityCalls = new ArrayList<>();
        WebClient securityWebClient = WebClient.builder()
                .exchangeFunction(securityExchangeFunction(securityCalls))
                .build();

        GlobalFilter filter = new SecurityGatewayConfig(true, Set.of("HS256")).authFilter(securityWebClient);
        String noneAlgToken = tokenWith("{\"alg\":\"none\"}", "{\"sub\":\"alice\"}", "irrelevant");
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/people")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + noneAlgToken)
                        .build());

        AtomicReference<HttpHeaders> downstreamHeaders = new AtomicReference<>();
        GatewayFilterChain chain = chainedExchange -> {
            downstreamHeaders.set(chainedExchange.getRequest().getHeaders());
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(securityCalls).isEmpty();
        assertThat(downstreamHeaders.get()).isNull();
    }

    @Test
    void shouldRejectSyntheticSignatureMarkerBeforeCallingSecurityServiceWhenStrictModeEnabled() {
        List<ClientRequest> securityCalls = new ArrayList<>();
        WebClient securityWebClient = WebClient.builder()
                .exchangeFunction(securityExchangeFunction(securityCalls))
                .build();

        GlobalFilter filter = new SecurityGatewayConfig(true, Set.of("HS256")).authFilter(securityWebClient);
        String syntheticToken = tokenWith("{\"alg\":\"HS256\"}", "{\"sub\":\"alice\"}", "test-signature-not-verified");
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/people")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + syntheticToken)
                        .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(securityCalls).isEmpty();
    }

    @Test
    void shouldAllowSyntheticSignatureMarkerWhenStrictModeDisabled() {
        List<ClientRequest> securityCalls = new ArrayList<>();
        WebClient securityWebClient = WebClient.builder()
                .exchangeFunction(securityExchangeFunction(securityCalls))
                .build();

        GlobalFilter filter = new SecurityGatewayConfig(false, Set.of("HS256")).authFilter(securityWebClient);
        String syntheticToken = tokenWith("{\"alg\":\"HS256\"}", "{\"sub\":\"alice\"}", "test-signature-not-verified");
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/people")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + syntheticToken)
                        .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(securityCalls).hasSize(3);
    }

    private static ExchangeFunction securityExchangeFunction(List<ClientRequest> captured) {
        return request -> {
            captured.add(request);
            String path = request.url().getPath();
            if ("/v1/auth/validate".equals(path)) {
                return Mono.just(jsonResponse("{\"valid\":true}"));
            }
            if ("/v1/auth/authorities".equals(path)) {
                return Mono.just(jsonResponse("[\"people:person:view\",\"people:person:create\"]"));
            }
            if ("/v1/auth/subject".equals(path)) {
                return Mono.just(textResponse("alice"));
            }
            return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).build());
        };
    }

    private static ClientResponse jsonResponse(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    private static ClientResponse textResponse(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                .body(body)
                .build();
    }

    private static String tokenWith(String headerJson, String payloadJson, String signature) {
        return base64Url(headerJson) + "." + base64Url(payloadJson) + "." + signature;
    }

    private static String base64Url(String value) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
