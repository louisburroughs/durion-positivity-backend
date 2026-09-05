package com.positivity.securityservice.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;

class ProducibleResponsesOperationCustomizerTest {

    private static final List<String> GENERIC_CODES = List.of("200", "400", "401", "403", "404", "409");

    private final ProducibleResponsesOperationCustomizer customizer = new ProducibleResponsesOperationCustomizer();

    /** No class-level {@code @PreAuthorize} or {@code @ApiResponse}; each method's own annotations are the fixture. */
    static class Fixture {

        @PreAuthorize("permitAll()")
        public void permitAllNoInputs() {
            // Intentionally empty: fixture method, only its annotations matter to the customizer under test.
        }

        @PreAuthorize("permitAll()")
        public void permitAllWithQueryParam() {
            // Intentionally empty: fixture method, only its annotations matter to the customizer under test.
        }

        @PreAuthorize("permitAll()")
        @ApiResponse(responseCode = "401")
        @ApiResponse(responseCode = "403")
        @ApiResponse(responseCode = "409")
        public void permitAllWithBodyAndDeclarations() {
            // Intentionally empty: fixture method, only its annotations matter to the customizer under test.
        }

        @PreAuthorize("hasAuthority('security:role:view')")
        @ApiResponse(responseCode = "404")
        public void hasAuthorityWithPathVariableAndDeclared404() {
            // Intentionally empty: fixture method, only its annotations matter to the customizer under test.
        }

        @PreAuthorize("hasAuthority('security:role:view')")
        public void hasAuthorityNoInputs() {
            // Intentionally empty: fixture method, only its annotations matter to the customizer under test.
        }

        @PreAuthorize("isAuthenticated()")
        public void isAuthenticatedWithQueryParam() {
            // Intentionally empty: fixture method, only its annotations matter to the customizer under test.
        }

        @PreAuthorize("hasAuthority('security:token:revoke') or @userSelfCheck.isSelf(#userId)")
        public void hasAuthorityOrBeanReference() {
            // Intentionally empty: fixture method, only its annotations matter to the customizer under test.
        }

        @io.swagger.v3.oas.annotations.Operation(responses = @ApiResponse(responseCode = "404"))
        @PreAuthorize("hasAuthority('security:role:view')")
        public void operationLevelDeclared404() {
            // Intentionally empty: fixture method, only its annotations matter to the customizer under test.
        }

        public void noPreAuthorize() {
            // Intentionally empty, and deliberately unannotated: the absence of @PreAuthorize is
            // what this fixture contributes.
        }
    }

    /** Class-level {@code @ApiResponse} that a method without its own declaration should still pick up. */
    @ApiResponse(responseCode = "409")
    static class ClassLevelDeclaration {

        @PreAuthorize("hasAuthority('security:role:view')")
        public void methodWithoutOwnDeclaration() {
            // Intentionally empty: the class-level @ApiResponse above is the fixture.
        }
    }

    private static Operation genericOperation(boolean withParameter, boolean withRequestBody) {
        Operation operation = new Operation();
        ApiResponses responses = new ApiResponses();
        for (String code : GENERIC_CODES) {
            responses.addApiResponse(code, new io.swagger.v3.oas.models.responses.ApiResponse());
        }
        responses.setDefault(new io.swagger.v3.oas.models.responses.ApiResponse());
        operation.setResponses(responses);
        if (withParameter) {
            operation.addParametersItem(new Parameter());
        }
        if (withRequestBody) {
            operation.setRequestBody(new RequestBody());
        }
        return operation;
    }

    private Operation customize(Class<?> beanType, String methodName, boolean withParameter, boolean withRequestBody)
            throws Exception {
        Method method = beanType.getMethod(methodName);
        HandlerMethod handlerMethod =
                new HandlerMethod(beanType.getDeclaredConstructor().newInstance(), method);
        return customizer.customize(genericOperation(withParameter, withRequestBody), handlerMethod);
    }

    @Test
    @DisplayName("permitAll() with no parameters or body keeps only 200 and default")
    void permitAllNoInputsKeepsOnlySuccess() throws Exception {
        Operation result = customize(Fixture.class, "permitAllNoInputs", false, false);
        assertThat(result.getResponses().keySet()).containsExactlyInAnyOrder("200", "default");
    }

