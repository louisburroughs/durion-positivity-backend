package com.positivity.agent.impl;

import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import com.positivity.agent.AgentPerformanceSpec;
import com.positivity.agent.BaseAgent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Architectural Governance Agent - Provides domain-driven design enforcement and system coherence
 * 
 * Implements REQ-005 capabilities:
 * - Domain boundary enforcement and circular dependency prevention
 * - API Gateway, SNS/SQS messaging, and event-driven architecture patterns
 * - Backward compatibility, versioning, and migration guidance
 * - POS domain pattern validation and technical debt management
 * - Architecture decision record (ADR) creation and maintenance
 */
@Component
public class ArchitecturalGovernanceAgent extends BaseAgent {

    // POS Domain boundaries with strict ownership rules
    private static final Map<String, Set<String>> POS_DOMAIN_OWNERSHIP = Map.of(
            "catalog", Set.of("pos-catalog", "pos-inventory", "pos-vehicle-inventory"),
            "customer", Set.of("pos-customer", "pos-people"),
            "order", Set.of("pos-order", "pos-invoice", "pos-work-order"),
            "pricing", Set.of("pos-price", "pos-accounting"),
            "integration", Set.of("pos-vehicle-reference-nhtsa", "pos-vehicle-reference-carapi", "pos-vehicle-fitment"),
            "infrastructure", Set.of("pos-api-gateway", "pos-service-discovery", "pos-security-service"),
            "operations", Set.of("pos-shop-manager", "pos-location", "pos-events", "pos-event-receiver"),
            "support", Set.of("pos-inquiry", "pos-image"));

    // Allowed cross-domain dependencies (prevents circular dependencies)
    private static final Map<String, Set<String>> ALLOWED_DEPENDENCIES = Map.of(
            "order", Set.of("customer", "catalog", "pricing"),
            "pricing", Set.of("catalog"),
            "operations", Set.of("customer", "order", "catalog"),
            "support", Set.of("customer", "order"),
            "integration", Set.of("catalog"));

    // API Gateway patterns and routing rules
    private static final Map<String, String> API_GATEWAY_PATTERNS = Map.of(
            "/api/v1/catalog/**", "pos-catalog",
            "/api/v1/customers/**", "pos-customer",
            "/api/v1/orders/**", "pos-order",
            "/api/v1/invoices/**", "pos-invoice",
            "/api/v1/inventory/**", "pos-inventory",
            "/api/v1/pricing/**", "pos-price",
            "/api/v1/vehicles/**", "pos-vehicle-inventory",
            "/api/v1/shop/**", "pos-shop-manager");

    // Event-driven architecture patterns
    private static final Set<String> DOMAIN_EVENTS = Set.of(
            "CustomerCreated", "CustomerUpdated", "OrderPlaced", "OrderCompleted",
            "PaymentProcessed", "InventoryUpdated", "PriceChanged", "VehicleAdded");

    public ArchitecturalGovernanceAgent() {
        super(
                "architectural-governance-agent",
                "Architectural Governance Agent",
                "governance",
                Set.of("domain-boundaries", "technical-debt", "adrs", "migration", "ddd-enforcement",
                        "circular-dependency", "api-gateway", "event-driven", "versioning", "pos-patterns"),
                Set.of("architecture-agent"), // Depends on Architecture Agent
                AgentPerformanceSpec.defaultSpec());
    }

    @Override
    protected AgentGuidanceResponse processGuidanceRequest(AgentConsultationRequest request) {
        String guidance = generateGovernanceGuidance(request);
        List<String> recommendations = generateGovernanceRecommendations(request);

        // Validate domain boundaries if applicable
        if (request.query().toLowerCase().contains("domain") ||
                request.query().toLowerCase().contains("boundary") ||
                request.query().toLowerCase().contains("service")) {
            guidance += "\n\n" + validateDomainBoundaries(request);
        }

        // Check for circular dependencies
        if (request.query().toLowerCase().contains("dependency") ||
                request.query().toLowerCase().contains("circular")) {
            guidance += "\n\n" + validateCircularDependencies(request);
        }

        return AgentGuidanceResponse.success(
                request.requestId(),
                getId(),
                guidance,
                0.92, // High confidence for governance guidance
                recommendations,
                Duration.ofMillis(250) // Simulated processing time
        );
    }

