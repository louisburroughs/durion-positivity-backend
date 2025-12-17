package com.positivity.agent.controller;

import com.positivity.agent.Agent;
import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import com.positivity.agent.registry.AgentRegistry;
import com.positivity.agent.registry.RegistryHealthStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * REST controller for agent consultation services
 */
@RestController
@RequestMapping("/api/agents")
public class AgentConsultationController {
    
    private final AgentRegistry agentRegistry;
    
    public AgentConsultationController(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }
    
    /**
     * Get all available agents
     */
    @GetMapping
    public ResponseEntity<List<Agent>> getAllAgents() {
        List<Agent> agents = agentRegistry.getAllAgents();
        return ResponseEntity.ok(agents);
    }
    
    /**
     * Get agents for a specific domain
     */
    @GetMapping("/domain/{domain}")
    public ResponseEntity<List<Agent>> getAgentsForDomain(@PathVariable String domain) {
        List<Agent> agents = agentRegistry.getAgentsForDomain(domain);
        return ResponseEntity.ok(agents);
    }
    
    /**
     * Get available agents
     */
    @GetMapping("/available")
    public ResponseEntity<List<Agent>> getAvailableAgents() {
        List<Agent> agents = agentRegistry.getAvailableAgents();
        return ResponseEntity.ok(agents);
    }
    
    /**
     * Consult an agent for guidance
     */
    @PostMapping("/consult")
    public CompletableFuture<ResponseEntity<AgentGuidanceResponse>> consultAgent(
            @RequestBody ConsultationRequestDto requestDto) {
        
        AgentConsultationRequest request = AgentConsultationRequest.create(
            requestDto.domain(),
            requestDto.query(),
            requestDto.context() != null ? requestDto.context() : Map.of()
        );
        
        return agentRegistry.consultBestAgent(request)
                .thenApply(response -> ResponseEntity.ok(response));
    }
    
    /**
     * Get registry health status
     */
    @GetMapping("/health")
    public ResponseEntity<RegistryHealthStatus> getRegistryHealth() {
        RegistryHealthStatus health = agentRegistry.getHealthStatus();
        return ResponseEntity.ok(health);
    }
    
    /**
     * Get specific agent by ID
     */
    @GetMapping("/{agentId}")
    public ResponseEntity<Agent> getAgent(@PathVariable String agentId) {
        return agentRegistry.getAgent(agentId)
                .map(agent -> ResponseEntity.ok(agent))
                .orElse(ResponseEntity.notFound().build());
    }
    
    /**
     * DTO for consultation requests
     */
    public record ConsultationRequestDto(
        String domain,
        String query,
        Map<String, Object> context
    ) {}
}