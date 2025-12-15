package com.positivity.agent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Configuration to enable scheduled tasks for agent monitoring and failover
 */
@Configuration
@EnableScheduling
public class SchedulingConfiguration {
    // This class enables @Scheduled annotations throughout the agent framework
}