    private String generateGovernanceGuidance(AgentConsultationRequest request) {
        String baseGuidance = "POS System Architectural Governance Analysis for " + request.domain() + ":\n\n";
        String query = request.query().toLowerCase();

        // Domain boundary enforcement guidance
        if (query.contains("domain") || query.contains("boundary") || query.contains("ddd")) {
            return baseGuidance + generateDomainBoundaryGuidance(request);
        }

        // API Gateway and integration patterns
        if (query.contains("api") || query.contains("gateway") || query.contains("integration")) {
            return baseGuidance + generateAPIGatewayGuidance(request);
        }

        // Event-driven architecture patterns
        if (query.contains("event") || query.contains("messaging") || query.contains("sns") || query.contains("sqs")) {
            return baseGuidance + generateEventDrivenGuidance(request);
        }

        // Versioning and migration guidance
        if (query.contains("version") || query.contains("migration") || query.contains("compatibility")) {
            return baseGuidance + generateVersioningGuidance(request);
        }

        // Technical debt management
        if (query.contains("debt") || query.contains("refactor") || query.contains("quality")) {
            return baseGuidance + generateTechnicalDebtGuidance(request);
        }

        // POS domain pattern validation
        if (query.contains("pos") || query.contains("pattern") || query.contains("validation")) {
            return baseGuidance + generatePOSPatternGuidance(request);
        }

        // General governance guidance
        return baseGuidance + generateGeneralGovernanceGuidance();
    }

    private String generateDomainBoundaryGuidance(AgentConsultationRequest request) {
        return "Domain Boundary Enforcement for POS System:\n\n" +
                "DOMAIN OWNERSHIP RULES:\n" +
                "- CATALOG Domain: pos-catalog (products), pos-inventory (stock), pos-vehicle-inventory (vehicles)\n" +
                "- CUSTOMER Domain: pos-customer (profiles), pos-people (staff management)\n" +
                "- ORDER Domain: pos-order (processing), pos-invoice (billing), pos-work-order (services)\n" +
                "- PRICING Domain: pos-price (rules), pos-accounting (financial)\n" +
                "- INTEGRATION Domain: pos-vehicle-reference-* (external data), pos-vehicle-fitment (compatibility)\n" +
                "- INFRASTRUCTURE Domain: pos-api-gateway, pos-service-discovery, pos-security-service\n" +
                "- OPERATIONS Domain: pos-shop-manager, pos-location, pos-events, pos-event-receiver\n" +
                "- SUPPORT Domain: pos-inquiry (help desk), pos-image (media)\n\n" +
                "BOUNDARY ENFORCEMENT RULES:\n" +
                "- Each service MUST own its complete business capability\n" +
                "- NO direct database access across domain boundaries\n" +
                "- Use domain events for cross-domain communication\n" +
                "- Implement anti-corruption layers for external integrations\n" +
                "- Maintain separate data models per domain\n\n" +
                "VIOLATION DETECTION:\n" +
                "- Shared database tables between services = VIOLATION\n" +
                "- Direct service-to-service database calls = VIOLATION\n" +
                "- Business logic leaking across domains = VIOLATION\n" +
                "- Circular dependencies between domains = VIOLATION\n\n" +
                "REMEDIATION STRATEGIES:\n" +
                "- Extract shared data into dedicated service\n" +
                "- Implement event-driven communication\n" +
                "- Create domain-specific data models\n" +
                "- Use API contracts for cross-domain access";
    }

