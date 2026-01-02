package com.pos.agent.impl;

import com.pos.agent.context.AgentContext;
import com.pos.agent.context.DefaultContext;
import com.pos.agent.core.AbstractAgent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.AgentProcessingState;
import com.pos.agent.framework.model.AgentType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DocumentationAgent
 */
public class DocumentationAgent extends AbstractAgent {

    private final Map<String, AgentContext> contextMap = new ConcurrentHashMap<>();

    public DocumentationAgent() {
        super(AgentType.DOCUMENTATION, List.of(
                "api-documentation",
                "code-documentation",
                "user-guides",
                "technical-writing",
                "documentation-generation"));
    }

    @Override
    public AgentContext getOrCreateContext(String sessionId) {
        return contextMap.computeIfAbsent(sessionId,
                sid -> DefaultContext.builder().requestId(sessionId).build());
    }

    @Override
    protected AgentResponse doProcessRequest(AgentRequest request) {
        return AgentResponse.builder()
                .status(AgentProcessingState.SUCCESS)
                .output("Documentation guidance: " + request.getAgentContext().getDescription())
                .confidence(0.8)
                .success(true)
                .recommendations(List.of("implement pattern", "configure system", "add monitoring"))
                .build();
    }

    @Override
    public String generateOutput(String query) {

        // Pre-allocate StringBuilder with estimated capacity for better performance
        StringBuilder guidance = new StringBuilder(1024);

        guidance.append("# Documentation Agent Guidance\n\n")
                .append("Request: ").append(query).append("\n\n")
                .append("## API Documentation Best Practices:\n")
                .append("- Use OpenAPI/Swagger specifications for API documentation\n")
                .append("- Implement Spring REST Docs for automatic documentation generation\n")
                .append("- Ensure endpoint schema synchronization with code\n")
                .append("- API documentation must be kept synchronized with implementation\n\n")
                .append("## Documentation Validation and Completeness:\n")
                .append("- Create completeness checklists for all documentation\n")
                .append("- Implement automated documentation metrics and coverage analysis\n")
                .append("- Use CI/CD pipeline for broken links detection\n")
                .append("- Perform regular documentation audits and quality reviews\n")
                .append("- Track documentation freshness and update frequency\n\n")
                .append("## Technical Documentation Best Practices:\n")
                .append("- Keep API and technical documentation synchronized\n")
                .append("- Provide comprehensive README documentation\n")
                .append("- Use OpenAPI for API contract documentation\n")
                .append("- Maintain endpoint specifications and schema definitions\n")
                .append("- Document all breaking API changes\n\n")
                .append("## Documentation Synchronization Mechanisms:\n")
                .append("- Automated generation from code annotations (JavaDoc)\n")
                .append("- CI/CD pipeline validation of documentation against code\n")
                .append("- Maven/Gradle plugins for automatic document generation\n")
                .append("- Version control integration for change tracking\n");

        return guidance.toString();
    }
}