    @Test
    @DisplayName("permitAll() with a query parameter keeps 200/400, prunes 401/403/404/409")
    void permitAllWithQueryParamKeeps400() throws Exception {
        Operation result = customize(Fixture.class, "permitAllWithQueryParam", true, false);
        assertThat(result.getResponses().keySet()).containsExactlyInAnyOrder("200", "400", "default");
    }

    @Test
    @DisplayName("permitAll() with a body and declared 401/403/409 keeps them, prunes undeclared 404")
    void permitAllWithBodyAndDeclarationsPrunes404() throws Exception {
        Operation result = customize(Fixture.class, "permitAllWithBodyAndDeclarations", false, true);
        assertThat(result.getResponses().keySet())
                .containsExactlyInAnyOrder("200", "400", "401", "403", "409", "default");
    }

    @Test
    @DisplayName("hasAuthority() with a path variable and declared 404 keeps it, prunes undeclared 409")
    void hasAuthorityWithPathVariableKeepsDeclared404() throws Exception {
        Operation result = customize(Fixture.class, "hasAuthorityWithPathVariableAndDeclared404", true, false);
        assertThat(result.getResponses().keySet())
                .containsExactlyInAnyOrder("200", "400", "401", "403", "404", "default");
    }

    @Test
    @DisplayName("hasAuthority() with no inputs keeps 200/401/403 only")
    void hasAuthorityNoInputsKeepsAuthResponses() throws Exception {
        Operation result = customize(Fixture.class, "hasAuthorityNoInputs", false, false);
        assertThat(result.getResponses().keySet()).containsExactlyInAnyOrder("200", "401", "403", "default");
    }

    @Test
    @DisplayName("isAuthenticated() with a query parameter keeps 200/400/401, prunes 403")
    void isAuthenticatedWithQueryParamPrunes403() throws Exception {
        Operation result = customize(Fixture.class, "isAuthenticatedWithQueryParam", true, false);
        assertThat(result.getResponses().keySet()).containsExactlyInAnyOrder("200", "400", "401", "default");
    }

    @Test
    @DisplayName("hasAuthority() combined with a bean-reference expression keeps 403")
    void hasAuthorityOrBeanReferenceKeeps403() throws Exception {
        Operation result = customize(Fixture.class, "hasAuthorityOrBeanReference", false, false);
        assertThat(result.getResponses().keySet()).contains("403");
        assertThat(result.getResponses().keySet()).containsExactlyInAnyOrder("200", "401", "403", "default");
    }

    @Test
    @DisplayName("class-level @ApiResponse(409) is honoured for a method without its own declaration")
    void classLevelDeclarationIsHonoured() throws Exception {
        Operation result = customize(ClassLevelDeclaration.class, "methodWithoutOwnDeclaration", false, false);
        assertThat(result.getResponses().keySet()).containsExactlyInAnyOrder("200", "401", "403", "409", "default");
    }

    @Test
    @DisplayName("@Operation(responses = @ApiResponse(404)) on the method is honoured")
    void operationLevelDeclarationIsHonoured() throws Exception {
        Operation result = customize(Fixture.class, "operationLevelDeclared404", false, false);
        assertThat(result.getResponses().keySet()).containsExactlyInAnyOrder("200", "401", "403", "404", "default");
    }

    @Test
    @DisplayName("no @PreAuthorize at all keeps 401 (authenticated-only default), prunes 403")
    void noPreAuthorizeKeeps401Only() throws Exception {
        Operation result = customize(Fixture.class, "noPreAuthorize", false, false);
        assertThat(result.getResponses().keySet()).containsExactlyInAnyOrder("200", "401", "default");
    }

    @Test
    @DisplayName("null responses are returned unchanged without an exception")
    void nullResponsesReturnedUnchanged() throws Exception {
        Operation operation = new Operation();
        operation.setResponses(null);
        Method method = Fixture.class.getMethod("permitAllNoInputs");
        HandlerMethod handlerMethod = new HandlerMethod(new Fixture(), method);

        Operation result = customizer.customize(operation, handlerMethod);

        assertThat(result).isSameAs(operation);
        assertThat(result.getResponses()).isNull();
    }
}
