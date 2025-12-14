---
description: 'Best practices and patterns for building Model Context Protocol (MCP) servers in Java using the official MCP Java SDK with reactive streams, integrated with Moqui Framework.'
applyTo: "**/*.java, **/build.gradle, **/build.gradle.kts, **/*Service.groovy, **/*RestEvents.groovy"
---

# Java MCP Server Development Guidelines for Moqui

When building MCP servers in Java for Moqui Framework, follow these best practices and patterns using the official Java SDK integrated with Moqui's architecture.

## Dependencies

Add the MCP Java SDK to your Moqui `build.gradle`:

```gradle
dependencies {
    implementation("io.modelcontextprotocol.sdk:mcp:0.14.1")
    // Moqui already provides most dependencies you'll need
}
```

Since Moqui uses Gradle, add to your component's `build.gradle` file in the root directory.

## Moqui Component Structure

Organize your MCP server within a Moqui component:

```
my-mcp-component/
├── src/main/java/
│   └── org/moqui/impl/mcp/
│       ├── MyMcpServer.java
│       ├── tools/
│       │   ├── SearchTool.java
│       │   └── EntityQueryTool.java
│       └── resources/
│           └── EntityResourceHandler.java
├── src/main/groovy/
│   └── org/moqui/impl/mcp/
│       └── McpServices.groovy
├── entity/
│   └── McpEntities.xml
└── build.gradle
```

## Server Setup with Moqui Context

Create an MCP server integrated with Moqui's context:

```java
import io.mcp.server.McpServer;
import io.mcp.server.McpServerBuilder;
import io.mcp.server.transport.StdioServerTransport;
import org.moqui.Moqui;
import org.moqui.context.ExecutionContext;

public class MyMcpServer {
    private final ExecutionContext ec;
    private final McpServer server;
    
    public MyMcpServer(ExecutionContext ec) {
        this.ec = ec;
        this.server = McpServerBuilder.builder()
            .serverInfo("moqui-mcp-server", "1.0.0")
            .capabilities(capabilities -> capabilities
                .tools(true)
                .resources(true)
                .prompts(true))
            .build();
    }
    
    public void start() {
        StdioServerTransport transport = new StdioServerTransport();
        server.start(transport).subscribe();
    }
}
```

## Adding Entity Query Tools

Query Moqui entities as tools:

```java
import io.mcp.server.tool.Tool;
import org.moqui.entity.EntityFind;
import org.moqui.entity.EntityList;
import io.mcp.json.JsonSchema;

// Define an entity query tool
Tool entityQueryTool = Tool.builder()
    .name("query_entities")
    .description("Query Moqui entities")
    .inputSchema(JsonSchema.object()
        .property("entityName", JsonSchema.string()
            .description("Name of the entity to query (e.g., Product)")
            .required(true))
        .property("conditions", JsonSchema.object()
            .description("Field conditions as key-value pairs")
            .additionalProperties(true))
        .property("limit", JsonSchema.integer()
            .description("Maximum results")
            .defaultValue(10)))
    .build();

// Register entity query handler
server.addToolHandler("query_entities", (arguments) -> {
    String entityName = arguments.get("entityName").asText();
    int limit = arguments.has("limit") 
        ? arguments.get("limit").asInt() 
        : 10;
    
    EntityFind find = ec.getEntity().find(entityName);
    
    // Add conditions if provided
    if (arguments.has("conditions")) {
        arguments.get("conditions").fields()
            .forEachRemaining(entry -> 
                find.condition(entry.getKey(), entry.getValue().asText()));
    }
    
    EntityList results = find.limit(limit).list();
    
    return Mono.just(ToolResponse.success()
        .addTextContent("Found " + results.size() + " records of " + entityName)
        .addTextContent(results.toString())
        .build());
});
```

## Adding Moqui Service Tools

Expose Moqui services as MCP tools:

