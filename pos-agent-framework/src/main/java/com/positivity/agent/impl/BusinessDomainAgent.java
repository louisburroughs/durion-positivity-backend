package com.positivity.agent.impl;

import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import com.positivity.agent.AgentPerformanceSpec;
import com.positivity.agent.BaseAgent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * Business Domain Agent (REQ-010)
 * 
 * Provides specialized POS domain knowledge and business process modeling
 * guidance.
 * Ensures proper integration with external services and event-driven patterns.
 * 
 * Requirements:
 * - REQ-010.1: POS domain knowledge guidance
 * - REQ-010.2: Payment processor and third-party integration guidance
 * - REQ-010.3: Event-driven pattern implementation
 * - REQ-010.4: Business rule implementation validation
 * - REQ-010.5: Distributed transaction and eventual consistency guidance
 */
@Component
public class BusinessDomainAgent extends BaseAgent {

    // POS Domain Knowledge Patterns
    private static final Map<String, String> POS_DOMAIN_PATTERNS = Map.of(
            "sales-transaction", "Implement atomic sales transactions with inventory updates and payment processing",
            "inventory-management", "Use event-driven inventory updates with eventual consistency patterns",
            "customer-lifecycle", "Model customer registration, loyalty programs, and service history",
            "payment-processing", "Implement secure payment flows with external processor integration",
            "order-fulfillment", "Design order workflows with status tracking and notification events",
            "vehicle-services", "Model automotive service processes with parts compatibility validation",
            "pricing-rules", "Implement dynamic pricing with business rule engines and validation",
            "reporting-analytics", "Design business intelligence patterns with data aggregation");

    // External Integration Patterns
    private static final Map<String, String> INTEGRATION_PATTERNS = Map.of(
            "payment-gateway", "Stripe/PayPal integration with webhook handling and idempotency",
            "vehicle-reference", "NHTSA/CarAPI integration with caching and fallback strategies",
            "notification-service", "SNS/SQS event publishing for business process notifications",
            "audit-logging", "Comprehensive audit trails for compliance and business analysis",
            "third-party-apis", "Circuit breaker patterns with retry logic and graceful degradation",
            "webhook-handling", "Secure webhook processing with signature validation and replay protection");

    // Business Rule Validation Patterns
    private static final Map<String, String> BUSINESS_RULE_PATTERNS = Map.of(
            "inventory-constraints", "Validate stock availability before order confirmation",
            "pricing-validation", "Ensure pricing rules comply with business policies and regulations",
            "customer-eligibility", "Validate customer status and credit limits for transactions",
            "service-compatibility", "Verify vehicle-service compatibility using reference data",
            "compliance-rules", "Implement regulatory compliance checks for automotive services",
            "business-hours", "Enforce business hour constraints for service scheduling");

    // Event-Driven Architecture Patterns
    private static final Map<String, String> EVENT_PATTERNS = Map.of(
            "order-events", "OrderCreated, OrderConfirmed, OrderShipped, OrderCompleted event flows",
            "inventory-events", "StockUpdated, LowStockAlert, RestockRequired event patterns",
            "payment-events", "PaymentInitiated, PaymentCompleted, PaymentFailed event handling",
            "customer-events", "CustomerRegistered, LoyaltyUpdated, ServiceCompleted event flows",
            "notification-events", "EmailSent, SMSDelivered, PushNotificationSent event tracking",
            "audit-events", "UserAction, SystemChange, ComplianceCheck event logging");

    public BusinessDomainAgent() {
        super(
                "business-domain-agent",
                "Business Domain Agent",
                "business",
                Set.of("pos-domain", "business-rules", "payment-integration", "event-driven",
                        "workflow-design", "third-party-apis", "distributed-transactions",
                        "eventual-consistency", "automotive-services", "compliance"),
                Set.of("architecture-agent", "integration-gateway-agent"),
                AgentPerformanceSpec.defaultSpec());
    }

    @Override
    public boolean canHandle(AgentConsultationRequest request) {
        // Handle business, pos, and domain requests
        String requestDomain = request.domain().toLowerCase();
        return requestDomain.equals("business") ||
                requestDomain.equals("pos") ||
                requestDomain.equals("domain") ||
                super.canHandle(request);
    }

