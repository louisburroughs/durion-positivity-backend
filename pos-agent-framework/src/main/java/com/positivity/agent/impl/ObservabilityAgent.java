package com.positivity.agent.impl;

import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import com.positivity.agent.AgentPerformanceSpec;
import com.positivity.agent.BaseAgent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Observability Agent - Provides OpenTelemetry instrumentation and
 * comprehensive monitoring
 * 
 * Implements REQ-008 capabilities:
 * - OpenTelemetry integration and instrumentation guidance
 * - RED metrics (Rate, Errors, Duration) implementation for all microservices
 * - Grafana dashboards, Prometheus metrics, and Jaeger tracing
 * - METRICS.md documentation standards and implementation validation
 * - Monitoring code review and observability best practices
 */
@Component
public class ObservabilityAgent extends BaseAgent {

    public ObservabilityAgent() {
        super(
                "observability-agent",
                "Observability Agent",
                "observability",
                Set.of("opentelemetry", "metrics", "tracing", "monitoring", "grafana", "prometheus",
                        "jaeger", "red-metrics", "rate", "errors", "duration", "instrumentation",
                        "observability", "telemetry", "dashboards", "alerts", "logging", "apm",
                        "distributed-tracing", "metrics-documentation", "sli", "slo", "performance"),
                Set.of(), // No dependencies
                new AgentPerformanceSpec(Duration.ofSeconds(3), 0.92, 0.999, 10, Duration.ofMinutes(5)));
    }

    @Override
    protected AgentGuidanceResponse processGuidanceRequest(AgentConsultationRequest request) {
        return AgentGuidanceResponse.success(
                request.requestId(),
                getId(),
                generateObservabilityGuidance(request),
                0.92,
                List.of("OpenTelemetry Integration", "RED Metrics Implementation", "Monitoring Setup"),
                Duration.ZERO);
    }

    private String generateObservabilityGuidance(AgentConsultationRequest request) {
        String baseGuidance = "POS System Observability Analysis for " + request.domain() + ":\n\n";
        String query = request.query().toLowerCase();

        // OpenTelemetry integration and instrumentation (REQ-008.1)
        if (query.contains("opentelemetry") || query.contains("otel") || query.contains("instrumentation") ||
                query.contains("tracing") || query.contains("telemetry")) {
            return baseGuidance + generateOpenTelemetryGuidance(request);
        }

        // Grafana dashboards, Prometheus metrics, and Jaeger tracing (REQ-008.3) -
        // check first for monitoring
        if (query.contains("grafana") || query.contains("prometheus") || query.contains("jaeger") ||
                query.contains("dashboards") || query.contains("visualization") || query.contains("monitoring")) {
            return baseGuidance + generateMonitoringStackGuidance(request);
        }

        // RED metrics implementation (REQ-008.2)
        if (query.contains("red-metrics") || query.contains("rate") || query.contains("errors") ||
                query.contains("duration") || query.contains("metrics") || query.contains("performance")) {
            return baseGuidance + generateRedMetricsGuidance(request);
        }

        // METRICS.md documentation standards (REQ-008.4)
        if (query.contains("metrics.md") || query.contains("documentation") || query.contains("standards") ||
                query.contains("metrics-docs") || query.contains("observability-docs")) {
            return baseGuidance + generateMetricsDocumentationGuidance(request);
        }

        // Implementation validation and code review (REQ-008.5)
        if (query.contains("validation") || query.contains("review") || query.contains("best-practices") ||
                query.contains("implementation") || query.contains("code-review")) {
            return baseGuidance + generateImplementationValidationGuidance(request);
        }

        // Alerting and SLI/SLO
        if (query.contains("alerts") || query.contains("alerting") || query.contains("sli") ||
                query.contains("slo") || query.contains("reliability")) {
            return baseGuidance + generateAlertingGuidance(request);
        }

        // General observability guidance
        return baseGuidance + generateGeneralObservabilityGuidance();
    }