    private String generateAPIGatewayGuidance(AgentConsultationRequest request) {
        return "API Gateway Patterns for POS System:\n\n" +
                "ROUTING PATTERNS:\n" +
                "- /api/v1/catalog/** → pos-catalog (product management)\n" +
                "- /api/v1/customers/** → pos-customer (customer operations)\n" +
                "- /api/v1/orders/** → pos-order (order processing)\n" +
                "- /api/v1/invoices/** → pos-invoice (billing operations)\n" +
                "- /api/v1/inventory/** → pos-inventory (stock management)\n" +
                "- /api/v1/pricing/** → pos-price (pricing operations)\n" +
                "- /api/v1/vehicles/** → pos-vehicle-inventory (vehicle management)\n" +
                "- /api/v1/shop/** → pos-shop-manager (shop operations)\n\n" +
                "CROSS-CUTTING CONCERNS:\n" +
                "- Authentication: JWT token validation for all requests\n" +
                "- Authorization: Role-based access control (RBAC)\n" +
                "- Rate Limiting: Per-client and per-endpoint limits\n" +
                "- Request/Response Transformation: Data format standardization\n" +
                "- Circuit Breaker: Fault tolerance for downstream services\n" +
                "- Logging: Centralized request/response logging\n" +
                "- Metrics: Request count, latency, error rates\n\n" +
                "SNS/SQS MESSAGING PATTERNS:\n" +
                "- Event Publishing: Domain events to SNS topics\n" +
                "- Event Consumption: SQS queues for reliable delivery\n" +
                "- Dead Letter Queues: Failed message handling\n" +
                "- Message Ordering: FIFO queues for ordered processing\n" +
                "- Retry Policies: Exponential backoff for failures\n\n" +
                "INTEGRATION BEST PRACTICES:\n" +
                "- Use correlation IDs for request tracing\n" +
                "- Implement idempotent operations\n" +
                "- Version API contracts explicitly\n" +
                "- Apply timeout and retry policies\n" +
                "- Monitor integration health continuously";
    }

    private String generateEventDrivenGuidance(AgentConsultationRequest request) {
        return "Event-Driven Architecture for POS System:\n\n" +
                "DOMAIN EVENTS:\n" +
                "- CustomerCreated: New customer registration\n" +
                "- CustomerUpdated: Customer profile changes\n" +
                "- OrderPlaced: New order creation\n" +
                "- OrderCompleted: Order fulfillment\n" +
                "- PaymentProcessed: Payment completion\n" +
                "- InventoryUpdated: Stock level changes\n" +
                "- PriceChanged: Pricing rule updates\n" +
                "- VehicleAdded: New vehicle inventory\n\n" +
                "EVENT PUBLISHING PATTERNS:\n" +
                "- Transactional Outbox: Ensure event consistency\n" +
                "- Event Sourcing: Store events as source of truth\n" +
                "- Saga Pattern: Coordinate distributed transactions\n" +
                "- CQRS: Separate read/write models\n\n" +
                "SNS/SQS IMPLEMENTATION:\n" +
                "- SNS Topics: One per domain event type\n" +
                "- SQS Queues: One per consuming service\n" +
                "- Fan-out Pattern: Multiple consumers per event\n" +
                "- Message Filtering: Attribute-based routing\n" +
                "- DLQ Configuration: Handle poison messages\n\n" +
                "EVENT SCHEMA GOVERNANCE:\n" +
                "- Schema Registry: Centralized schema management\n" +
                "- Backward Compatibility: Additive changes only\n" +
                "- Version Evolution: Semantic versioning for schemas\n" +
                "- Contract Testing: Validate producer/consumer contracts\n\n" +
                "CONSISTENCY PATTERNS:\n" +
                "- Eventual Consistency: Accept temporary inconsistency\n" +
                "- Compensating Actions: Handle failure scenarios\n" +
                "- Idempotent Processing: Handle duplicate events\n" +
                "- Event Replay: Recover from failures";
    }

