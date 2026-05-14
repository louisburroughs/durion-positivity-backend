# Issue #645 Aggregate-First Discovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `pos-mcp-server`'s per-service OpenAPI auto-discovery with aggregate-first discovery from the gateway while keeping manual facade tools unchanged.

**Architecture:** Keep the existing registration pipeline centered on `ToolRegistrationServiceImpl`, but swap the document source to a single gateway aggregate fetch. Route auto-discovered tool calls through the gateway base URI, derive tool names from aggregate path domains, and fail soft when aggregate discovery is unavailable.

**Tech Stack:** Java 25, Spring Boot 4, Reactor, WebClient, swagger-parser, JUnit 5, Mockito, AssertJ

---

## File Structure

- **Modify:** `pos-mcp-server/src/main/java/com/positivity/mcp/internal/config/McpServerProperties.java` — add aggregate discovery properties and excluded-path matching helpers.
- **Modify:** `pos-mcp-server/src/main/resources/application.yml` — add default aggregate-spec URL and excluded path fragments.
- **Modify:** `pos-mcp-server/src/main/resources/application-alpha.yml` — point aggregate discovery at the gateway service URL in alpha.
- **Modify:** `pos-mcp-server/src/main/resources/META-INF/additional-spring-configuration-metadata.json` — document the new config keys.
- **Modify:** `pos-mcp-server/src/main/java/com/positivity/mcp/internal/discovery/OpenApiDocumentFetcher.java` — add aggregate fetch support that returns the gateway base URI plus parsed OpenAPI.
- **Create:** `pos-mcp-server/src/test/java/com/positivity/mcp/internal/discovery/OpenApiDocumentFetcherTest.java` — cover aggregate fetch success/failure behavior.
- **Create:** `pos-mcp-server/src/test/resources/openapi/aggregate/minimal-aggregate.yaml` — aggregate fixture with public API paths.
- **Create:** `pos-mcp-server/src/test/resources/openapi/aggregate/invalid-aggregate.yaml` — malformed aggregate fixture for parser failure coverage.
- **Modify:** `pos-mcp-server/src/main/java/com/positivity/mcp/internal/discovery/OperationProxyFactory.java` — add a direct-URI handler path for gateway-based auto-discovered tools.
- **Modify:** `pos-mcp-server/src/main/java/com/positivity/mcp/internal/discovery/OpenApiToolMapper.java` — derive `{domain}_{operationId}` names, exclude internal/admin/actuator paths, and build handlers against the gateway base URI.
- **Create:** `pos-mcp-server/src/test/java/com/positivity/mcp/internal/discovery/OpenApiToolMapperTest.java` — cover naming, filtering, and gateway-based handler construction.
- **Modify:** `pos-mcp-server/src/main/java/com/positivity/mcp/internal/service/ToolRegistrationServiceImpl.java` — replace Eureka iteration with aggregate-first registration and preserve fail-soft behavior.
- **Modify:** `pos-mcp-server/src/main/java/com/positivity/mcp/internal/discovery/ToolBootstrapRunner.java` — update startup logging to reflect aggregate-first registration.
- **Create:** `pos-mcp-server/src/test/java/com/positivity/mcp/internal/service/ToolRegistrationServiceImplTest.java` — cover successful registration and fail-soft skip behavior.

### Task 1: Add aggregate discovery configuration and fetcher support

**Files:**
- Modify: `pos-mcp-server/src/main/java/com/positivity/mcp/internal/config/McpServerProperties.java`
- Modify: `pos-mcp-server/src/main/resources/application.yml`
- Modify: `pos-mcp-server/src/main/resources/application-alpha.yml`
- Modify: `pos-mcp-server/src/main/resources/META-INF/additional-spring-configuration-metadata.json`
- Modify: `pos-mcp-server/src/main/java/com/positivity/mcp/internal/discovery/OpenApiDocumentFetcher.java`
- Create: `pos-mcp-server/src/test/java/com/positivity/mcp/internal/discovery/OpenApiDocumentFetcherTest.java`
- Create: `pos-mcp-server/src/test/resources/openapi/aggregate/minimal-aggregate.yaml`
- Create: `pos-mcp-server/src/test/resources/openapi/aggregate/invalid-aggregate.yaml`

