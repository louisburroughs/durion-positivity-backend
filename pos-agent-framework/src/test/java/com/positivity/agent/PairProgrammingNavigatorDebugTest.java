package com.positivity.agent;

import com.positivity.agent.impl.PairProgrammingNavigatorAgent;
import org.junit.jupiter.api.Test;

import java.util.Map;

public class PairProgrammingNavigatorDebugTest {

    @Test
    void debugLoopDetection() {
        PairProgrammingNavigatorAgent agent = new PairProgrammingNavigatorAgent();

        String context = "The service layer keeps getting refactored without progress";
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "collaboration", context, Map.of("requestType", "loop-detection"));

        AgentGuidanceResponse response = agent.provideGuidance(request).join();

        System.out.println("Context: " + context);
        System.out.println("Response Status: " + response.status());
        System.out.println("Response Guidance: " + response.guidance());
        System.out.println("Contains stop phrase: " + containsMandatoryStopPhrase(response.guidance()));
    }

    private boolean containsMandatoryStopPhrase(String guidance) {
        if (guidance == null)
            return false;

        String[] stopPhrases = {
                "We are looping.",
                "This is the third pass on the same solution.",
                "We are re-solving a problem that hasn't changed.",
                "We are churning entities.",
                "The data model is moving, but the behavior is not.",
                "We're redesigning entities to compensate for unclear behavior.",
                "We are creating services to avoid making a decision.",
                "This is service sprawl.",
                "We are wrapping CRUD without adding policy.",
                "Business logic is leaking into the screen layer.",
                "Screens are doing policy work.",
                "This logic does not belong in a transition.",
                "We are crossing a domain boundary.",
                "This creates hidden coupling between domains.",
                "This violates the service contract boundary.",
                "We are leaning on the framework instead of modeling the problem.",
                "This is a framework feature in search of a use case.",
                "Framework is being used as a crutch here.",
                "Momentum has stalled.",
                "We are stuck in decision churn.",
                "We need to collapse options."
        };

        for (String phrase : stopPhrases) {
            if (guidance.contains(phrase)) {
                System.out.println("Found stop phrase: " + phrase);
                return true;
            }
        }
        return false;
    }
}