    private String generateOpenTelemetryGuidance(AgentConsultationRequest request) {
        return "OpenTelemetry Integration & Instrumentation:\n\n" +
                "SPRING BOOT OPENTELEMETRY SETUP:\n" +
                "```xml\n" +
                "<dependency>\n" +
                "    <groupId>io.opentelemetry.instrumentation</groupId>\n" +
                "    <artifactId>opentelemetry-spring-boot-starter</artifactId>\n" +
                "</dependency>\n" +
                "<dependency>\n" +
                "    <groupId>io.micrometer</groupId>\n" +
                "    <artifactId>micrometer-tracing-bridge-otel</artifactId>\n" +
                "</dependency>\n" +
                "```\n\n" +

                "APPLICATION CONFIGURATION:\n" +
                "```yaml\n" +
                "management:\n" +
                "  tracing:\n" +
                "    sampling:\n" +
                "      probability: 1.0\n" +
                "  otlp:\n" +
                "    tracing:\n" +
                "      endpoint: http://jaeger:14268/api/traces\n" +
                "  metrics:\n" +
                "    export:\n" +
                "      prometheus:\n" +
                "        enabled: true\n" +
                "```\n\n" +

                "CUSTOM INSTRUMENTATION:\n" +
                "```java\n" +
                "@Component\n" +
                "public class OrderService {\n" +
                "    private final Tracer tracer;\n" +
                "    private final Counter orderCounter;\n" +
                "    private final Timer orderProcessingTimer;\n" +
                "\n" +
                "    @NewSpan(\"order-processing\")\n" +
                "    public Order processOrder(@SpanAttribute(\"order.id\") String orderId) {\n" +
                "        Span span = tracer.nextSpan().name(\"process-order\")\n" +
                "            .tag(\"order.id\", orderId)\n" +
                "            .tag(\"service.name\", \"pos-order\")\n" +
                "            .start();\n" +
                "        \n" +
                "        try (Tracer.SpanInScope ws = tracer.withSpanInScope(span)) {\n" +
                "            orderCounter.increment();\n" +
                "            return Timer.Sample.start(orderProcessingTimer)\n" +
                "                .stop(this::executeOrderProcessing);\n" +
                "        } finally {\n" +
                "            span.end();\n" +
                "        }\n" +
                "    }\n" +
                "}\n" +
                "```\n\n" +

                "POS-SPECIFIC TRACING PATTERNS:\n" +
                "- Order Flow: Customer → Catalog → Inventory → Payment → Fulfillment\n" +
                "- Payment Processing: Validation → Authorization → Capture → Receipt\n" +
                "- Inventory Updates: Stock Check → Reservation → Allocation → Confirmation\n" +
                "- Cross-Service Correlation: Use correlation IDs for transaction tracking\n";
    }

