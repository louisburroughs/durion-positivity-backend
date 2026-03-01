package com.positivity.securityservice.internal.config;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

/**
 * Adds an HTTP connector that redirects traffic to HTTPS when explicitly enabled.
 *
 * <p>
 * This should typically be used only when the service itself terminates TLS. In
 * most production environments, redirecting at the ingress/load balancer layer
 * is preferred.
 */
@Configuration
@Slf4j
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnClass({ TomcatServletWebServerFactory.class, Connector.class })
@ConditionalOnProperty(prefix = "security.http-to-https", name = "enabled", havingValue = "true")
public class HttpToHttpsRedirectConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> httpToHttpsRedirectCustomizer(
            @Value("${security.http-to-https.http-port:8080}") int httpPort,
            @Value("${server.port:8443}") int httpsPort,
            @Value("${server.ssl.enabled:false}") boolean sslEnabled) {
        return factory -> {
            if (!sslEnabled) {
                log.warn(
                        "HTTP->HTTPS redirect is enabled but server SSL is disabled. Skipping redirect connector setup.");
                return;
            }
            if (httpPort == httpsPort) {
                log.warn("HTTP->HTTPS redirect not configured because http-port and server.port are both {}", httpPort);
                return;
            }

            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setScheme("http");
            connector.setPort(httpPort);
            connector.setSecure(false);
            connector.setRedirectPort(httpsPort);
            factory.addAdditionalConnectors(connector);

            log.info("Configured HTTP->HTTPS redirect connector on port {} redirecting to {}", httpPort, httpsPort);
        };
    }
}
