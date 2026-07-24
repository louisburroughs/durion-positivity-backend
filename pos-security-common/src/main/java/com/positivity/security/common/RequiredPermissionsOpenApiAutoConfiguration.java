package com.positivity.security.common;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;

/**
 * Single source of the springdoc {@link OperationCustomizer} that publishes each operation's required
 * permissions as the {@code x-required-permissions} OpenAPI extension (#781).
 *
 * <p>Previously copy-pasted into every service's {@code OpenApiConfig}; auto-configured here so every
 * service on the classpath emits the extension identically. It reads
 * {@link org.springframework.security.access.prepost.PreAuthorize} from the controller class and the
 * handler method, extracts every {@code hasAuthority} / {@code hasAnyAuthority} argument, and — for
 * operations guarded only by {@code isAuthenticated()} or with no {@code @PreAuthorize} — emits the
 * {@code AUTHENTICATED} sentinel.
 *
 * <p>Gated behind {@link ConditionalOnClass}({@code OperationCustomizer}) so non-web consumers of this
 * library are unaffected, and {@link ConditionalOnMissingBean} so a service that still defines its own
 * customizer keeps precedence during migration.
 */
@AutoConfiguration
@ConditionalOnClass(OperationCustomizer.class)
public class RequiredPermissionsOpenApiAutoConfiguration {

    private static final Pattern AUTHORITY_PATTERN = Pattern.compile("'([^']+)'");

    @Bean
    @ConditionalOnMissingBean(OperationCustomizer.class)
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
