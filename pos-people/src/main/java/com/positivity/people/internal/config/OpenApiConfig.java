package com.positivity.people.internal.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.ArrayList;
import java.util.Arrays;
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

@Configuration
public class OpenApiConfig {

    private static final Pattern AUTHORITY_PATTERN = Pattern.compile("'([^']+)'");

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("POS Human Resources Service API")
                        .description(
                                "Human Resources service for employee management, payroll, and benefits administration")
                        .version("v1")
                        .contact(
                                new Contact().email("louis.burroughs@gmail.com").name("Durion Support Services")))
                .tags(Arrays.asList(
                        new Tag().name("People API").description("Operations related to people records"),
                        new Tag()
                                .name("People Availability API")
                                .description("Operations for querying people availability"),
                        new Tag()
                                .name("People Reports API")
                                .description("Reporting endpoints for people and attendance data"),
                        new Tag().name("People - TimeEntries").description("Time entry adjustments and related APIs"),
                        new Tag().name("Time Entry Approval API").description("Approve/reject time entries (batch)"),
                        new Tag().name("People - Exceptions").description("Time entry exception APIs"),
                        new Tag()
                                .name("Work Sessions API")
                                .description("Operations for managing work sessions and breaks")))
                .components(new Components()
                        .addSecuritySchemes(
                                "bearerAuth",
                                new SecurityScheme()
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
        collectAuthorities(
                requiredPermissions,
                AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), PreAuthorize.class));
        collectAuthorities(
                requiredPermissions,
                AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), PreAuthorize.class));
        if (requiredPermissions.isEmpty() && isAuthenticatedOrAbsent(handlerMethod)) {
            requiredPermissions.add("AUTHENTICATED");
        }
        return new ArrayList<>(requiredPermissions);
    }

    private static boolean isAuthenticatedOrAbsent(HandlerMethod handlerMethod) {
        PreAuthorize methodLevel =
                AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), PreAuthorize.class);
        if (methodLevel != null) {
            return "isAuthenticated()".equals(methodLevel.value());
        }
        PreAuthorize classLevel =
                AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), PreAuthorize.class);
        return classLevel == null || "isAuthenticated()".equals(classLevel.value());
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