```java
// Define a service call tool
Tool serviceCallTool = Tool.builder()
    .name("call_service")
    .description("Call a Moqui service")
    .inputSchema(JsonSchema.object()
        .property("serviceName", JsonSchema.string()
            .description("Service name (e.g., org.moqui.impl.ProductServices.getProduct)")
            .required(true))
        .property("parameters", JsonSchema.object()
            .description("Service parameters as key-value pairs")
            .additionalProperties(true)))
    .build();

// Register service call handler
server.addToolHandler("call_service", (arguments) -> {
    String serviceName = arguments.get("serviceName").asText();
    Map<String, Object> params = new HashMap<>();
    
    if (arguments.has("parameters")) {
        arguments.get("parameters").fields()
            .forEachRemaining(entry -> 
                params.put(entry.getKey(), entry.getValue().asText()));
    }
    
    return Mono.fromCallable(() -> {
        Map<String, Object> result = ec.getService()
            .sync()
            .name(serviceName)
            .parameters(params)
            .call();
        
        return ToolResponse.success()
            .addTextContent("Service executed successfully")
            .addTextContent(result.toString())
            .build();
    }).subscribeOn(Schedulers.boundedElastic());
});
```

## Adding Entity Resources

Expose Moqui entities as resources:

```java
import io.mcp.server.resource.Resource;

// Register resource list handler - list all entities
server.addResourceListHandler(() -> {
    return Mono.fromCallable(() -> {
        List<Resource> resources = new ArrayList<>();
        
        // List available entities from Moqui
        ec.getEntity().getAllNonViewEntities().forEach(entity -> {
            resources.add(Resource.builder()
                .name(entity.getEntityName())
                .uri("entity://" + entity.getEntityName())
                .description("Entity: " + entity.getEntityName())
                .mimeType("application/json")
                .build());
        });
        
        return resources;
    });
});

// Register resource read handler - get entity definitions
server.addResourceReadHandler((uri) -> {
    if (uri.startsWith("entity://")) {
        String entityName = uri.substring(9);
        
        return Mono.fromCallable(() -> {
            Map<String, Object> entityDef = new HashMap<>();
            // Build entity definition including fields, relationships, etc.
            return ResourceContent.json(
                objectMapper.writeValueAsString(entityDef), 
                uri);
        });
    }
    throw new ResourceNotFoundException(uri);
});
```

## Adding Moqui Service Prompts

Define prompts for common Moqui operations:

```java
import io.mcp.server.prompt.Prompt;
import io.mcp.server.prompt.PromptMessage;

// Register service usage prompts
server.addPromptListHandler(() -> {
    List<Prompt> prompts = List.of(
        Prompt.builder()
            .name("find_entity")
            .description("Find records in a Moqui entity")
            .argument(PromptArgument.builder()
                .name("entityName")
                .description("Name of the entity (e.g., Product, Order)")
                .required(true)
                .build())
            .argument(PromptArgument.builder()
                .name("conditions")
                .description("Filter conditions")
                .required(false)
                .build())
            .build(),
        Prompt.builder()
            .name("call_moqui_service")
            .description("Call a Moqui business service")
            .argument(PromptArgument.builder()
                .name("servicePath")
                .description("Service path (e.g., org.moqui.impl.OrderServices.createOrder)")
                .required(true)
                .build())
            .argument(PromptArgument.builder()
                .name("inputParameters")
                .description("Service input parameters")
                .required(false)
                .build())
            .build()
    );
    return Mono.just(prompts);
});

// Implement prompt handlers
server.addPromptGetHandler((name, arguments) -> {
    if (name.equals("find_entity")) {
        String entityName = arguments.get("entityName");
        String conditions = arguments.getOrDefault("conditions", "");
        
        String prompt = String.format(
            "Query the %s entity with conditions: %s\n\n" +
            "Use the query_entities tool to search for records.",
            entityName, conditions);
        
        List<PromptMessage> messages = List.of(
            PromptMessage.user(prompt)
        );
        
        return Mono.just(PromptResult.builder()
            .description("Find entity records in " + entityName)
            .messages(messages)
            .build());
    }
    throw new PromptNotFoundException(name);
});
```

