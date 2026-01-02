package com.pos.agent.impl;

import com.pos.agent.context.AgentContext;
import com.pos.agent.context.EventDrivenContext;
import com.pos.agent.core.AbstractAgent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.AgentProcessingState;
import com.pos.agent.framework.model.AgentType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * EventDrivenArchitectureAgent - provides event-driven architecture guidance
 * and recommendations
 * Extends AbstractAgent for common validation and failure handling
 */
public class EventDrivenArchitectureAgent extends AbstractAgent {

    protected static final Map<String, AgentContext> CONTEXT_MAP = new ConcurrentHashMap<>();

    public EventDrivenArchitectureAgent() {
        super(AgentType.EVENT_DRIVEN_ARCHITECTURE, List.of(
                "event-sourcing",
                "message-brokers",
                "event-schema-design",
                "saga-patterns",
                "event-streaming"));
    }

     @Override
    public AgentContext getOrCreateContext(String sessionId) {
        return CONTEXT_MAP.computeIfAbsent(sessionId,
                sid -> EventDrivenContext.builder().requestId(sessionId).build());
    }

    @Override
    protected AgentResponse doProcessRequest(AgentRequest request) {
        return AgentResponse.builder()
                .status(AgentProcessingState.SUCCESS)
                .output("Event-driven pattern: " + request.getAgentContext().getDescription())
                .confidence(0.8)
                .success(true)
                .recommendations(List.of("implement pattern", "configure system", "add monitoring"))
                .build();
    }

    @Override
    public String generateOutput(String query) {
       
        return "Event-Driven Architecture Guidance:\n\n" +
                "For query: " + query + "\n\n" +
                "- Use message brokers like Kafka or RabbitMQ\n" +
                "- Implement event schema versioning and validation\n" +
                "- Ensure idempotent event handlers\n" +
                "- Consider dead-letter queues for failed events\n" +
                "- Implement saga patterns for distributed transactions\n";
    }

    @Override
    public void updateContext(String sessionId, String guidance) {
        EventDrivenContext ctx = (EventDrivenContext) getOrCreateContext(sessionId);

        if (guidance.contains("kafka"))
            ctx.addMessageBroker("kafka", Map.of("type", "streaming"));
        if (guidance.contains("idempotent"))
            ctx.addEventHandler("idempotent-handler", "idempotency");
        if (guidance.contains("dead letter"))
            ctx.addDeadLetterQueue("failed-events", "dlq-config");
        if (guidance.contains("event sourcing") || guidance.contains("event store"))
            ctx.addEventStore("event-store");
        if (guidance.contains("saga"))
            ctx.addSaga("saga-pattern");
    }

    @Override
    public void removeContext(String sessionId) {
        CONTEXT_MAP.remove(sessionId);
    }
}