- [ ] **Step 1: Write the failing test**

Create `pos-mcp-server/src/test/java/com/positivity/mcp/internal/discovery/OpenApiDocumentFetcherTest.java`:

```java
package com.positivity.mcp.internal.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.config.McpServerProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class OpenApiDocumentFetcherTest {

    @Test
    void fetchAggregateSpecReturnsGatewayBaseUriAndParsedOpenApi() {
        DiscoveryClient discoveryClient = mock(DiscoveryClient.class);
        WebClient webClient = WebClient.builder()
            .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_YAML_VALUE)
                    .body("""
                    openapi: 3.0.3
                    info:
                      title: Aggregate
                          version: v1
                        paths:
                          /v1/accounting/invoices:
                            get:
                              operationId: listInvoices
                              summary: List invoices
                              description: Returns invoices.
                        """)
                        .build()))
                .build();

        McpServerProperties properties = new McpServerProperties(
                "http://localhost:8086",
                "/mcp/message",
                "/mcp/sse",
                "/v3/api-docs",
                "http://localhost:8080/docs/openapi-aggregate.yaml",
                Duration.ofSeconds(5),
                List.of(),
                List.of("/v1/"),
                List.of("/admin/", "/actuator/", "/internal/"));

        OpenApiDocumentFetcher fetcher = new OpenApiDocumentFetcher(discoveryClient, webClient, properties);

        var discovered = fetcher.fetchAggregateSpec().blockOptional().orElseThrow();

        assertThat(discovered.baseUri().toString()).isEqualTo("http://localhost:8080");
        assertThat(discovered.openApi().getPaths()).containsKey("/v1/accounting/invoices");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl pos-mcp-server -DskipTests=false -Dtest=OpenApiDocumentFetcherTest test`

Expected: `BUILD FAILURE` because `McpServerProperties` does not yet accept aggregate discovery arguments and `OpenApiDocumentFetcher` has no `fetchAggregateSpec()` method.

- [ ] **Step 3: Write minimal implementation**

Update `pos-mcp-server/src/main/java/com/positivity/mcp/internal/config/McpServerProperties.java` so it owns aggregate discovery settings:

```java
public record McpServerProperties(
        @NonNull String baseUrl,
        @NonNull String messageEndpoint,
        @NonNull String sseEndpoint,
        @NonNull String openApiPath,
        @NonNull String aggregateSpecUrl,
        Duration discoveryTimeout,
        @NonNull List<String> includedServices,
        @NonNull List<String> includedPathPrefixes,
        @NonNull List<String> excludedPathFragments) {
    public McpServerProperties {
        if (aggregateSpecUrl == null) {
            aggregateSpecUrl = "http://localhost:8080/docs/openapi-aggregate.yaml";
        }
        if (excludedPathFragments == null) {
            excludedPathFragments = List.of();
        }
    }

    public boolean excludesPath(@NonNull String path) {
        return excludedPathFragments.stream().anyMatch(path::contains);
    }
}
```

Update `pos-mcp-server/src/main/resources/application.yml`:

```yaml
mcp:
  server:
    base-url: http://localhost:${server.port}
    message-endpoint: /mcp/message
    sse-endpoint: /mcp/sse
    open-api-path: /v3/api-docs
    aggregate-spec-url: ${MCP_AGGREGATE_SPEC_URL:http://localhost:8080/docs/openapi-aggregate.yaml}
    discovery-timeout: 5s
    included-services:
      - event-receiver
    included-path-prefixes:
      - /v1/
    excluded-path-fragments:
      - /admin/
      - /actuator/
      - /internal/
```

Update `pos-mcp-server/src/main/resources/application-alpha.yml` to add:

```yaml
mcp:
  server:
    aggregate-spec-url: ${MCP_AGGREGATE_SPEC_URL:http://pos-api-gateway/docs/openapi-aggregate.yaml}
```

Add matching metadata entries in `pos-mcp-server/src/main/resources/META-INF/additional-spring-configuration-metadata.json`:

