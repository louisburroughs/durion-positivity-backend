package com.positivity.documents.internal.config;

import com.positivity.security.common.GatewaySecurityConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Documents service security configuration.
 *
 * <p>Imports shared gateway security so the service uses the standard auth
 * model, including public OpenAPI and Swagger endpoints.</p>
 */
@Configuration
@Import(GatewaySecurityConfig.class)
public class SecurityConfig {}
