package com.positivity.mcp.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * #2074: registers {@link TranscriptionProperties} ({@code mcp.transcription.*}).
 *
 * <p>The speech-to-text client itself is a component in {@code internal.client} that reads these
 * properties (the module's dependency direction is client → config, never config → client). It is
 * independent of the Ollama chat and embedding configuration: no OpenAI model is exposed as a bean.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TranscriptionProperties.class)
public class TranscriptionConfiguration {}