## Integration with Moqui Groovy Services

Expose service logic via MCP by wrapping Groovy services:

```groovy
// src/main/groovy/org/moqui/impl/mcp/McpServices.groovy

def queryEntitiesForMcp(Map params) {
    String entityName = params.entityName
    Map conditions = params.conditions ?: [:]
    Integer limit = params.limit ?: 10
    
    EntityFind find = entity.find(entityName)
    conditions.each { field, value ->
        find.condition(field, value)
    }
    
    EntityList results = find.limit(limit).list()
    
    return [
        count: results.size(),
        records: results.map { it.getMap() }
    ]
}

def callServiceForMcp(Map params) {
    String serviceName = params.serviceName
    Map serviceParams = params.parameters ?: [:]
    
    Map result = service.sync()
        .name(serviceName)
        .parameters(serviceParams)
        .call()
    
    return result
}
```

Register as Moqui service definition:

```xml
<!-- entity/McpServices.xml -->
<services xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/service-definition-3.xsd">
    
    <service verb="query" noun="EntitiesForMcp" type="script">
        <description>Query Moqui entities for MCP</description>
        <in-parameters>
            <parameter name="entityName" required="true"/>
            <parameter name="conditions" type="Map"/>
            <parameter name="limit" type="Integer" default="10"/>
        </in-parameters>
        <out-parameters>
            <parameter name="count" type="Integer"/>
            <parameter name="records" type="List"/>
        </out-parameters>
        <script location="org/moqui/impl/mcp/McpServices.groovy" method="queryEntitiesForMcp"/>
    </service>
    
    <service verb="call" noun="ServiceForMcp" type="script">
        <description>Call a Moqui service for MCP</description>
        <in-parameters>
            <parameter name="serviceName" required="true"/>
            <parameter name="parameters" type="Map"/>
        </in-parameters>
        <out-parameters>
            <parameter name="results" type="Map"/>
        </out-parameters>
        <script location="org/moqui/impl/mcp/McpServices.groovy" method="callServiceForMcp"/>
    </service>
</services>
```

## REST API Integration

Expose MCP tools via Moqui REST API:

```java
// Create a Moqui REST endpoint that calls MCP tools
@RestEvent
public class McpToolRestEvents {
    
    private final ExecutionContext ec;
    
    @RequestMapping(method = RequestMethod.POST, value = "/mcp/tool")
    public Map<String, Object> callMcpTool(
            @RequestBody Map<String, Object> request,
            ExecutionContext ec) {
        
        String toolName = (String) request.get("toolName");
        Map<String, Object> parameters = (Map<String, Object>) 
            request.getOrDefault("parameters", new HashMap<>());
        
        // Call MCP tool and return result
        return callMcpToolInternal(toolName, parameters);
    }
}
```

## Entity Definition Integration

Reference Moqui entity definitions in your MCP server:

```java
public void discoverEntitiesAsMcpResources() {
    // Get all entity definitions from Moqui
    ec.getEntity().getAllNonViewEntities().forEach(entity -> {
        String entityName = entity.getEntityName();
        
        // Register as MCP resource
        Resource resource = Resource.builder()
            .name(entityName)
            .uri("entity://" + entityName)
            .description("Entity: " + entityName + 
                " (" + entity.getPkFieldNames().size() + " key fields)")
            .mimeType("application/json")
            .build();
        
        resources.add(resource);
    });
}
```

## Testing with Moqui

Write tests using Moqui's test framework:

```java
import org.moqui.context.ExecutionContextFactory;
import org.moqui.Moqui;
import org.junit.jupiter.api.Test;

class McpServerMoquiTest {
    
    @Test
    void testEntityQueryTool() {
        ExecutionContextFactory ecf = Moqui.getExecutionContextFactory();
        ExecutionContext ec = ecf.getExecutionContext();
        
        try {
            MyMcpServer mcpServer = new MyMcpServer(ec);
            
            // Verify tool exists
            assertThat(mcpServer.getTools()).contains("query_entities");
            
            // Test entity query
            Map<String, Object> queryResult = mcpServer.callTool(
                "query_entities",
                Map.of("entityName", "Product", "limit", 5));
            
            assertThat(queryResult).containsKey("records");
        } finally {
            ec.destroy();
        }
    }
}
```