```json
{
  "name": "mcp.server.aggregate-spec-url",
  "type": "java.lang.String",
  "description": "Gateway aggregate OpenAPI URL used for auto-discovered MCP tools.",
  "defaultValue": "http://localhost:8080/docs/openapi-aggregate.yaml"
},
{
  "name": "mcp.server.excluded-path-fragments",
  "type": "java.util.List<java.lang.String>",
  "description": "Path fragments excluded from aggregate-based MCP tool registration."
}
```

Update `pos-mcp-server/src/main/java/com/positivity/mcp/internal/discovery/OpenApiDocumentFetcher.java` with aggregate fetch support:

```java
public @NonNull Mono<DiscoveredOpenApi> fetchAggregateSpec() {
    URI aggregateUri = URI.create(properties.aggregateSpecUrl());
    URI baseUri = URI.create(aggregateUri.getScheme() + "://" + aggregateUri.getAuthority());
    long fetchStartNanos = System.nanoTime();

    return webClient
            .get()
            .uri(aggregateUri)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(properties.discoveryTimeout())
            .doOnNext(raw -> log.info(
                    "Fetched aggregate OpenAPI from {} in {} ms ({} bytes)",
                    aggregateUri,
                    elapsedMs(fetchStartNanos),
                    raw.length()))
            .map(raw -> deserialize("aggregate", raw))
            .flatMap(result -> {
                OpenAPI openAPI = result.getOpenAPI();
                if (openAPI == null) {
                    log.warn("Failed to parse aggregate OpenAPI from {}: {}", aggregateUri, result.getMessages());
                    return Mono.empty();
                }
                return Mono.just(new DiscoveredOpenApi("aggregate", baseUri, openAPI));
            })
            .onErrorResume(ex -> {
                log.warn("Could not fetch aggregate OpenAPI at {}: {}", aggregateUri, ex.getMessage());
                return Mono.empty();
            });
}
```

Create `pos-mcp-server/src/test/resources/openapi/aggregate/minimal-aggregate.yaml`:

```yaml
openapi: 3.0.3
info:
  title: Aggregate
  version: v1
paths:
  /v1/accounting/invoices:
    get:
      operationId: listInvoices
      summary: List invoices
      description: Returns invoices from the gateway aggregate.
```

Create `pos-mcp-server/src/test/resources/openapi/aggregate/invalid-aggregate.yaml`:

```yaml
openapi: 3.0.3
info:
  title: Broken Aggregate
paths:
  /v1/accounting/invoices: [
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl pos-mcp-server -DskipTests=false -Dtest=OpenApiDocumentFetcherTest test`

Expected: `BUILD SUCCESS` and the aggregate fetch test passes.

- [ ] **Step 5: Commit**

