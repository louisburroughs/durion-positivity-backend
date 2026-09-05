package com.positivity.securityservice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

/**
 * Spec-level regression guard for issue #1721: the committed {@code openapi.yaml} must not advertise a {@code 404}
 * or {@code 409} response on an operation whose backing controller method (or its declaring class) never declares
 * one, and endpoints with no possible input or authorization failure must not carry the generic
 * {@code 400}/{@code 401}/{@code 403} that {@code GlobalExceptionHandler}'s unscoped {@code @ControllerAdvice}
 * previously merged onto every operation.
 *
 * <p>This test parses the committed spec file directly (it is not regenerated as part of this change) and cross
 * checks it against the controller source via reflection, so it fails loudly whenever the two drift, independent
 * of whichever agent last touched either side.
 */
@DisplayName("pos-security-service OpenAPI error response contract (issue #1721)")
class OpenApiErrorResponseContractTest {

    private static final String CONTROLLER_PACKAGE = "com.positivity.securityservice.internal.controller";

    @Test
    @DisplayName("GET /v1/auth/validate documents exactly 200 and 400")
    void validateTokenDocumentsOnly200And400() throws IOException {
        Map<String, Object> spec = loadOpenApiSpec();
        Map<String, Object> operation = operationFor(spec, "/v1/auth/validate", "get");
        assertThat(responseCodes(operation)).containsExactlyInAnyOrder("200", "400");
    }

    @Test
    @DisplayName("GET /v1/permissions/catalog-version documents exactly 200")
    void catalogVersionDocumentsOnly200() throws IOException {
        Map<String, Object> spec = loadOpenApiSpec();
        Map<String, Object> operation = operationFor(spec, "/v1/permissions/catalog-version", "get");
        assertThat(responseCodes(operation)).containsExactlyInAnyOrder("200");
    }

    @Test
    @DisplayName("operations documenting 404 match controller methods that declare a 404 @ApiResponse")
    void operationsWith404MatchDeclaredControllerMethods() throws Exception {
        assertOperationsMatchDeclarations("404");
    }

    @Test
    @DisplayName("operations documenting 409 match controller methods that declare a 409 @ApiResponse")
    void operationsWith409MatchDeclaredControllerMethods() throws Exception {
        assertOperationsMatchDeclarations("409");
    }

    private void assertOperationsMatchDeclarations(String code) throws Exception {
        Map<String, Object> spec = loadOpenApiSpec();
        Set<String> operationIdsWithCode = operationIdsWithResponseCode(spec, code);
        Set<String> methodNamesWithDeclaration = controllerMethodNamesDeclaring(code);
        assertThat(operationIdsWithCode)
                .as("operationIds documenting %s vs controller methods declaring %s", code, code)
                .isEqualTo(methodNamesWithDeclaration);
    }

    private static Map<String, Object> loadOpenApiSpec() throws IOException {
        Path path = Path.of("openapi.yaml");
        if (!Files.exists(path)) {
            path = Path.of("../pos-security-service/openapi.yaml");
        }
        try (InputStream in = Files.newInputStream(path)) {
            return new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> paths(Map<String, Object> spec) {
        return (Map<String, Object>) spec.get("paths");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> operationFor(Map<String, Object> spec, String path, String httpMethod) {
        Map<String, Object> pathItem = (Map<String, Object>) paths(spec).get(path);
        assertThat(pathItem).as("path %s present in openapi.yaml", path).isNotNull();
        Map<String, Object> operation = (Map<String, Object>) pathItem.get(httpMethod);
        assertThat(operation)
                .as("%s %s present in openapi.yaml", httpMethod, path)
                .isNotNull();
        return operation;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> responseCodes(Map<String, Object> operation) {
        Map<String, Object> responses = (Map<String, Object>) operation.get("responses");
        return responses == null ? Set.of() : new LinkedHashSet<>(responses.keySet());
    }

    @SuppressWarnings("unchecked")
    private static Set<String> operationIdsWithResponseCode(Map<String, Object> spec, String code) {
        Set<String> operationIds = new LinkedHashSet<>();
        for (Object pathItemObj : paths(spec).values()) {
            Map<String, Object> pathItem = (Map<String, Object>) pathItemObj;
            for (Object operationObj : pathItem.values()) {
                if (!(operationObj instanceof Map)) {
                    continue;
                }
                Map<String, Object> operation = (Map<String, Object>) operationObj;
                if (responseCodes(operation).contains(code)) {
                    Object operationId = operation.get("operationId");
                    if (operationId != null) {
                        operationIds.add(operationId.toString());
                    }
                }
            }
        }
        return operationIds;
    }

    private static Set<String> controllerMethodNamesDeclaring(String code) throws ClassNotFoundException {
        Set<String> methodNames = new LinkedHashSet<>();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (BeanDefinition beanDefinition : scanner.findCandidateComponents(CONTROLLER_PACKAGE)) {
            Class<?> controllerClass = Class.forName(beanDefinition.getBeanClassName());
            boolean classDeclaresCode = declaresResponseCode(controllerClass, code);
            for (Method method : controllerClass.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())
                        || AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class) == null) {
                    continue;
                }
                if (classDeclaresCode || declaresResponseCode(method, code)) {
                    methodNames.add(operationIdOf(method));
                }
            }
        }
        return methodNames;
    }

    /** springdoc's operationId: an explicit {@code @Operation(operationId)} when set, else the method name. */
    private static String operationIdOf(Method method) {
        Operation operation = AnnotatedElementUtils.findMergedAnnotation(method, Operation.class);
        return operation != null && !operation.operationId().isBlank() ? operation.operationId() : method.getName();
    }

    private static boolean declaresResponseCode(AnnotatedElement element, String code) {
        if (AnnotatedElementUtils.findMergedRepeatableAnnotations(element, ApiResponse.class).stream()
                .anyMatch(response -> code.equals(response.responseCode()))) {
            return true;
        }
        Operation operation = AnnotatedElementUtils.findMergedAnnotation(element, Operation.class);
        return operation != null
                && java.util.Arrays.stream(operation.responses())
                        .anyMatch(response -> code.equals(response.responseCode()));
    }
}