## Moqui Configuration

Add MCP server configuration to your component.xml:

```xml
<!-- component.xml -->
<component xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/component-3.xsd">
    
    <init-services>
        <start-service name="org.moqui.impl.mcp.StartMcpServer"/>
    </init-services>
    
</component>
```

## Common Moqui MCP Patterns

### Access Moqui Services

```java
// Call a Moqui service from MCP tool
server.addToolHandler("moqui_service", (args) -> {
    return Mono.fromCallable(() -> {
        Map<String, Object> result = ec.getService()
            .sync()
            .name("org.moqui.impl.OrderServices.createOrder")
            .parameters(Map.of(
                "partyId", "DEMO_ORG",
                "productId", args.get("productId").asText()))
            .call();
        
        return ToolResponse.success()
            .addTextContent("Order created: " + result.get("orderId"))
            .build();
    }).subscribeOn(Schedulers.boundedElastic());
});
```

### Query with EntityFind

```java
// Use Moqui's EntityFind API
EntityFind find = ec.getEntity().find(entityName)
    .condition("status", "ACTIVE")
    .orderBy("name")
    .limit(limit);

EntityList results = find.list();
```

### Access Moqui Caching

```java
// Use Moqui's cache system
Cache cache = ec.getCache().getCache("mcp-tool-cache");
cache.put(cacheKey, result, 300); // 5 minute TTL
```

### Logging with Moqui

```java
// Use Moqui's logger
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

Logger logger = LoggerFactory.getLogger(MyMcpServer.class);
logger.info("MCP Tool called: {}", toolName);
```

## Error Handling for Moqui

Handle Moqui-specific exceptions:

```java
server.addToolHandler("safe_operation", (args) -> {
    return Mono.fromCallable(() -> {
        try {
            String entityName = args.get("entityName").asText();
            EntityFind find = ec.getEntity().find(entityName);
            EntityList results = find.list();
            
            return ToolResponse.success()
                .addTextContent("Success: " + results.size() + " records")
                .build();
        } catch (EntityException e) {
            logger.error("Entity operation failed", e);
            return ToolResponse.error()
                .message("Entity error: " + e.getMessage())
                .build();
        } catch (ServiceException e) {
            logger.error("Service call failed", e);
            return ToolResponse.error()
                .message("Service error: " + e.getMessage())
                .build();
        }
    });
});
```

## Best Practices for Moqui MCP

1. **Leverage Moqui Context** - Always use ExecutionContext for entity and service access
2. **Use Moqui Naming Conventions** - Follow entity and service naming patterns
3. **Implement Service Definitions** - Define Moqui services for reusability
4. **Cache Results** - Use Moqui's caching system for performance
5. **Security** - Apply Moqui's permission system for MCP access
6. **Logging** - Use SLF4J integrated with Moqui logging
7. **Transactions** - Respect Moqui's transaction handling
8. **Entity Relationships** - Expose related entities as resources
9. **Async Operations** - Use bounded elastic scheduler for service calls
10. **Documentation** - Document service parameters and expected outputs

## Dependencies

Add the MCP Java SDK to your Moqui `build.gradle`:

```gradle
dependencies {
    implementation("io.modelcontextprotocol.sdk:mcp:0.14.1")
    // Moqui already provides most dependencies you'll need
}
```

Since Moqui uses Gradle, add to your component's `build.gradle` file in the root directory.

## Server Setup

Create an MCP server using the builder pattern:

