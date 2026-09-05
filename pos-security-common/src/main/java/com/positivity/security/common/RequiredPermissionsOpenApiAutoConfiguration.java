package com.positivity.security.common;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
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
 * library are unaffected. This customizer is always registered — it is no longer conditional on the
 * absence of other {@code OperationCustomizer} beans. Services may register additional customizers
 * alongside it (for example pos-security-service's response-pruning customizer, issue #1721) without
 * losing this extension; springdoc applies every {@code OperationCustomizer} bean present in the context.
 */
@AutoConfiguration
@ConditionalOnClass(OperationCustomizer.class)
public class RequiredPermissionsOpenApiAutoConfiguration {

    // Capture only the quoted arguments of hasAuthority(...) / hasAnyAuthority(...). Role checks
    // (hasRole/hasAnyRole) are deliberately ignored: role names are not permission codes, so emitting
    // them into x-required-permissions would grant a discovered op a non-existent code and make it
    // permanently unselectable (#1102 review). A role-only endpoint therefore emits no extension and
    // stays fail-closed — it is never broadened to AUTHENTICATED.
    private static final Pattern AUTHORITY_CALL_PATTERN =
            Pattern.compile("hasAnyAuthority\\s*\\(([^)]*)\\)|hasAuthority\\s*\\(([^)]*)\\)");
    private static final Pattern QUOTED_ARG_PATTERN = Pattern.compile("'([^']+)'");

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
        Matcher call = AUTHORITY_CALL_PATTERN.matcher(preAuthorize.value());
        while (call.find()) {
            String args = call.group(1) != null ? call.group(1) : call.group(2);
            Matcher arg = QUOTED_ARG_PATTERN.matcher(args);
            while (arg.find()) {
                requiredPermissions.add(arg.group(1));
            }
        }
    }
}
