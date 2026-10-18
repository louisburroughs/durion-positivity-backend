package com.positivity.inboundmail.internal.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The one RestClient builder this module needs: direct-URL startup registration of its event
 * types against pos-event-receiver. The edge calls no other service over HTTP (ADR-0044: it is called, never calling, and its
 * inputs arrive as mail), so there is no load-balanced builder. Bounded timeouts keep a
 * hung receiver from blocking startup.
 */
@Configuration
public class RestClientConfig {

    @Value("${pos.restclient.connect.timeout:2000}")
    private int connectTimeoutMs;

    @Value("${pos.restclient.read.timeout:5000}")
    private int readTimeoutMs;

    @Bean
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder().requestFactory(factory);
    }
}