    private String generateRedMetricsGuidance(AgentConsultationRequest request) {
        return "RED Metrics Implementation (Rate, Errors, Duration):\n\n" +
                "RATE METRICS - Request Throughput:\n" +
                "```java\n" +
                "@RestController\n" +
                "public class OrderController {\n" +
                "    private final Counter requestCounter = Counter.builder(\"http_requests_total\")\n" +
                "        .description(\"Total HTTP requests\")\n" +
                "        .tag(\"service\", \"pos-order\")\n" +
                "        .register(Metrics.globalRegistry);\n" +
                "\n" +
                "    @PostMapping(\"/orders\")\n" +
                "    @Timed(name = \"order_creation_duration\", description = \"Order creation time\")\n" +
                "    public ResponseEntity<Order> createOrder(@RequestBody OrderRequest request) {\n" +
                "        requestCounter.increment(Tags.of(\"endpoint\", \"/orders\", \"method\", \"POST\"));\n" +
                "        // Implementation\n" +
                "    }\n" +
                "}\n" +
                "```\n\n" +

                "ERROR METRICS - Failure Tracking:\n" +
                "```java\n" +
                "@Component\n" +
                "public class ErrorMetricsCollector {\n" +
                "    private final Counter errorCounter = Counter.builder(\"application_errors_total\")\n" +
                "        .description(\"Total application errors\")\n" +
                "        .register(Metrics.globalRegistry);\n" +
                "\n" +
                "    @EventListener\n" +
                "    public void handleError(ErrorEvent event) {\n" +
                "        errorCounter.increment(\n" +
                "            Tags.of(\n" +
                "                \"service\", \"pos-order\",\n" +
                "                \"error_type\", event.getErrorType(),\n" +
                "                \"severity\", event.getSeverity()\n" +
                "            )\n" +
                "        );\n" +
                "    }\n" +
                "}\n" +
                "```\n\n" +

                "DURATION METRICS - Response Time Tracking:\n" +
                "```java\n" +
                "@Configuration\n" +
                "public class MetricsConfiguration {\n" +
                "    @Bean\n" +
                "    public TimedAspect timedAspect(MeterRegistry registry) {\n" +
                "        return new TimedAspect(registry);\n" +
                "    }\n" +
                "\n" +
                "    @Bean\n" +
                "    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {\n" +
                "        return registry -> registry.config()\n" +
                "            .commonTags(\"service\", \"pos-order\", \"version\", \"1.0.0\");\n" +
                "    }\n" +
                "}\n" +
                "```\n\n" +

                "POS-SPECIFIC RED METRICS:\n" +
                "- Order Processing Rate: orders/second, peak vs average\n" +
                "- Payment Error Rate: failed payments/total payments\n" +
                "- Inventory Update Duration: time to update stock levels\n" +
                "- Customer Lookup Duration: time to retrieve customer data\n" +
                "- Cross-Service Call Duration: inter-service communication latency\n";
    }

    private String generateMonitoringStackGuidance(AgentConsultationRequest request) {
        return "Grafana Dashboards, Prometheus & Jaeger Integration:\n\n" +
                "PROMETHEUS CONFIGURATION:\n" +
                "```yaml\n" +
                "# prometheus.yml\n" +
                "global:\n" +
                "  scrape_interval: 15s\n" +
                "  evaluation_interval: 15s\n" +
                "\n" +
                "scrape_configs:\n" +
                "  - job_name: 'pos-services'\n" +
                "    metrics_path: '/actuator/prometheus'\n" +
                "    static_configs:\n" +
                "      - targets:\n" +
                "        - 'pos-order:8080'\n" +
                "        - 'pos-inventory:8081'\n" +
                "        - 'pos-payment:8082'\n" +
                "        - 'pos-customer:8083'\n" +
                "```\n\n" +

                "GRAFANA DASHBOARD JSON TEMPLATE:\n" +
                "```json\n" +
                "{\n" +
                "  \"dashboard\": {\n" +
                "    \"title\": \"POS System Overview\",\n" +
                "    \"panels\": [\n" +
                "      {\n" +
                "        \"title\": \"Request Rate\",\n" +
                "        \"type\": \"graph\",\n" +
                "        \"targets\": [{\n" +
                "          \"expr\": \"rate(http_requests_total[5m])\",\n" +
                "          \"legendFormat\": \"{{service}} - {{endpoint}}\"\n" +
                "        }]\n" +
                "      },\n" +
                "      {\n" +
                "        \"title\": \"Error Rate\",\n" +
                "        \"type\": \"singlestat\",\n" +
                "        \"targets\": [{\n" +
                "          \"expr\": \"rate(application_errors_total[5m])\"\n" +
                "        }]\n" +
                "      }\n" +
                "    ]\n" +
                "  }\n" +
                "}\n" +
                "```\n\n" +

                "JAEGER TRACING SETUP:\n" +
                "```yaml\n" +
                "# docker-compose.yml\n" +
                "services:\n" +
                "  jaeger:\n" +
                "    image: jaegertracing/all-in-one:latest\n" +
                "    ports:\n" +
                "      - \"16686:16686\"\n" +
                "      - \"14268:14268\"\n" +
                "    environment:\n" +
                "      - COLLECTOR_OTLP_ENABLED=true\n" +
                "```\n\n" +

                "POS DASHBOARD PANELS:\n" +
                "- Service Health: Up/Down status for all microservices\n" +
                "- Transaction Volume: Orders, payments, inventory updates per minute\n" +
                "- Response Times: P50, P95, P99 latencies by service\n" +
                "- Error Rates: 4xx, 5xx errors by endpoint\n" +
                "- Business Metrics: Revenue, items sold, customer interactions\n";
    }