```java
import io.mcp.server.McpServer;
import io.mcp.server.McpServerBuilder;
import io.mcp.server.transport.StdioServerTransport;

McpServer server = McpServerBuilder.builder()
    .serverInfo("my-server", "1.0.0")
    .capabilities(capabilities -> capabilities
        .tools(true)
        .resources(true)
        .prompts(true))
    .build();

// Start with stdio transport
StdioServerTransport transport = new StdioServerTransport();
server.start(transport).subscribe();
```

## Adding Tools

Register tool handlers with the server:

```java
import io.mcp.server.tool.Tool;
import io.mcp.server.tool.ToolHandler;
import reactor.core.publisher.Mono;

// Define a tool
Tool searchTool = Tool.builder()
    .name("search")
    .description("Search for information")
    .inputSchema(JsonSchema.object()
        .property("query", JsonSchema.string()
            .description("Search query")
            .required(true))
        .property("limit", JsonSchema.integer()
            .description("Maximum results")
            .defaultValue(10)))
    .build();

// Register tool handler
server.addToolHandler("search", (arguments) -> {
    String query = arguments.get("query").asText();
    int limit = arguments.has("limit") 
        ? arguments.get("limit").asInt() 
        : 10;
    
    // Perform search
    List<String> results = performSearch(query, limit);
    
    return Mono.just(ToolResponse.success()
        .addTextContent("Found " + results.size() + " results")
        .build());
});
```

## Adding Resources

Implement resource handlers for data access:

```java
import io.mcp.server.resource.Resource;
import io.mcp.server.resource.ResourceHandler;

// Register resource list handler
server.addResourceListHandler(() -> {
    List<Resource> resources = List.of(
        Resource.builder()
            .name("Data File")
            .uri("resource://data/example.txt")
            .description("Example data file")
            .mimeType("text/plain")
            .build()
    );
    return Mono.just(resources);
});

// Register resource read handler
server.addResourceReadHandler((uri) -> {
    if (uri.equals("resource://data/example.txt")) {
        String content = loadResourceContent(uri);
        return Mono.just(ResourceContent.text(content, uri));
    }
    throw new ResourceNotFoundException(uri);
});

// Register resource subscribe handler
server.addResourceSubscribeHandler((uri) -> {
    subscriptions.add(uri);
    log.info("Client subscribed to {}", uri);
    return Mono.empty();
});
```

## Adding Prompts

Implement prompt handlers for templated conversations:

```java
import io.mcp.server.prompt.Prompt;
import io.mcp.server.prompt.PromptMessage;
import io.mcp.server.prompt.PromptArgument;

// Register prompt list handler
server.addPromptListHandler(() -> {
    List<Prompt> prompts = List.of(
        Prompt.builder()
            .name("analyze")
            .description("Analyze a topic")
            .argument(PromptArgument.builder()
                .name("topic")
                .description("Topic to analyze")
                .required(true)
                .build())
            .argument(PromptArgument.builder()
                .name("depth")
                .description("Analysis depth")
                .required(false)
                .build())
            .build()
    );
    return Mono.just(prompts);
});

// Register prompt get handler
server.addPromptGetHandler((name, arguments) -> {
    if (name.equals("analyze")) {
        String topic = arguments.getOrDefault("topic", "general");
        String depth = arguments.getOrDefault("depth", "basic");
        
        List<PromptMessage> messages = List.of(
            PromptMessage.user("Please analyze this topic: " + topic),
            PromptMessage.assistant("I'll provide a " + depth + " analysis of " + topic)
        );
        
        return Mono.just(PromptResult.builder()
            .description("Analysis of " + topic + " at " + depth + " level")
            .messages(messages)
            .build());
    }
    throw new PromptNotFoundException(name);
});
```

## Reactive Streams Pattern

The Java SDK uses Reactive Streams (Project Reactor) for asynchronous processing:

