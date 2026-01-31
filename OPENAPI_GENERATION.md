# OpenAPI Generation Guide

## Overview
OpenAPI documentation is generated from running Spring Boot services. Each `pos-*` module can generate its OpenAPI spec via the `local` Spring profile.

## Prerequisites
- Java 21+
- Maven build completed: `./mvnw clean package -DskipTests`
- Each service built in `/pos-{service}/target/`

## Method 1: Manual Generation (Recommended)

Start the service with the `local` profile on a specific port, then fetch the OpenAPI spec:

### Step 1: Start the Service
```bash
cd pos-inventory
java -jar target/pos-inventory-*.jar --spring.profiles.active=local --server.port=8093
```

### Step 2: Fetch the OpenAPI JSON
In another terminal:
```bash
curl http://localhost:8093/v3/api-docs > openapi.json
```

### Step 3: Stop the Service
Press `Ctrl+C` in the service terminal.

## Method 2: Maven OpenAPI Profile (Experimental)

The `openapi` profile in each module's pom.xml attempts to automate this:

```bash
./mvnw clean package -Popenapi -DskipTests -pl pos-inventory
```

**Note**: This profile starts the app during Maven's pre-integration-test phase and generates the spec during integration-test phase. It requires the application to successfully start and remain responsive.

## Output Location
- **Manual Method**: `./openapi.json` (current directory)
- **Maven Profile**: `pos-inventory/target/openapi.json`

## Available Services

Each module supports the same OpenAPI generation process:
- `pos-inventory` (port 8093)
- `pos-accounting` (port 8096)
- `pos-customer` (port 8099)
- `pos-order` (port 8100)
- `pos-location` (port 8101)
- `pos-people` (port 8102)
- `pos-workorder` (port 8103)
- ... (check individual module pom.xml for port assignments)

## Spring Local Profile Configuration

The `application-local.yml` in each service configures:
- **Database**: H2 in-memory (no PostgreSQL required)
- **Flyway**: Disabled (no migrations)
- **Eureka**: Disabled (no service discovery)
- **Port**: Dynamic (0) with `--server.port=` override
- **H2 Console**: Enabled at `/h2-console` (optional debugging)

Example:
```yaml
spring:
  datasource:
    url: jdbc:h2:mem:{service};DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
  flyway.enabled: false
eureka.client.enabled: false
```

## Troubleshooting

**Problem**: Service fails to start
- Check that H2 database driver is in classpath (should be automatic)
- Verify port is not already in use: `lsof -i :8093`
- Check logs for configuration errors

**Problem**: `/v3/api-docs` returns 404
- Ensure `springdoc-openapi-starter-webmvc-ui` dependency is present in pom.xml
- Verify service fully started before accessing endpoint

**Problem**: Empty or incomplete OpenAPI spec
- Service may not have any REST controllers defined
- Check that `@RestController` and `@RequestMapping` are properly annotated

## Integration with CI/CD

For automated OpenAPI generation in CI/CD pipelines:

```bash
#!/bin/bash
cd pos-inventory
java -jar target/pos-inventory-*.jar --spring.profiles.active=local --server.port=8093 &
PID=$!
sleep 10  # Wait for startup

curl http://localhost:8093/v3/api-docs > target/openapi.json

kill $PID
wait $PID 2>/dev/null
```

## References
- [Springdoc OpenAPI Documentation](https://springdoc.org/)
- [OpenAPI 3.0 Specification](https://spec.openapis.org/oas/v3.0.3)
