package com.positivity.catalog.internal.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

@Configuration
public class OpenApiConfig {

        private static final Pattern AUTHORITY_PATTERN = Pattern.compile("'([^']+)'");

        @Bean
        public OpenAPI customOpenAPI() {
                return new OpenAPI()
                                .info(new Info()
                                                .title("Positivity Product API")
                                                .description("API documentation for the Product service, accessible via the API Gateway.")
                                                .version("v1")
                                                .contact(
                                                                new Contact().email("louis.burroughs@gmail.com")
                                                                                .name("Durion Support Services")))
                                .servers(List.of(
                                                new Server().url("http://api-gateway.local/v1/catalog")
                                                                .description("API Gateway")))
                                .components(new Components()
                                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")));
        }

         /**
         * Produces an {@link OperationCustomizer} that reads
         * {@link org.springframework.security.access.prepost.PreAuthorize} annotations from
         * both the controller class and the handler method, extracts every {@code hasAuthority}
         * / {@code hasAnyAuthority} argument, and attaches them as the
         * {@code x-required-permissions} extension on the corresponding OpenAPI operation.
         *
         * @return an {@link OperationCustomizer} that populates {@code x-required-permissions}
         */
        @Bean
        public OperationCustomizer requiredPermissionsOperationCustomizer() {
                return (operation, handlerMethod) -> {
                        var requiredPermissions = extractRequiredPermissions(handlerMethod);
                        if (!requiredPermissions.isEmpty()) {
                                operation.addExtension("x-required-permissions", requiredPermissions);
                        }
                        return operation;
                };
        }

        private static List<String> extractRequiredPermissions(HandlerMethod handlerMethod) {
                var requiredPermissions = new LinkedHashSet<String>();
                collectAuthorities(requiredPermissions,
                                AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(),
                                                PreAuthorize.class));
                collectAuthorities(requiredPermissions,
                                AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(),
                                                PreAuthorize.class));
                return new ArrayList<>(requiredPermissions);
        }

        private static void collectAuthorities(LinkedHashSet<String> requiredPermissions, PreAuthorize preAuthorize) {
                if (preAuthorize == null) {
                        return;
                }

                Matcher matcher = AUTHORITY_PATTERN.matcher(preAuthorize.value());
                while (matcher.find()) {
                        requiredPermissions.add(matcher.group(1));
                }
        }
}