    private String generateMetricsDocumentationGuidance(AgentConsultationRequest request) {
        return "METRICS.md Documentation Standards:\n\n" +
                "REQUIRED METRICS.md STRUCTURE:\n" +
                "```markdown\n" +
                "# Service Metrics Documentation\n" +
                "\n" +
                "## Service Overview\n" +
                "- **Service Name**: pos-order\n" +
                "- **Version**: 1.0.0\n" +
                "- **Owner**: Order Management Team\n" +
                "- **Last Updated**: 2024-12-16\n" +
                "\n" +
                "## RED Metrics\n" +
                "\n" +
                "### Rate Metrics\n" +
                "| Metric Name | Description | Labels | SLI Target |\n" +
                "|-------------|-------------|--------|------------|\n" +
                "| `http_requests_total` | Total HTTP requests | service, endpoint, method | >100 req/min |\n" +
                "| `order_creation_rate` | Orders created per second | service | >10 orders/min |\n" +
                "\n" +
                "### Error Metrics\n" +
                "| Metric Name | Description | Labels | SLI Target |\n" +
                "|-------------|-------------|--------|------------|\n" +
                "| `application_errors_total` | Application errors | service, error_type | <1% error rate |\n" +
                "| `payment_failures_total` | Payment processing failures | service, failure_reason | <0.5% failure rate |\n"
                +
                "\n" +
                "### Duration Metrics\n" +
                "| Metric Name | Description | Labels | SLI Target |\n" +
                "|-------------|-------------|--------|------------|\n" +
                "| `order_processing_duration` | Order processing time | service | P95 <500ms |\n" +
                "| `database_query_duration` | Database query time | service, query_type | P95 <100ms |\n" +
                "\n" +
                "## Business Metrics\n" +
                "| Metric Name | Description | Labels | Business Target |\n" +
                "|-------------|-------------|--------|----------------|\n" +
                "| `revenue_total` | Total revenue processed | service, currency | Track daily revenue |\n" +
                "| `items_sold_total` | Total items sold | service, category | Track inventory turnover |\n" +
                "\n" +
                "## Alerting Rules\n" +
                "- **High Error Rate**: >5% errors in 5 minutes\n" +
                "- **High Latency**: P95 >1s for 2 minutes\n" +
                "- **Service Down**: No requests for 1 minute\n" +
                "\n" +
                "## Dashboard Links\n" +
                "- [Service Overview](http://grafana:3000/d/pos-order-overview)\n" +
                "- [Error Analysis](http://grafana:3000/d/pos-order-errors)\n" +
                "```\n\n" +

                "DOCUMENTATION VALIDATION CHECKLIST:\n" +
                "✓ All RED metrics documented with SLI targets\n" +
                "✓ Business metrics aligned with domain requirements\n" +
                "✓ Alerting rules defined for critical metrics\n" +
                "✓ Dashboard links provided and accessible\n" +
                "✓ Metric labels consistent across services\n" +
                "✓ Documentation updated with code changes\n";
    }