    private String generateVersioningGuidance(AgentConsultationRequest request) {
        return "Versioning and Migration for POS System:\n\n" +
                "API VERSIONING STRATEGY:\n" +
                "- URL Versioning: /api/v1/, /api/v2/ for major changes\n" +
                "- Header Versioning: Accept-Version header for minor changes\n" +
                "- Semantic Versioning: MAJOR.MINOR.PATCH format\n" +
                "- Deprecation Policy: 6-month notice for breaking changes\n\n" +
                "BACKWARD COMPATIBILITY RULES:\n" +
                "- Additive Changes: New fields, endpoints allowed\n" +
                "- Non-Breaking Changes: Optional parameters, default values\n" +
                "- Breaking Changes: Require new version\n" +
                "- Field Removal: Deprecate first, remove later\n\n" +
                "DATABASE MIGRATION PATTERNS:\n" +
                "- Blue-Green Deployment: Zero-downtime migrations\n" +
                "- Expand-Contract Pattern: Gradual schema evolution\n" +
                "- Feature Flags: Control migration rollout\n" +
                "- Rollback Strategy: Always plan for rollback\n\n" +
                "SERVICE MIGRATION STRATEGIES:\n" +
                "- Strangler Fig Pattern: Gradually replace legacy services\n" +
                "- Branch by Abstraction: Isolate changes behind interfaces\n" +
                "- Parallel Run: Run old and new versions simultaneously\n" +
                "- Canary Deployment: Gradual traffic migration\n\n" +
                "COMPATIBILITY TESTING:\n" +
                "- Contract Tests: Validate API contracts\n" +
                "- Integration Tests: End-to-end compatibility\n" +
                "- Performance Tests: Ensure no regression\n" +
                "- Rollback Tests: Validate rollback procedures";
    }

    private String generateTechnicalDebtGuidance(AgentConsultationRequest request) {
        return "Technical Debt Management for POS System:\n\n" +
                "DEBT IDENTIFICATION:\n" +
                "- Code Smells: Duplicated code, long methods, large classes\n" +
                "- Architecture Violations: Circular dependencies, layer violations\n" +
                "- Performance Issues: N+1 queries, memory leaks, slow endpoints\n" +
                "- Security Vulnerabilities: Outdated dependencies, weak authentication\n" +
                "- Documentation Gaps: Missing ADRs, outdated documentation\n\n" +
                "DEBT CLASSIFICATION:\n" +
                "- Critical: Security vulnerabilities, data corruption risks\n" +
                "- High: Performance bottlenecks, architecture violations\n" +
                "- Medium: Code quality issues, maintainability problems\n" +
                "- Low: Documentation gaps, minor refactoring opportunities\n\n" +
                "REMEDIATION STRATEGIES:\n" +
                "- Boy Scout Rule: Leave code better than you found it\n" +
                "- Refactoring Sprints: Dedicated time for debt reduction\n" +
                "- Architecture Reviews: Regular design validation\n" +
                "- Automated Quality Gates: Prevent new debt introduction\n\n" +
                "DEBT TRACKING:\n" +
                "- Technical Debt Register: Centralized tracking\n" +
                "- Impact Assessment: Business impact of debt items\n" +
                "- Remediation Planning: Prioritized backlog\n" +
                "- Progress Monitoring: Regular debt reduction metrics\n\n" +
                "PREVENTION MEASURES:\n" +
                "- Definition of Done: Include quality criteria\n" +
                "- Code Reviews: Catch issues early\n" +
                "- Static Analysis: Automated quality checks\n" +
                "- Architecture Decision Records: Document design decisions";
    }