    @Override
    protected AgentGuidanceResponse processGuidanceRequest(AgentConsultationRequest request) {
        String query = request.query().toLowerCase();
        List<String> recommendations = new ArrayList<>();

        // REQ-010.1: POS domain knowledge guidance
        if (containsAny(query, "pos", "point of sale", "sales", "retail", "transaction", "customer", "loyalty",
                "inventory", "automotive", "vehicle", "service", "scheduling", "appointment")) {
            recommendations.addAll(providePOSDomainGuidance(query));
        }

        // REQ-010.2: Payment processor and third-party integration guidance
        if (containsAny(query, "payment", "stripe", "paypal", "integration", "api", "webhook")) {
            recommendations.addAll(provideIntegrationGuidance(query));
        }

        // REQ-010.3: Event-driven pattern implementation
        if (containsAny(query, "event", "message", "sns", "sqs", "notification", "async")) {
            recommendations.addAll(provideEventDrivenGuidance(query));
        }

        // REQ-010.4: Business rule implementation validation
        if (containsAny(query, "rule", "validation", "constraint", "policy", "compliance")) {
            recommendations.addAll(provideBusinessRuleGuidance(query));
        }

        // REQ-010.5: Distributed transaction and eventual consistency guidance
        if (containsAny(query, "transaction", "consistency", "distributed", "saga", "compensation")) {
            recommendations.addAll(provideDistributedTransactionGuidance(query));
        }

        // Default business domain guidance
        if (recommendations.isEmpty()) {
            recommendations.addAll(provideDefaultBusinessGuidance(query));
        }

        String guidance = generateBusinessDomainGuidance(request, recommendations);

        return AgentGuidanceResponse.success(
                request.requestId(),
                getId(),
                guidance,
                calculateConfidence(query, recommendations),
                recommendations,
                Duration.ofMillis(300) // Business domain analysis processing time
        );
    }

    private String generateBusinessDomainGuidance(AgentConsultationRequest request, List<String> recommendations) {
        StringBuilder guidance = new StringBuilder();
        guidance.append("POS Business Domain Analysis for ").append(request.domain()).append(":\n\n");

        String query = request.query().toLowerCase();

        if (containsAny(query, "pos", "sales", "transaction")) {
            guidance.append("POS Domain Patterns:\n");
            guidance.append("- Implement atomic sales transactions with proper isolation levels\n");
            guidance.append("- Use event-driven inventory updates for real-time stock management\n");
            guidance.append("- Design customer lifecycle management with loyalty integration\n\n");
        }

        if (containsAny(query, "payment", "integration")) {
            guidance.append("Payment Integration Patterns:\n");
            guidance.append("- Implement secure payment processor integration with idempotency\n");
            guidance.append("- Use webhook handlers with signature validation\n");
            guidance.append("- Design payment reconciliation for financial accuracy\n\n");
        }

        if (containsAny(query, "event", "async")) {
            guidance.append("Event-Driven Architecture:\n");
            guidance.append("- Design domain events for business state changes\n");
            guidance.append("- Use saga pattern for distributed business transactions\n");
            guidance.append("- Implement event sourcing for audit trails\n\n");
        }

        guidance.append("Business Domain Recommendations:\n");
        for (int i = 0; i < Math.min(recommendations.size(), 5); i++) {
            guidance.append("- ").append(recommendations.get(i)).append("\n");
        }

        return guidance.toString();
    }

    private List<String> providePOSDomainGuidance(String query) {
        List<String> guidance = new ArrayList<>();

        if (query.contains("sales") || query.contains("transaction")) {
            guidance.add(
                    "Implement atomic sales transactions using Spring @Transactional with proper isolation levels");
            guidance.add("Design sales entities: Sale, SaleItem, Payment, Customer with proper relationships");
            guidance.add("Use optimistic locking for inventory updates during sales transactions");
            guidance.add("Implement sales tax calculation with configurable tax rules by location");
        }

        if (query.contains("inventory")) {
            guidance.add("Design inventory entities: Product, Stock, Location, Movement with audit trails");
            guidance.add("Implement event-driven inventory updates using Spring Events or messaging");
            guidance.add("Use eventual consistency for inventory synchronization across locations");
            guidance.add("Implement low stock alerts with configurable thresholds");
        }

        if (query.contains("customer")) {
            guidance.add("Model customer lifecycle: Registration, Profile, Loyalty, Service History");
            guidance.add("Implement customer segmentation for targeted marketing and pricing");
            guidance.add("Design customer communication preferences and notification settings");
            guidance.add("Use customer hierarchy for B2B scenarios (corporate accounts, fleet management)");
            guidance.add("Implement event-driven customer registration and loyalty updates");
        }

        if (query.contains("automotive") || query.contains("vehicle") || query.contains("service")
                || query.contains("scheduling") || query.contains("appointment")) {
            guidance.add("Integrate vehicle reference data (VIN decoding, parts compatibility)");
            guidance.add("Model vehicle service history and maintenance schedules");
            guidance.add("Implement parts fitment validation using external APIs");
            guidance.add("Design service appointment scheduling with technician availability");
            guidance.add("Use event-driven vehicle service updates and maintenance notifications");
        }

        return guidance;
    }

