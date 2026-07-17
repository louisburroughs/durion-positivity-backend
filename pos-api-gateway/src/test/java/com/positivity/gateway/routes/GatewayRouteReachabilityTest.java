package com.positivity.gateway.routes;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.gateway.filter.ApiVersionHeaderToPathFilter;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Route-reachability test (#923): boots the real gateway context (real {@code application.yml},
 * no stubs) and asserts that Spring Cloud Gateway resolves the {@code /warranty/**} route —
 * predicate, target service, and prefix stripping — exactly as production traffic would exercise
 * it. The {@code inventory} route is asserted with the same shape as the legacy-domain template.
 *
 * <p>External request flow being pinned (see {@link ApiVersionHeaderToPathFilter}):
 *
 * <pre>
 * Client:  GET /warranty/warranty/claims   (X-API-Version: 1)
 * Gateway: GET /warranty/v1/warranty/claims  (version header → path segment)
 * Service: GET /v1/warranty/claims           (route StripPrefix=1)
 * </pre>
 *
 * <p>Any of the realistic misconfigurations breaks a specific assertion here: a renamed service id
 * fails the {@code lb://} URI check, a wrong path prefix fails the predicate-match checks, and a
 * missing or wrong-count StripPrefix fails the executed-filter-chain path assertions.
 */
@SpringBootTest(
        properties = {
            // SecurityGatewayConfig needs an HS256-capable secret (>= 32 bytes) to construct.
            "security.jwt.secret=unit-test-only-jwt-secret-0123456789abcdef",
            // No Eureka in unit tests; lb:// URIs are asserted, never resolved.
            "eureka.client.enabled=false"
        })
class GatewayRouteReachabilityTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @Autowired
    private ApiVersionHeaderToPathFilter apiVersionFilter;

    // ------------------------------------------------------------------
    // warranty (#923)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("warranty route definition: lb://WARRANTY, Path=/warranty/**, StripPrefix=1")
    void warrantyRouteDefinitionIsConfigured() {
        RouteDefinition definition = routeDefinitionById("warranty");

        assertThat(definition.getUri()).hasToString("lb://WARRANTY");

        assertThat(definition.getPredicates()).hasSize(1);
        PredicateDefinition predicate = definition.getPredicates().get(0);
        assertThat(predicate.getName()).isEqualTo("Path");
        assertThat(predicate.getArgs()).containsValue("/warranty/**");

        assertThat(definition.getFilters()).hasSize(1);
        FilterDefinition filter = definition.getFilters().get(0);
        assertThat(filter.getName()).isEqualTo("StripPrefix");
        assertThat(filter.getArgs()).containsValue("1");
    }

    @Test
    @DisplayName("warranty predicate matches /warranty/** and nothing else")
    void warrantyPredicateMatchesOnlyWarrantyPaths() {
        Route warranty = routeById("warranty");

        assertThat(matches(warranty, exchangeFor("/warranty/v1/warranty/claims")))
                .as("versioned warranty path must be routed")
                .isTrue();
        assertThat(matches(warranty, exchangeFor("/warrantyx/v1/anything")))
                .as("prefix must match a full path segment, not a substring")
                .isFalse();
        assertThat(matches(warranty, exchangeFor("/inventory/v1/items/123")))
                .as("other domains must not be captured")
                .isFalse();
    }

    @Test
    @DisplayName("warranty route filters strip exactly one prefix segment")
    void warrantyRouteStripsExactlyOnePrefixSegment() {
        ServerWebExchange downstream =
                runRouteFilters(routeById("warranty"), exchangeFor("/warranty/v1/warranty/claims"));

        assertThat(downstream.getRequest().getURI().getRawPath()).isEqualTo("/v1/warranty/claims");
    }

    @Test
    @DisplayName("external GET /warranty/warranty/claims + X-API-Version:1 reaches WARRANTY as /v1/warranty/claims")
    void warrantyExternalRequestIsRewrittenAndReachable() {
        MockServerWebExchange external = MockServerWebExchange.from(
                MockServerHttpRequest.get("/warranty/warranty/claims").header("X-API-Version", "1"));

        ServerWebExchange versioned = applyApiVersionFilter(external);
        assertThat(versioned.getRequest().getURI().getRawPath())
                .as("X-API-Version header must be injected as a path segment")
                .isEqualTo("/warranty/v1/warranty/claims");

        Route warranty = routeById("warranty");
        assertThat(matches(warranty, versioned))
                .as("rewritten path must still match the warranty route")
                .isTrue();

        ServerWebExchange downstream = runRouteFilters(warranty, versioned);
        assertThat(downstream.getRequest().getURI().getRawPath())
                .as("WARRANTY controllers listen on /v1/warranty/**")
                .isEqualTo("/v1/warranty/claims");
    }

    // ------------------------------------------------------------------
    // inventory — legacy-domain template, same route shape
    // ------------------------------------------------------------------

    @Test
    @DisplayName("inventory route definition: lb://INVENTORY, Path=/inventory/**, StripPrefix=1")
    void inventoryRouteDefinitionIsConfigured() {
        RouteDefinition definition = routeDefinitionById("inventory");

        assertThat(definition.getUri()).hasToString("lb://INVENTORY");

        assertThat(definition.getPredicates()).hasSize(1);
        PredicateDefinition predicate = definition.getPredicates().get(0);
        assertThat(predicate.getName()).isEqualTo("Path");
        assertThat(predicate.getArgs()).containsValue("/inventory/**");

        assertThat(definition.getFilters()).hasSize(1);
        FilterDefinition filter = definition.getFilters().get(0);
        assertThat(filter.getName()).isEqualTo("StripPrefix");
        assertThat(filter.getArgs()).containsValue("1");
    }

    @Test
    @DisplayName("external GET /inventory/items/123 + X-API-Version:1 reaches INVENTORY as /v1/items/123")
    void inventoryExternalRequestIsRewrittenAndReachable() {
        MockServerWebExchange external = MockServerWebExchange.from(
                MockServerHttpRequest.get("/inventory/items/123").header("X-API-Version", "1"));

        ServerWebExchange versioned = applyApiVersionFilter(external);
        assertThat(versioned.getRequest().getURI().getRawPath()).isEqualTo("/inventory/v1/items/123");

        Route inventory = routeById("inventory");
        assertThat(matches(inventory, versioned)).isTrue();
        assertThat(matches(inventory, exchangeFor("/inventoryx/v1/items")))
                .as("prefix must match a full path segment, not a substring")
                .isFalse();

        ServerWebExchange downstream = runRouteFilters(inventory, versioned);
        assertThat(downstream.getRequest().getURI().getRawPath()).isEqualTo("/v1/items/123");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Route routeById(String id) {
        Route route = routeLocator
                .getRoutes()
                .filter(candidate -> id.equals(candidate.getId()))
                .blockFirst(TIMEOUT);
        assertThat(route)
                .as("route '%s' must be resolvable from application.yml", id)
                .isNotNull();
        return route;
    }

    private RouteDefinition routeDefinitionById(String id) {
        RouteDefinition definition = routeDefinitionLocator
                .getRouteDefinitions()
                .filter(candidate -> id.equals(candidate.getId()))
                .blockFirst(TIMEOUT);
        assertThat(definition)
                .as("route definition '%s' must be loaded from application.yml", id)
                .isNotNull();
        return definition;
    }

    private static MockServerWebExchange exchangeFor(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path));
    }

    /** Evaluates the route's real Path predicate against the exchange. */
    private static boolean matches(Route route, ServerWebExchange exchange) {
        return Boolean.TRUE.equals(
                Mono.from(route.getPredicate().apply(exchange)).block(TIMEOUT));
    }

    /**
     * Runs the exchange through the version-header rewrite exactly as the gateway does (the filter
     * runs at HIGHEST_PRECEDENCE, before route matching) and returns the mutated exchange handed to
     * the rest of the chain. If the filter short-circuits (e.g. 400), the original exchange is
     * returned and the caller's path assertion fails.
     */
    private ServerWebExchange applyApiVersionFilter(ServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>(exchange);
        apiVersionFilter
                .filter(exchange, mutated -> {
                    downstream.set(mutated);
                    return Mono.empty();
                })
                .block(TIMEOUT);
        return downstream.get();
    }

    /**
     * Executes the route's actual resolved {@link GatewayFilter}s (e.g. StripPrefix) and captures
     * the exchange that would be forwarded downstream to the lb:// target.
     */
    private static ServerWebExchange runRouteFilters(Route route, ServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>(exchange);
        GatewayFilterChain chain = forwarded -> {
            downstream.set(forwarded);
            return Mono.empty();
        };
        List<GatewayFilter> filters = route.getFilters();
        for (int i = filters.size() - 1; i >= 0; i--) {
            GatewayFilter filter = filters.get(i);
            GatewayFilterChain next = chain;
            chain = current -> filter.filter(current, next);
        }
        chain.filter(exchange).block(TIMEOUT);
        return downstream.get();
    }
}
