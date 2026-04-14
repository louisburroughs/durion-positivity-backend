package com.positivity.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class ApiVersionHeaderToPathFilterTest {

    private final ApiVersionHeaderToPathFilter filter = new ApiVersionHeaderToPathFilter();

    @Test
    void shouldRejectRequestWhenVersionHeaderMissing() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/customer/crm/accounts").build());
        AtomicReference<String> downstreamPath = new AtomicReference<>();
        GatewayFilterChain chain = chainedExchange -> {
            downstreamPath.set(chainedExchange
                    .getRequest()
                    .getPath()
                    .pathWithinApplication()
                    .value());
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(downstreamPath.get()).isNull();
    }

    @Test
    void shouldRejectRequestWhenVersionHeaderIsInvalid() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/customer/crm/accounts")
                .header("X-API-Version", "v1")
                .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldRewritePathToServiceScopedVersionedPath() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/customer/crm/accounts")
                .header("X-API-Version", "1")
                .build());
        AtomicReference<String> downstreamPath = new AtomicReference<>();
        GatewayFilterChain chain = chainedExchange -> {
            downstreamPath.set(chainedExchange
                    .getRequest()
                    .getPath()
                    .pathWithinApplication()
                    .value());
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(downstreamPath.get()).isEqualTo("/customer/v1/crm/accounts");
    }

    @Test
    void shouldBypassActuatorPathWithoutVersionHeader() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health").build());
        AtomicReference<String> downstreamPath = new AtomicReference<>();
        GatewayFilterChain chain = chainedExchange -> {
            downstreamPath.set(chainedExchange
                    .getRequest()
                    .getPath()
                    .pathWithinApplication()
                    .value());
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(downstreamPath.get()).isEqualTo("/actuator/health");
    }

    @Test
    void shouldBypassAuthLoginPathWithoutVersionHeader() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/security-service/v1/auth/login").build());
        AtomicReference<String> downstreamPath = new AtomicReference<>();
        GatewayFilterChain chain = chainedExchange -> {
            downstreamPath.set(chainedExchange
                    .getRequest()
                    .getPath()
                    .pathWithinApplication()
                    .value());
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(downstreamPath.get()).isEqualTo("/security-service/v1/auth/login");
    }

    @Test
    void shouldNotRewriteAlreadyVersionedPath() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/customer/v1/crm/accounts").build());
        AtomicReference<String> downstreamPath = new AtomicReference<>();
        GatewayFilterChain chain = chainedExchange -> {
            downstreamPath.set(chainedExchange
                    .getRequest()
                    .getPath()
                    .pathWithinApplication()
                    .value());
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(downstreamPath.get()).isEqualTo("/customer/v1/crm/accounts");
    }
}