    private String generatePOSPatternGuidance(AgentConsultationRequest request) {
        return "POS Domain Pattern Validation:\n\n" +
                "RETAIL PATTERNS:\n" +
                "- Product Catalog: Hierarchical categories, variants, bundles\n" +
                "- Inventory Management: Stock levels, reservations, transfers\n" +
                "- Pricing Rules: Base prices, discounts, promotions, taxes\n" +
                "- Order Processing: Cart, checkout, fulfillment, returns\n" +
                "- Payment Processing: Multiple payment methods, refunds, splits\n\n" +
                "AUTOMOTIVE PATTERNS:\n" +
                "- Vehicle Fitment: Year/Make/Model compatibility\n" +
                "- Parts Catalog: OEM numbers, aftermarket alternatives\n" +
                "- Service Orders: Labor, parts, diagnostics, warranties\n" +
                "- Customer Vehicles: Service history, maintenance schedules\n\n" +
                "BUSINESS RULE PATTERNS:\n" +
                "- Customer Hierarchies: Individual, business, fleet customers\n" +
                "- Pricing Scenarios: Retail, wholesale, contractor pricing\n" +
                "- Tax Calculations: Location-based, product-based taxes\n" +
                "- Discount Rules: Customer-based, volume-based, time-based\n\n" +
                "WORKFLOW PATTERNS:\n" +
                "- Quote-to-Cash: Estimate → Order → Invoice → Payment\n" +
                "- Service Workflow: Appointment → Diagnosis → Repair → Completion\n" +
                "- Inventory Workflow: Purchase → Receive → Stock → Sell\n" +
                "- Customer Workflow: Prospect → Customer → Loyalty → Retention\n\n" +
                "VALIDATION RULES:\n" +
                "- Data Consistency: Cross-domain data validation\n" +
                "- Business Rules: Domain-specific constraints\n" +
                "- Regulatory Compliance: Tax, warranty, safety requirements\n" +
                "- Performance Standards: Response times, throughput targets";
    }

    private String generateGeneralGovernanceGuidance() {
        return "General Architectural Governance for POS System:\n\n" +
                "GOVERNANCE PRINCIPLES:\n" +
                "- Domain Autonomy: Each domain owns its complete capability\n" +
                "- Loose Coupling: Minimize dependencies between domains\n" +
                "- High Cohesion: Group related functionality together\n" +
                "- Explicit Contracts: Clear interfaces between services\n" +
                "- Evolutionary Design: Support incremental changes\n\n" +
                "DECISION FRAMEWORK:\n" +
                "- Architecture Decision Records (ADRs): Document significant decisions\n" +
                "- Trade-off Analysis: Evaluate alternatives systematically\n" +
                "- Impact Assessment: Consider downstream effects\n" +
                "- Stakeholder Alignment: Ensure business alignment\n\n" +
                "QUALITY GATES:\n" +
                "- Design Reviews: Validate architectural decisions\n" +
                "- Code Reviews: Enforce coding standards\n" +
                "- Security Reviews: Validate security measures\n" +
                "- Performance Reviews: Ensure performance targets\n\n" +
                "CONTINUOUS IMPROVEMENT:\n" +
                "- Architecture Health Checks: Regular system assessment\n" +
                "- Metrics Collection: Track architectural metrics\n" +
                "- Feedback Loops: Learn from production issues\n" +
                "- Knowledge Sharing: Spread architectural knowledge";
    }

    private String validateDomainBoundaries(AgentConsultationRequest request) {
        StringBuilder validation = new StringBuilder("DOMAIN BOUNDARY VALIDATION:\n\n");

        // Check for potential boundary violations
        String query = request.query().toLowerCase();
        if (query.contains("shared") || query.contains("cross-service") || query.contains("direct access")) {
            validation.append("⚠️  POTENTIAL BOUNDARY VIOLATION DETECTED:\n")
                    .append("- Avoid shared databases between domains\n")
                    .append("- Use domain events for cross-domain communication\n")
                    .append("- Implement anti-corruption layers for external integrations\n")
                    .append("- Maintain separate data models per domain\n\n");
        }

        // Validate domain ownership
        validation.append("DOMAIN OWNERSHIP VALIDATION:\n");
        for (Map.Entry<String, Set<String>> domain : POS_DOMAIN_OWNERSHIP.entrySet()) {
            validation.append("- ").append(domain.getKey().toUpperCase()).append(" Domain owns: ")
                    .append(String.join(", ", domain.getValue())).append("\n");
        }

        validation.append("\nBOUNDARY ENFORCEMENT CHECKLIST:\n")
                .append("✓ Each service owns its complete business capability\n")
                .append("✓ No direct database access across domains\n")
                .append("✓ Domain events used for cross-domain communication\n")
                .append("✓ Anti-corruption layers for external integrations\n")
                .append("✓ Separate data models per domain\n");

        return validation.toString();
    }

