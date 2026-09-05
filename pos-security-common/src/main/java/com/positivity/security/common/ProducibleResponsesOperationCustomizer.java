package com.positivity.security.common;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.models.Operation;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;

/**
 * Prunes the generic {@code 400}/{@code 401}/{@code 403}/{@code 404}/{@code 409} responses that springdoc merges
 * onto <em>every</em> operation when a module registers an unscoped {@code @ControllerAdvice} whose
 * {@code @ExceptionHandler} methods carry {@code @ResponseStatus}, keeping only the codes an operation can
 * actually produce. Originating case: {@code pos-security-service}'s {@code GlobalExceptionHandler} (issue
 * #1721). This customizer is platform-wide — any module on {@code pos-security-common} gets it automatically
 * (see {@link RequiredPermissionsOpenApiAutoConfiguration}) so a module that later adds an unscoped advice does
 * not regress its spec.
 *
 * <p>springdoc derives a "generic" response for every {@code @ExceptionHandler} method carrying
 * {@code @ResponseStatus} on any {@code @ControllerAdvice} in the context, and merges it onto each operation's
 * responses regardless of whether that operation's handler can ever throw the corresponding exception. When the
 * advice is unscoped, its {@code 400} (bad-request/validation handlers), {@code 401} (credential/token
 * handlers), {@code 403} (authorization-denied handlers), {@code 404} (not-found handlers), and {@code 409}
 * (conflict handlers) land on every operation in the module, even endpoints that are {@code permitAll()} with no
 * request body (which can never 401/403/404/409) or that take no parameters at all (which can never 400). This
 * misrepresents the actual contract to API consumers and contract tests. See ADR-0017 §1 (Canonical HTTP
 * Response Matrix) for the status semantics this customizer preserves: {@code 400} is request-shape/validation,
 * {@code 401} is missing/invalid authentication, {@code 403} is an authenticated caller lacking permission,
 * {@code 404} is a missing resource, {@code 409} is a target-resource identity/version/lifecycle collision. Per
 * ADR-0056 §1, any {@code 5xx} code is always kept: every endpoint can fault, and some modules document
 * {@code 500}/{@code 503} explicitly.
 *
 * <p>springdoc runs every registered {@link OperationCustomizer} bean <em>after</em> merging the generic
 * responses onto an operation ({@code AbstractOpenApiResource#calculatePath} → {@code responseBuilder.build} →
 * {@code customizeOperation}), so a customizer is the correct place to prune what does not apply — this class
 * never adds a response and never modifies a kept response, it only removes generic responses that an operation
 * cannot produce.
 *
 * <p>For a given operation, a response code is kept if it is:
 *
 * <ul>
 *   <li>declared directly on the handler method or its declaring class via {@link ApiResponse} (repeatable, or
 *       via the {@code @ApiResponses} container) or via {@code @Operation(responses = ...)} on the method;
 *   <li>a {@code 2xx} code, {@code "default"}, or any {@code 5xx} code — springdoc's success responses are never
 *       touched, and any endpoint can fault (ADR-0056 §1);
 *   <li>{@code "400"}, when the operation has at least one parameter or a request body — binding/validation
 *       failures are only possible then;
 *   <li>{@code "401"}, unless the effective {@code @PreAuthorize} expression is exactly {@code permitAll()} — any
 *       other endpoint can be refused by the security filter chain when no/invalid bearer token is presented;
 *   <li>{@code "403"}, when the effective {@code @PreAuthorize} expression contains {@code hasAuthority},
 *       {@code hasAnyAuthority}, {@code hasRole}, {@code hasAnyRole}, or a bean reference ({@code @}) — method
 *       security can then deny an authenticated caller. {@code isAuthenticated()} alone cannot produce
 *       {@code 403} (the filter chain answers {@code 401} first), and neither can {@code permitAll()}.
 * </ul>
 *
 * <p>The effective {@code @PreAuthorize} expression is the method-level annotation
 * ({@link AnnotatedElementUtils#findMergedAnnotation(java.lang.reflect.AnnotatedElement, Class)}), falling back to
 * the class level; if neither is present the operation is treated as authenticated-only, matching a module's
 * default {@code anyRequest().authenticated()} security configuration.
 *
 * <p>Every other response code present on the operation — in practice the generic {@code 404}/{@code 409}, and
 * {@code 400}/{@code 401}/{@code 403} where none of the rules above apply — is removed. If
 * {@code operation.getResponses()} is {@code null} the operation is returned unchanged.
 */
