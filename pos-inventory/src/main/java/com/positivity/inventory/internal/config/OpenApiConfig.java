package com.positivity.inventory.internal.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;

@Configuration
public class OpenApiConfig {

        private static final Pattern AUTHORITY_PATTERN = Pattern.compile("'([^']+)'");

        @Bean
        public OpenAPI customOpenAPI() {
                return new OpenAPI()
                                .info(new Info()
                                                .title("Positivity Inventory API")
                                                .description("API documentation for the Inventory service, accessible via the API Gateway.")
                                                .version("v1")
                                                .contact(
                                                                new Contact().email("louis.burroughs@gmail.com")
                                                                                .name("Durion Support Services")))
                                .tags(Arrays.asList(
                                                new Tag()
                                                                .name("Cycle Count Operations")
                                                                .description("Cycle count submission and recount operations"),
                                                new Tag()
                                                                .name("Cycle Count Query")
                                                                .description("Query endpoints for cycle count tasks and history")))
                                .components(new Components()
                                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")));
        }

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
                                AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), PreAuthorize.class));
                collectAuthorities(requiredPermissions,
                                AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), PreAuthorize.class));
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