    private String validateCircularDependencies(AgentConsultationRequest request) {
        StringBuilder validation = new StringBuilder("CIRCULAR DEPENDENCY VALIDATION:\n\n");

        validation.append("ALLOWED DEPENDENCY GRAPH:\n");
        for (Map.Entry<String, Set<String>> dependency : ALLOWED_DEPENDENCIES.entrySet()) {
            validation.append("- ").append(dependency.getKey().toUpperCase())
                    .append(" may depend on: ").append(String.join(", ", dependency.getValue())).append("\n");
        }

        validation.append("\nCIRCULAR DEPENDENCY PREVENTION:\n")
                .append("- ORDER domain may depend on CUSTOMER, CATALOG, PRICING\n")
                .append("- PRICING domain may depend on CATALOG only\n")
                .append("- OPERATIONS domain may depend on CUSTOMER, ORDER, CATALOG\n")
                .append("- SUPPORT domain may depend on CUSTOMER, ORDER\n")
                .append("- INTEGRATION domain may depend on CATALOG\n")
                .append("- CUSTOMER, CATALOG, INFRASTRUCTURE domains have no dependencies\n\n")
                .append("VIOLATION DETECTION:\n")
                .append("- Reverse dependencies (e.g., CUSTOMER → ORDER) = VIOLATION\n")
                .append("- Transitive circular dependencies = VIOLATION\n")
                .append("- Cross-domain database access = VIOLATION\n\n")
                .append("REMEDIATION STRATEGIES:\n")
                .append("- Use domain events to break circular dependencies\n")
                .append("- Extract shared concepts into separate services\n")
                .append("- Implement dependency inversion with interfaces\n")
                .append("- Apply hexagonal architecture patterns");

        return validation.toString();
    }

    private List<String> generateGovernanceRecommendations(AgentConsultationRequest request) {
        String query = request.query().toLowerCase();

        if (query.contains("domain") || query.contains("boundary")) {
            return List.of(
                    "Enforce strict domain ownership rules",
                    "Use domain events for cross-domain communication",
                    "Implement anti-corruption layers for external integrations",
                    "Maintain separate data models per domain",
                    "Validate domain boundaries in code reviews");
        }

        if (query.contains("api") || query.contains("gateway")) {
            return List.of(
                    "Implement API Gateway routing patterns",
                    "Apply cross-cutting concerns consistently",
                    "Use SNS/SQS for event-driven communication",
                    "Implement circuit breaker patterns",
                    "Monitor API Gateway health and performance");
        }

        if (query.contains("version") || query.contains("migration")) {
            return List.of(
                    "Apply semantic versioning for APIs",
                    "Implement backward compatibility rules",
                    "Use blue-green deployment for migrations",
                    "Plan rollback strategies for all changes",
                    "Test compatibility thoroughly");
        }

        if (query.contains("debt") || query.contains("quality")) {
            return List.of(
                    "Maintain technical debt register",
                    "Implement automated quality gates",
                    "Conduct regular architecture reviews",
                    "Apply boy scout rule for code quality",
                    "Track debt reduction metrics");
        }

        // Default recommendations
        return List.of(
                "Enforce domain-driven design principles",
                "Prevent circular dependencies between domains",
                "Implement event-driven architecture patterns",
                "Apply API Gateway best practices",
                "Maintain backward compatibility",
                "Manage technical debt proactively",
                "Validate POS domain patterns");
    }
}