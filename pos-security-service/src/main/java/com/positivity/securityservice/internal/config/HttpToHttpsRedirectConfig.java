package com.positivity.securityservice.internal.config;

// NOTE: TomcatServletWebServerFactory package removed/restructured in Spring Boot 4.0
// TODO: Research Spring Boot 4.0 embedded server configuration API if HTTP-to-HTTPS redirect is needed
// Alternative: Handle HTTPS redirect at load balancer/reverse proxy level (recommended)
// import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
// import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HttpToHttpsRedirectConfig {
    // @Bean
    // public WebServerFactoryCustomizer<TomcatServletWebServerFactory>
    // servletContainer() {
    // return factory -> {
    // Connector connector = new Connector("HTTP/1.1");
    // connector.setScheme("http");
    // connector.setPort(8080);
    // connector.setSecure(false);
    // connector.setRedirectPort(8443);
    // factory.addAdditionalTomcatConnectors(connector);
    // };
    // }
}
