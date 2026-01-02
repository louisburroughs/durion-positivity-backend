package com.pos.agent.impl;

import com.pos.agent.core.AbstractAgent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.AgentProcessingState;
import com.pos.agent.framework.model.AgentType;

import java.util.List;

/**
 * EventDrivenArchitectureAgent - provides event-driven architecture guidance
 * and recommendations
 * Extends AbstractAgent for common validation and failure handling
 */
public class EventDrivenArchitectureAgent extends AbstractAgent {

    public EventDrivenArchitectureAgent() {
        super(AgentType.EVENT_DRIVEN_ARCHITECTURE, List.of(
                "event-sourcing",
                "message-brokers",
                "event-schema-design",
                "saga-patterns",
                "event-streaming"));
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
}