```bash
git add pos-mcp-server/src/main/java/com/positivity/mcp/internal/config/McpServerProperties.java \
  pos-mcp-server/src/main/java/com/positivity/mcp/internal/discovery/OpenApiDocumentFetcher.java \
  pos-mcp-server/src/main/resources/application.yml \
  pos-mcp-server/src/main/resources/application-alpha.yml \
  pos-mcp-server/src/main/resources/META-INF/additional-spring-configuration-metadata.json \
  pos-mcp-server/src/test/java/com/positivity/mcp/internal/discovery/OpenApiDocumentFetcherTest.java \
  pos-mcp-server/src/test/resources/openapi/aggregate/minimal-aggregate.yaml \
  pos-mcp-server/src/test/resources/openapi/aggregate/invalid-aggregate.yaml
git commit -m "feat: add aggregate openapi fetch support" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 2: Route auto-discovered tools through gateway aggregate paths

**Files:**
- Modify: `pos-mcp-server/src/main/java/com/positivity/mcp/internal/discovery/OperationProxyFactory.java`
- Modify: `pos-mcp-server/src/main/java/com/positivity/mcp/internal/discovery/OpenApiToolMapper.java`
- Create: `pos-mcp-server/src/test/java/com/positivity/mcp/internal/discovery/OpenApiToolMapperTest.java`

- [ ] **Step 1: Write the failing test**

Create `pos-mcp-server/src/test/java/com/positivity/mcp/internal/discovery/OpenApiToolMapperTest.java`:

```java
package com.positivity.mcp.internal.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.config.McpServerProperties;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class OpenApiToolMapperTest {

    @Test
    void mapsAggregatePathToDomainOperationToolNameAndFiltersExcludedPaths() {
        OperationProxyFactory proxyFactory = mock(OperationProxyFactory.class);
        when(proxyFactory.handler(eq(URI.create("http://localhost:8080")), eq(HttpMethod.GET), eq("/v1/accounting/invoices")))
                .thenReturn((exchange, request) -> reactor.core.publisher.Mono.empty());

        McpServerProperties properties = new McpServerProperties(
                "http://localhost:8086",
                "/mcp/message",
                "/mcp/sse",
                "/v3/api-docs",
                "http://localhost:8080/docs/openapi-aggregate.yaml",
                Duration.ofSeconds(5),
                List.of(),
                List.of("/v1/"),
                List.of("/admin/", "/actuator/", "/internal/"));

        OpenAPI openApi = new OpenAPI().paths(new Paths()
                .addPathItem("/v1/accounting/invoices", new PathItem().get(new Operation()
                        .operationId("createInvoice")
                        .summary("Create invoice")
                        .description("Creates an invoice.")))
                .addPathItem("/v1/accounting/admin/audit", new PathItem().get(new Operation()
                        .operationId("adminAudit")
                        .summary("Admin audit")
                        .description("Should be excluded."))));

        OpenApiToolMapper mapper = new OpenApiToolMapper(properties, proxyFactory);

        List<McpServerFeatures.AsyncToolSpecification> tools =
                mapper.toToolSpecifications("aggregate", URI.create("http://localhost:8080"), openApi);

        assertThat(tools).hasSize(1);
        assertThat(tools.getFirst().tool().name()).isEqualTo("accounting_createinvoice");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl pos-mcp-server -DskipTests=false -Dtest=OpenApiToolMapperTest test`

Expected: `BUILD FAILURE` because `OpenApiToolMapper` still prefixes names with `serviceId` and does not exclude admin/internal/actuator paths.

- [ ] **Step 3: Write minimal implementation**

Add a direct-base-URI handler to `OperationProxyFactory.java`:

```java
@NonNull
BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> handler(
        @NonNull URI baseUri, @NonNull HttpMethod method, @NonNull String pathTemplate) {
    return (exchange, request) -> {
        var arguments = Optional.ofNullable(request.arguments()).orElse(Map.of());
        Map<String, Object> pathParams = asMap(arguments.get("pathParams"));
        Map<String, Object> queryParams = asMap(arguments.get("queryParams"));
        Map<String, Object> headers = asMap(arguments.get("headers"));
        Object body = arguments.get("body");

        URI targetUri = buildUri(baseUri, pathTemplate, pathParams, queryParams);
        var requestSpec = webClient.method(method).uri(targetUri);
        headers.forEach((key, value) -> requestSpec.header(key, String.valueOf(value)));
        if (body != null) {
            requestSpec.contentType(MediaType.APPLICATION_JSON);
        }

        return requestSpec
                .body(body != null ? BodyInserters.fromValue(body) : BodyInserters.empty())
                .retrieve()
                .toEntity(String.class)
                .map(responseEntity -> successResult(responseEntity.getBody()))
                .onErrorResume(ex -> Mono.just(errorResult(ex.getMessage())));
    };
}
```

Refactor `OpenApiToolMapper.java` so aggregate mapping derives the tool name from the path domain and filters excluded paths:

```java
openApi.getPaths().forEach((path, pathItem) -> {
    if (!properties.includesPath(path) || properties.excludesPath(path)) {
        return;
    }
    addOperation(specs, baseUri, path, pathItem.getGet(), HttpMethod.GET);
    addOperation(specs, baseUri, path, pathItem.getPost(), HttpMethod.POST);
    addOperation(specs, baseUri, path, pathItem.getPut(), HttpMethod.PUT);
    addOperation(specs, baseUri, path, pathItem.getDelete(), HttpMethod.DELETE);
    addOperation(specs, baseUri, path, pathItem.getPatch(), HttpMethod.PATCH);
});

private void addOperation(
        @NonNull List<McpServerFeatures.AsyncToolSpecification> specs,
        @NonNull URI baseUri,
        @NonNull String path,
        Operation operation,
        @NonNull HttpMethod method) {
    if (operation == null) {
        return;
    }

    String domain = extractDomain(path);
    String operationId = buildOperationId(operation);
    String toolName = sanitizeName(domain + "_" + operationId);
    var handler = proxyFactory.handler(baseUri, method, path);
    // existing tool/input-schema construction stays the same
}

private String extractDomain(@NonNull String path) {
    String[] segments = path.split("/");
    if (segments.length >= 3 && "v1".equals(segments[1])) {
        return segments[2];
    }
    return "gateway";
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl pos-mcp-server -DskipTests=false -Dtest=OpenApiToolMapperTest test`

Expected: `BUILD SUCCESS` and the mapper test proves one public tool is emitted with the `accounting_createinvoice` name while the admin path is excluded.

- [ ] **Step 5: Commit**

```bash
git add pos-mcp-server/src/main/java/com/positivity/mcp/internal/discovery/OperationProxyFactory.java \
  pos-mcp-server/src/main/java/com/positivity/mcp/internal/discovery/OpenApiToolMapper.java \
  pos-mcp-server/src/test/java/com/positivity/mcp/internal/discovery/OpenApiToolMapperTest.java
git commit -m "feat: map aggregate openapi operations to gateway tools" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 3: Cut over registration to aggregate-first and prove fail-soft behavior

**Files:**
- Modify: `pos-mcp-server/src/main/java/com/positivity/mcp/internal/service/ToolRegistrationServiceImpl.java`
- Modify: `pos-mcp-server/src/main/java/com/positivity/mcp/internal/discovery/ToolBootstrapRunner.java`
- Create: `pos-mcp-server/src/test/java/com/positivity/mcp/internal/service/ToolRegistrationServiceImplTest.java`

- [ ] **Step 1: Write the failing test**

Create `pos-mcp-server/src/test/java/com/positivity/mcp/internal/service/ToolRegistrationServiceImplTest.java`:

```java
package com.positivity.mcp.internal.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.config.McpServerProperties;
import com.positivity.mcp.internal.discovery.OpenApiDocumentFetcher;
import com.positivity.mcp.internal.discovery.OpenApiToolMapper;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.swagger.v3.oas.models.OpenAPI;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import reactor.core.publisher.Mono;

class ToolRegistrationServiceImplTest {

    @Test
    void skipsRegistrationWhenAggregateFetchReturnsEmpty() {
        DiscoveryClient discoveryClient = mock(DiscoveryClient.class);
        McpServerProperties properties = new McpServerProperties(
                "http://localhost:8086",
                "/mcp/message",
                "/mcp/sse",
                "/v3/api-docs",
                "http://localhost:8080/docs/openapi-aggregate.yaml",
                Duration.ofSeconds(5),
                List.of(),
                List.of("/v1/"),
                List.of("/admin/", "/actuator/", "/internal/"));
        OpenApiDocumentFetcher fetcher = mock(OpenApiDocumentFetcher.class);
        OpenApiToolMapper mapper = mock(OpenApiToolMapper.class);
        McpAsyncServer server = mock(McpAsyncServer.class);

        when(fetcher.fetchAggregateSpec()).thenReturn(Mono.empty());

        ToolRegistrationServiceImpl service =
                new ToolRegistrationServiceImpl(discoveryClient, properties, fetcher, mapper, server);

        service.registerDiscoveredTools().block();

        verify(server, never()).addTool(any(McpServerFeatures.AsyncToolSpecification.class));
        verify(server, never()).notifyToolsListChanged();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl pos-mcp-server -DskipTests=false -Dtest=ToolRegistrationServiceImplTest test`

Expected: `BUILD FAILURE` because `ToolRegistrationServiceImpl` still iterates `discoveryClient.getServices()` and never calls `fetchAggregateSpec()`.

- [ ] **Step 3: Write minimal implementation**

Replace the registration flow in `ToolRegistrationServiceImpl.java` with aggregate-first orchestration:

```java
@Override
public @NonNull Mono<Void> registerDiscoveredTools() {
    long totalStartNanos = System.nanoTime();
    return openApiDocumentFetcher
            .fetchAggregateSpec()
            .flatMap(discovered -> Mono.just(openApiToolMapper.toToolSpecifications(
                    discovered.serviceId(),
                    discovered.baseUri(),
                    discovered.openApi())))
            .flatMap(specifications -> {
                if (specifications.isEmpty()) {
                    log.warn(
                            "No MCP tools matched aggregate discovery filters. Path prefixes: {}",
                            properties.includedPathPrefixes());
                    return Mono.empty();
                }

                String toolNames = specifications.stream()
                        .map(specification -> specification.tool().name())
                        .collect(java.util.stream.Collectors.joining(", "));

                log.info("Registering {} aggregate-discovered MCP tools: {}", specifications.size(), toolNames);

                return reactor.core.publisher.Flux.fromIterable(specifications)
                        .flatMap(this::addToolWithTiming)
                        .then(mcpAsyncServer.notifyToolsListChanged())
                        .doOnSuccess(ignored -> log.info(
                                "Registered aggregate-discovered MCP tools in {} ms",
                                elapsedMs(totalStartNanos)));
            })
            .switchIfEmpty(Mono.fromRunnable(() -> log.warn(
                    "Aggregate OpenAPI discovery returned no document; skipping auto-discovered MCP tools")))
            .onErrorResume(ex -> {
                log.warn(
                        "Failed to register aggregate-discovered tools after {} ms: {}",
                        elapsedMs(totalStartNanos),
                        ex.getMessage());
                return Mono.empty();
            });
}
```

Update `ToolBootstrapRunner.java` logging:

```java
log.info("Discovering aggregate OpenAPI and registering MCP tools via gateway");
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl pos-mcp-server -DskipTests=false -Dtest=OpenApiDocumentFetcherTest,OpenApiToolMapperTest,ToolRegistrationServiceImplTest test`

Expected: `BUILD SUCCESS` and the targeted aggregate-discovery tests all pass.

- [ ] **Step 5: Commit**

```bash
git add pos-mcp-server/src/main/java/com/positivity/mcp/internal/service/ToolRegistrationServiceImpl.java \
  pos-mcp-server/src/main/java/com/positivity/mcp/internal/discovery/ToolBootstrapRunner.java \
  pos-mcp-server/src/test/java/com/positivity/mcp/internal/service/ToolRegistrationServiceImplTest.java
git commit -m "feat: register mcp tools from gateway aggregate" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 4: Verify the module suite for the first slice

**Files:**
- Test: `pos-mcp-server`

- [ ] **Step 1: Write the failing verification target**

Run: `./mvnw -q -pl pos-mcp-server -DskipTests=false -Dtest=OpenApiDocumentFetcherTest,OpenApiToolMapperTest,ToolRegistrationServiceImplTest test`

Expected: this passes only after Tasks 1-3 are complete.

- [ ] **Step 2: Run targeted verification**

Run: `./mvnw -q -pl pos-mcp-server -DskipTests=false -Dtest=OpenApiDocumentFetcherTest,OpenApiToolMapperTest,ToolRegistrationServiceImplTest test`

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Run broader module verification**

Run: `./mvnw -q -pl pos-mcp-server -DskipTests=false test`

Expected: `BUILD SUCCESS` and no regressions in the existing `pos-mcp-server` module suite.

- [ ] **Step 4: Commit final verification checkpoint**

```bash
git commit --allow-empty -m "test: verify aggregate-first mcp discovery slice" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

## Self-Review Notes

- **Spec coverage:** Task 1 covers aggregate fetch/configuration, Task 2 covers `{domain}_{operationId}` mapping and path exclusion, Task 3 covers registration cutover and fail-soft behavior, and Task 4 covers module verification.
- **Placeholder scan:** every task lists exact file paths, commands, and concrete code to add or change.
- **Type consistency:** the plan keeps the existing class names and packages in `pos-mcp-server`, and it uses the same `OpenApiDocumentFetcher`, `OpenApiToolMapper`, `OperationProxyFactory`, `ToolRegistrationServiceImpl`, and `McpServerProperties` types throughout.