```java
// Return Mono for single results
server.addToolHandler("process", (args) -> {
    return Mono.fromCallable(() -> {
        String result = expensiveOperation(args);
        return ToolResponse.success()
            .addTextContent(result)
            .build();
    }).subscribeOn(Schedulers.boundedElastic());
});

// Return Flux for streaming results
server.addResourceListHandler(() -> {
    return Flux.fromIterable(getResources())
        .map(r -> Resource.builder()
            .uri(r.getUri())
            .name(r.getName())
            .build())
        .collectList();
});
```

## Synchronous Facade

For blocking use cases, use the synchronous API:

```java
import io.mcp.server.McpSyncServer;

McpSyncServer syncServer = server.toSyncServer();

// Blocking tool handler
syncServer.addToolHandler("greet", (args) -> {
    String name = args.get("name").asText();
    return ToolResponse.success()
        .addTextContent("Hello, " + name + "!")
        .build();
});
```

## Transport Configuration

### Stdio Transport

For local subprocess communication:

```java
import io.mcp.server.transport.StdioServerTransport;

StdioServerTransport transport = new StdioServerTransport();
server.start(transport).block();
```

### HTTP Transport (Servlet)

For HTTP-based servers:

```java
import io.mcp.server.transport.ServletServerTransport;
import jakarta.servlet.http.HttpServlet;

public class McpServlet extends HttpServlet {
    private final McpServer server;
    private final ServletServerTransport transport;
    
    public McpServlet() {
        this.server = createMcpServer();
        this.transport = new ServletServerTransport();
    }
    
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) {
        transport.handleRequest(server, req, resp).block();
    }
}
```

## Spring Boot Integration

Use the Spring Boot starter for seamless integration:

```xml
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-spring-boot-starter</artifactId>
    <version>0.14.1</version>
</dependency>
```

Configure the server with Spring:

```java
import org.springframework.context.annotation.Configuration;
import io.mcp.spring.McpServerConfigurer;

@Configuration
public class McpConfiguration {
    
    @Bean
    public McpServerConfigurer mcpServerConfigurer() {
        return server -> server
            .serverInfo("spring-server", "1.0.0")
            .capabilities(cap -> cap
                .tools(true)
                .resources(true)
                .prompts(true));
    }
}
```

Register handlers as Spring beans:

```java
import org.springframework.stereotype.Component;
import io.mcp.spring.ToolHandler;

@Component
public class SearchToolHandler implements ToolHandler {
    
    @Override
    public String getName() {
        return "search";
    }
    
    @Override
    public Tool getTool() {
        return Tool.builder()
            .name("search")
            .description("Search for information")
            .inputSchema(JsonSchema.object()
                .property("query", JsonSchema.string().required(true)))
            .build();
    }
    
    @Override
    public Mono<ToolResponse> handle(JsonNode arguments) {
        String query = arguments.get("query").asText();
        return Mono.just(ToolResponse.success()
            .addTextContent("Search results for: " + query)
            .build());
    }
}
```

## Error Handling

Use proper error handling with MCP exceptions:

```java
server.addToolHandler("risky", (args) -> {
    return Mono.fromCallable(() -> {
        try {
            String result = riskyOperation(args);
            return ToolResponse.success()
                .addTextContent(result)
                .build();
        } catch (ValidationException e) {
            return ToolResponse.error()
                .message("Invalid input: " + e.getMessage())
                .build();
        } catch (Exception e) {
            log.error("Unexpected error", e);
            return ToolResponse.error()
                .message("Internal error occurred")
                .build();
        }
    });
});
```

## JSON Schema Construction

Use the fluent schema builder:

```java
import io.mcp.json.JsonSchema;

JsonSchema schema = JsonSchema.object()
    .property("name", JsonSchema.string()
        .description("User's name")
        .minLength(1)
        .maxLength(100)
        .required(true))
    .property("age", JsonSchema.integer()
        .description("User's age")
        .minimum(0)
        .maximum(150))
    .property("email", JsonSchema.string()
        .description("Email address")
        .format("email")
        .required(true))
    .property("tags", JsonSchema.array()
        .items(JsonSchema.string())
        .uniqueItems(true))
    .additionalProperties(false)
    .build();
```

## Logging and Observability