    private List<String> provideIntegrationGuidance(String query) {
        List<String> guidance = new ArrayList<>();

        if (query.contains("payment") || query.contains("stripe") || query.contains("paypal")) {
            guidance.add("Implement payment processor integration with idempotency keys");
            guidance.add("Use webhook handlers with signature validation for payment status updates");
            guidance.add("Implement payment retry logic with exponential backoff");
            guidance.add("Store payment tokens securely, never store raw card data");
            guidance.add("Implement payment reconciliation processes for financial accuracy");
            guidance.add("Use event-driven payment processing with PaymentInitiated and PaymentCompleted events");
        }

        if (query.contains("vehicle") || query.contains("nhtsa") || query.contains("carapi")) {
            guidance.add("Implement vehicle reference API integration with caching strategies");
            guidance.add("Use circuit breaker pattern for external API resilience");
            guidance.add("Implement fallback mechanisms for offline vehicle data access");
            guidance.add("Cache vehicle data with appropriate TTL based on data volatility");
        }

        if (query.contains("webhook")) {
            guidance.add("Implement webhook signature validation for security");
            guidance.add("Use idempotency keys to handle duplicate webhook deliveries");
            guidance.add("Implement webhook retry mechanisms with exponential backoff");
            guidance.add("Log all webhook events for audit and debugging purposes");
        }

        if (query.contains("api") || query.contains("integration") || query.contains("third-party")) {
            guidance.add("Implement circuit breaker pattern for third-party API resilience");
            guidance.add("Use event-driven integration patterns with async processing");
            guidance.add("Implement API rate limiting and throttling mechanisms");
            guidance.add("Design fallback strategies for API failures and timeouts");
            guidance.add("Use correlation IDs for distributed tracing across API calls");
        }

        return guidance;
    }

    private List<String> provideEventDrivenGuidance(String query) {
        return Arrays.asList(
                "Design domain events that represent business state changes",
                "Use event sourcing for audit trails and business process reconstruction",
                "Implement event handlers with idempotency to handle duplicate events",
                "Use saga pattern for distributed business transactions",
                "Design event schemas with versioning for backward compatibility",
                "Implement event ordering guarantees where business logic requires it",
                "Use dead letter queues for failed event processing with manual intervention",
                "Implement event replay capabilities for system recovery scenarios");
    }

    private List<String> provideBusinessRuleGuidance(String query) {
        return Arrays.asList(
                "Implement business rules as separate, testable components",
                "Use rule engines (Drools) for complex business logic with frequent changes",
                "Validate business constraints at service boundaries, not just UI",
                "Implement audit logging for all business rule evaluations",
                "Design configurable business rules for different business contexts",
                "Use specification pattern for complex business rule composition",
                "Implement business rule versioning for regulatory compliance",
                "Design business rule testing with comprehensive scenario coverage",
                "Use event-driven compliance monitoring and rule evaluation notifications");
    }

    private List<String> provideDistributedTransactionGuidance(String query) {
        return Arrays.asList(
                "Use saga pattern for distributed business transactions across microservices",
                "Implement compensation actions for each step in distributed transactions",
                "Use eventual consistency with business-appropriate consistency windows",
                "Implement distributed locks only when absolutely necessary for business correctness",
                "Design idempotent operations to handle retry scenarios gracefully",
                "Use outbox pattern for reliable event publishing from database transactions",
                "Implement transaction monitoring and alerting for business process visibility",
                "Design rollback strategies that maintain business data integrity");
    }

    private List<String> provideDefaultBusinessGuidance(String query) {
        return Arrays.asList(
                "Follow domain-driven design principles for business logic organization",
                "Implement comprehensive audit trails for business process compliance",
                "Design APIs that reflect business capabilities, not technical implementation",
                "Use business-friendly error messages and status codes",
                "Implement business metrics and KPIs for operational visibility",
                "Design data models that reflect real business relationships and constraints");
    }

    private boolean containsAny(String text, String... keywords) {
        return Arrays.stream(keywords).anyMatch(text::contains);
    }

    private double calculateConfidence(String query, List<String> recommendations) {
        if (recommendations.isEmpty()) {
            return 0.3;
        }

        // Higher confidence for specific business domain queries
        double confidence = 0.7;

        if (containsAny(query, "pos", "sales", "inventory", "payment", "customer")) {
            confidence += 0.2;
        }

        if (containsAny(query, "business", "rule", "process", "workflow")) {
            confidence += 0.1;
        }

        return Math.min(confidence, 1.0);
    }
}