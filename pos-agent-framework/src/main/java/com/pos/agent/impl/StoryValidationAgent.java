package com.pos.agent.impl;

import com.pos.agent.core.AbstractAgent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.AgentProcessingState;
import com.pos.agent.context.AgentContext;
import com.pos.agent.context.StoryContext;
import com.pos.agent.framework.model.AgentType;

import java.util.List;

/**
 * Agent responsible for validating story requests and checking activation
 * conditions.
 * This agent determines whether a story meets the requirements for processing:
 * - Must be a backend story (isBackendStory = true)
 * - Must have valid content (isValidContent = true)
 * 
 * Implements Requirements 1.5 from upgrade-story-quality feature.
 */
public class StoryValidationAgent extends AbstractAgent {

    public StoryValidationAgent() {
        super(AgentType.BUSINESS_DOMAIN, List.of(
                "story-validation",
                "activation-conditions",
                "backend-story-check",
                "content-validation",
                "story-requirements"));
    }

    /**
     * Check if this agent can handle the given request.
     * Only handles requests in the "story" domain that meet activation conditions.
     *
     * @param request The request to check
     * @return true if this agent can handle the request, false otherwise
     */
    public boolean canHandle(AgentRequest request) {
        AgentContext contextObj = request.getAgentContext();

        // Only handle requests in the "story" domain
        if (!"story".equals(contextObj.getAgentDomain())) {
            return false;
        }

        // Check activation conditions
        return isBackendStory(contextObj) && isValidContent(contextObj);
    }

    @Override
    protected AgentResponse doProcessRequest(AgentRequest request) {
        AgentContext contextObj = request.getAgentContext();

        if (!(contextObj instanceof StoryContext)) {
            return AgentResponse.builder()
                    .status(AgentProcessingState.FAILURE)
                    .output("Invalid context type: expected AgentContext")
                    .errorMessage("Context must be of type AgentContext")
                    .confidence(0.0)
                    .success(false)
                    .recommendations(List.of("Use AgentContext for story validation"))
                    .build();
        }

        // Check if agent can handle this request
        if (!canHandle(request)) {
            // Determine which condition failed
            boolean isBackend = isBackendStory(contextObj);
            boolean isValid = isValidContent(contextObj);

            String reason;
            List<String> recommendations;
            if (!isBackend && !isValid) {
                reason = "Story is not a backend story and content is not valid";
                recommendations = List.of(
                        "Ensure story is marked as backend story",
                        "Verify story content meets quality standards");
            } else if (!isBackend) {
                reason = "Story is not a backend story";
                recommendations = List.of("Ensure story is marked as backend story");
            } else {
                reason = "Story content is not valid";
                recommendations = List.of("Verify story content meets quality standards");
            }

            return AgentResponse.builder()
                    .status(AgentProcessingState.FAILURE)
                    .output("Story validation failed: " + reason)
                    .errorMessage("Activation conditions not met: " + reason)
                    .confidence(0.0)
                    .success(false)
                    .recommendations(recommendations)
                    .build();
        }

        // Story passed validation
        String storyName = (String) contextObj.getProperty("storyName");
        return AgentResponse.builder()
                .status(AgentProcessingState.SUCCESS)
                .output("Story validated successfully: " + storyName)
                .confidence(1.0)
                .success(true)
                .recommendations(List.of(
                        "Story ready for processing",
                        "All activation conditions met"))
                .build();
    }

    /**
     * Check if the story is marked as a backend story.
     *
     * @param context The agent context containing story properties
     * @return true if the story is a backend story, false otherwise
     */
    private boolean isBackendStory(AgentContext context) {
        Boolean isBackend = (Boolean) context.getProperty("isBackendStory");
        return isBackend != null && isBackend;
    }

    /**
     * Check if the story has valid content.
     *
     * @param context The agent context containing story properties
     * @return true if the story content is valid, false otherwise
     */
    private boolean isValidContent(AgentContext context) {
        Boolean isValid = (Boolean) context.getProperty("isValidContent");
        return isValid != null && isValid;
    }
}
