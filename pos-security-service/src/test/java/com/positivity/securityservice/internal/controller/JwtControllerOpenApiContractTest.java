package com.positivity.securityservice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.securityservice.internal.dto.TokenPairRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JwtController token-pair OpenAPI contract")
class JwtControllerOpenApiContractTest {

  @Test
  @DisplayName("token-pair operation documents privileged internal semantics")
  void tokenPairOperationDocumentsPrivilegedInternalSemantics() throws Exception {
    Method method = jwtControllerMethod();

    Operation operation = method.getAnnotation(Operation.class);
    assertThat(operation).isNotNull();
    assertThat(operation.summary()).containsIgnoringCase("privileged");
    assertThat(operation.description())
        .contains("Privileged internal endpoint")
        .contains("security:token:issue_internal");
  }

  @Test
  @DisplayName("token-pair operation declares 403 for missing issue permission")
  void tokenPairOperationDeclares403ForMissingIssuePermission() throws Exception {
    Method method = jwtControllerMethod();

    Optional<ApiResponse> forbiddenResponse = Arrays.stream(method.getAnnotationsByType(ApiResponse.class))
        .filter(response -> "403".equals(response.responseCode()))
        .findFirst();

    assertThat(forbiddenResponse).isPresent();
    assertThat(forbiddenResponse.orElseThrow().description())
        .contains("missing security:token:issue_internal permission");
  }

  @Test
  @DisplayName("token-pair operation requires bearerAuth scope security:token:issue_internal")
  void tokenPairOperationRequiresExpectedSecurityScope() throws Exception {
    Method method = jwtControllerMethod();

    SecurityRequirement securityRequirement = method.getAnnotation(SecurityRequirement.class);
    assertThat(securityRequirement).isNotNull();
    assertThat(securityRequirement.name()).isEqualTo("bearerAuth");
    assertThat(securityRequirement.scopes()).contains("security:token:issue_internal");
  }

  private Method jwtControllerMethod() throws NoSuchMethodException {
    return JwtController.class.getDeclaredMethod("generateTokenPair", TokenPairRequest.class);
  }
}