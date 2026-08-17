package com.positivity.image.internal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * RestClient builder for this module's platform registrations (CAP-324 #1257).
 *
 * <p>Needed because permission registration talks to pos-security-service at startup, and this
 * module had never made an outbound call before — it only served images it already held. Added with
 * the store endpoint, which is the reason there are permissions to register at all.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