    private String generateImplementationValidationGuidance(AgentConsultationRequest request) {
        return "Observability Implementation Validation & Code Review:\n\n" +
                "CODE REVIEW CHECKLIST:\n" +
                "✓ OpenTelemetry instrumentation present in all service methods\n" +
                "✓ RED metrics implemented for all HTTP endpoints\n" +
                "✓ Custom business metrics aligned with domain requirements\n" +
                "✓ Proper error handling and metric recording\n" +
                "✓ Correlation IDs propagated across service boundaries\n" +
                "✓ Sensitive data excluded from traces and metrics\n" +
                "✓ Performance impact of instrumentation minimized\n\n" +

                "VALIDATION PATTERNS:\n" +
                "```java\n" +
                "@Component\n" +
                "public class ObservabilityValidator {\n" +
                "    public ValidationResult validateServiceInstrumentation(Class<?> serviceClass) {\n" +
                "        // Check for @Timed annotations on public methods\n" +
                "        // Verify Counter and Timer metrics are registered\n" +
                "        // Validate span creation in business logic\n" +
                "        // Ensure proper error metric recording\n" +
                "    }\n" +
                "}\n" +
                "```\n\n" +

                "PERFORMANCE CONSIDERATIONS:\n" +
                "- Sampling Rate: Use 1.0 for development, 0.1-0.01 for production\n" +
                "- Metric Cardinality: Limit label combinations to <10,000\n" +
                "- Trace Overhead: <5% CPU impact, <10MB memory per service\n" +
                "- Batch Export: Configure OTLP exporters with batching\n\n" +

                "SECURITY BEST PRACTICES:\n" +
                "- Exclude PII from trace attributes and metric labels\n" +
                "- Sanitize error messages in spans\n" +
                "- Use secure transport (TLS) for telemetry data\n" +
                "- Implement proper authentication for monitoring endpoints\n";
    }

    private String generateAlertingGuidance(AgentConsultationRequest request) {
        return "Alerting & SLI/SLO Implementation:\n\n" +
                "PROMETHEUS ALERTING RULES:\n" +
                "```yaml\n" +
                "groups:\n" +
                "  - name: pos-system-alerts\n" +
                "    rules:\n" +
                "      - alert: HighErrorRate\n" +
                "        expr: rate(application_errors_total[5m]) > 0.05\n" +
                "        for: 2m\n" +
                "        labels:\n" +
                "          severity: critical\n" +
                "        annotations:\n" +
                "          summary: \"High error rate detected\"\n" +
                "          description: \"Error rate is {{ $value }} for service {{ $labels.service }}\"\n" +
                "\n" +
                "      - alert: HighLatency\n" +
                "        expr: histogram_quantile(0.95, rate(http_request_duration_seconds_bucket[5m])) > 1.0\n" +
                "        for: 5m\n" +
                "        labels:\n" +
                "          severity: warning\n" +
                "```\n\n" +

                "SLI/SLO DEFINITIONS:\n" +
                "- **Availability SLI**: Percentage of successful requests (non-5xx)\n" +
                "- **Latency SLI**: P95 response time under threshold\n" +
                "- **Throughput SLI**: Requests per second capacity\n" +
                "- **Error Budget**: 99.9% availability = 43.2 minutes downtime/month\n\n" +

                "POS-SPECIFIC ALERTS:\n" +
                "- Payment Processing Failure: >1% payment failures\n" +
                "- Inventory Sync Delay: >5 minutes inventory update lag\n" +
                "- Order Processing Backup: >100 pending orders\n" +
                "- Customer Service Impact: >10% customer-facing errors\n";
    }

    private String generateGeneralObservabilityGuidance() {
        return "General Observability Best Practices:\n\n" +
                "OBSERVABILITY PILLARS:\n" +
                "1. **Metrics**: Quantitative measurements over time\n" +
                "2. **Logs**: Discrete events with context\n" +
                "3. **Traces**: Request flow across services\n\n" +

                "IMPLEMENTATION PRIORITIES:\n" +
                "1. Start with RED metrics for all services\n" +
                "2. Add distributed tracing for complex flows\n" +
                "3. Implement structured logging with correlation IDs\n" +
                "4. Create service-specific dashboards\n" +
                "5. Define and monitor SLIs/SLOs\n\n" +

                "MICROSERVICES OBSERVABILITY PATTERNS:\n" +
                "- Service Mesh: Consider Istio for automatic instrumentation\n" +
                "- Circuit Breakers: Monitor and alert on circuit breaker state\n" +
                "- Health Checks: Implement deep health checks with dependencies\n" +
                "- Correlation IDs: Propagate request IDs across all services\n\n" +

                "COST OPTIMIZATION:\n" +
                "- Use sampling for high-volume traces\n" +
                "- Implement metric retention policies\n" +
                "- Monitor telemetry data volume and costs\n" +
                "- Use local aggregation before export\n";
    }
}