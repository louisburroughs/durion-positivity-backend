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
 * Single source of two springdoc {@link OperationCustomizer} beans, auto-configured for every service on the
 * {@code pos-security-common} classpath:
 *
 * <ul>
 *   <li>{@link #requiredPermissionsOperationCustomizer()} publishes each operation's required permissions as the
 *       {@code x-required-permissions} OpenAPI extension (#781). It reads
 *       {@link org.springframework.security.access.prepost.PreAuthorize} from the controller class and the
 *       handler method, extracts every {@code hasAuthority} / {@code hasAnyAuthority} argument, and — for
 *       operations guarded only by {@code isAuthenticated()} or with no {@code @PreAuthorize} — emits the
 *       {@code AUTHENTICATED} sentinel.
 *   <li>{@link #producibleResponsesOperationCustomizer()} prunes the generic {@code 400}/{@code 401}/{@code 403}/
 *       {@code 404}/{@code 409} responses that springdoc merges onto every operation from an unscoped
 *       {@code @ControllerAdvice}, keeping only the codes an operation can actually produce. Originating case:
 *       {@code pos-security-service} (issue #1721); see {@link ProducibleResponsesOperationCustomizer} for the
 *       full rule set.
 * </ul>
 *
 * <p>Previously the required-permissions customizer was copy-pasted into every service's {@code OpenApiConfig};
 * both are auto-configured here so every service on the classpath gets them identically, with no per-module code.
 *
 * <p>Both beans are gated behind {@link ConditionalOnClass}({@code OperationCustomizer}) so non-web consumers of
 * this library are unaffected. Neither is conditional on the absence of other {@code OperationCustomizer} beans —
 * services may register additional customizers alongside them without losing either one; springdoc applies every
 * {@code OperationCustomizer} bean present in the context.
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

    @Bean
    public OperationCustomizer producibleResponsesOperationCustomizer() {
        return new ProducibleResponsesOperationCustomizer();
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