public final class ProducibleResponsesOperationCustomizer implements OperationCustomizer {

    private static final Set<String> AUTHORITY_EXPRESSION_MARKERS =
            Set.of("hasAuthority", "hasAnyAuthority", "hasRole", "hasAnyRole");

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        if (operation.getResponses() == null) {
            return operation;
        }

        Method method = handlerMethod.getMethod();
        Class<?> beanType = handlerMethod.getBeanType();

        Set<String> declaredCodes = declaredResponseCodes(method, beanType);
        boolean hasParametersOrBody = hasParametersOrBody(operation);
        String preAuthorizeExpression = effectivePreAuthorizeExpression(method, beanType);
        boolean permitAll = "permitAll()".equals(preAuthorizeExpression);
        boolean methodSecurityCanDeny = preAuthorizeExpression != null
                && (containsAuthorityExpression(preAuthorizeExpression) || preAuthorizeExpression.contains("@"));

        operation
                .getResponses()
                .keySet()
                .removeIf(code -> !isKept(code, declaredCodes, hasParametersOrBody, permitAll, methodSecurityCanDeny));
        return operation;
    }

    private static boolean isKept(
            String code,
            Set<String> declaredCodes,
            boolean hasParametersOrBody,
            boolean permitAll,
            boolean methodSecurityCanDeny) {
        if (isSuccessOrDefault(code) || declaredCodes.contains(code)) {
            return true;
        }
        return switch (code) {
            case "400" -> hasParametersOrBody;
            case "401" -> !permitAll;
            case "403" -> methodSecurityCanDeny;
            default -> false;
        };
    }

    private static boolean isSuccessOrDefault(String code) {
        return io.swagger.v3.oas.models.responses.ApiResponses.DEFAULT.equals(code)
                || (code.length() == 3 && (code.charAt(0) == '2' || code.charAt(0) == '5'));
    }

    private static boolean hasParametersOrBody(Operation operation) {
        return (operation.getParameters() != null && !operation.getParameters().isEmpty())
                || operation.getRequestBody() != null;
    }

    private static Set<String> declaredResponseCodes(Method method, Class<?> beanType) {
        Set<String> codes = new HashSet<>();
        for (ApiResponse apiResponse :
                AnnotatedElementUtils.findMergedRepeatableAnnotations(method, ApiResponse.class)) {
            codes.add(apiResponse.responseCode());
        }
        for (ApiResponse apiResponse :
                AnnotatedElementUtils.findMergedRepeatableAnnotations(beanType, ApiResponse.class)) {
            codes.add(apiResponse.responseCode());
        }
        io.swagger.v3.oas.annotations.Operation swaggerOperation =
                AnnotatedElementUtils.findMergedAnnotation(method, io.swagger.v3.oas.annotations.Operation.class);
        if (swaggerOperation != null) {
            for (ApiResponse apiResponse : swaggerOperation.responses()) {
                codes.add(apiResponse.responseCode());
            }
        }
        return codes;
    }

    private static String effectivePreAuthorizeExpression(Method method, Class<?> beanType) {
        PreAuthorize methodLevel = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
        if (methodLevel != null) {
            return methodLevel.value();
        }
        PreAuthorize classLevel = AnnotatedElementUtils.findMergedAnnotation(beanType, PreAuthorize.class);
        return classLevel != null ? classLevel.value() : null;
    }

    private static boolean containsAuthorityExpression(String expression) {
        return AUTHORITY_EXPRESSION_MARKERS.stream().anyMatch(expression::contains);
    }
}