Use SLF4J for logging:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

private static final Logger log = LoggerFactory.getLogger(MyMcpServer.class);

server.addToolHandler("process", (args) -> {
    log.info("Tool called: process, args: {}", args);
    
    return Mono.fromCallable(() -> {
        String result = process(args);
        log.debug("Processing completed successfully");
        return ToolResponse.success()
            .addTextContent(result)
            .build();
    }).doOnError(error -> {
        log.error("Processing failed", error);
    });
});
```

Propagate context with Reactor:

```java
import reactor.util.context.Context;

server.addToolHandler("traced", (args) -> {
    return Mono.deferContextual(ctx -> {
        String traceId = ctx.get("traceId");
        log.info("Processing with traceId: {}", traceId);
        
        return Mono.just(ToolResponse.success()
            .addTextContent("Processed")
            .build());
    });
});
```

## Testing

Write tests using the synchronous API:

```java
import org.junit.jupiter.api.Test;
import static org.assertj.core.Assertions.assertThat;

class McpServerTest {
    
    @Test
    void testToolHandler() {
        McpServer server = createTestServer();
        McpSyncServer syncServer = server.toSyncServer();
        
        JsonNode args = objectMapper.createObjectNode()
            .put("query", "test");
        
        ToolResponse response = syncServer.callTool("search", args);
        
        assertThat(response.isError()).isFalse();
        assertThat(response.getContent()).hasSize(1);
    }
}
```

## Jackson Integration

The SDK uses Jackson for JSON serialization. Customize as needed:

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

ObjectMapper mapper = new ObjectMapper();
mapper.registerModule(new JavaTimeModule());

// Use custom mapper with server
McpServer server = McpServerBuilder.builder()
    .objectMapper(mapper)
    .build();
```

## Content Types

Support multiple content types in responses:

```java
import io.mcp.server.content.Content;

server.addToolHandler("multi", (args) -> {
    return Mono.just(ToolResponse.success()
        .addTextContent("Plain text response")
        .addImageContent(imageBytes, "image/png")
        .addResourceContent("resource://data", "application/json", jsonData)
        .build());
});
```

## Server Lifecycle

Properly manage server lifecycle:

```java
import reactor.core.Disposable;

Disposable serverDisposable = server.start(transport).subscribe();

// Graceful shutdown
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    log.info("Shutting down MCP server");
    serverDisposable.dispose();
    server.stop().block();
}));
```

## Common Patterns

### Request Validation

```java
server.addToolHandler("validate", (args) -> {
    if (!args.has("required_field")) {
        return Mono.just(ToolResponse.error()
            .message("Missing required_field")
            .build());
    }
    
    return processRequest(args);
});
```

### Async Operations

```java
server.addToolHandler("async", (args) -> {
    return Mono.fromCallable(() -> callExternalApi(args))
        .timeout(Duration.ofSeconds(30))
        .onErrorResume(TimeoutException.class, e -> 
            Mono.just(ToolResponse.error()
                .message("Operation timed out")
                .build()))
        .subscribeOn(Schedulers.boundedElastic());
});
```

### Resource Caching

```java
private final Map<String, String> cache = new ConcurrentHashMap<>();

server.addResourceReadHandler((uri) -> {
    return Mono.fromCallable(() -> 
        cache.computeIfAbsent(uri, this::loadResource))
        .map(content -> ResourceContent.text(content, uri));
});
```

## Best Practices

1. **Use Reactive Streams** for async operations and backpressure
2. **Leverage Spring Boot** starter for enterprise applications
3. **Implement proper error handling** with specific error messages
4. **Use SLF4J** for logging, not System.out
5. **Validate inputs** in tool and prompt handlers
6. **Support graceful shutdown** with proper resource cleanup
7. **Use bounded elastic scheduler** for blocking operations
8. **Propagate context** for observability in reactive chains
9. **Test with synchronous API** for simplicity
10. **Follow Java naming conventions** (camelCase for methods, PascalCase for classes)