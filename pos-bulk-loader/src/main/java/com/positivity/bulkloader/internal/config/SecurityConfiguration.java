package com.positivity.bulkloader.internal.config;

import com.positivity.security.common.GatewaySecurityConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Slf4j
@Configuration
@Import(GatewaySecurityConfig.class)
public class SecurityConfiguration {}
