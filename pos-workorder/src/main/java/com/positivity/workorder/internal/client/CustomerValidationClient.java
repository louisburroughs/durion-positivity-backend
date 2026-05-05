package com.positivity.workorder.internal.client;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class CustomerValidationClient {

  private final RestClient restClient;

  public CustomerValidationClient(
      @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
      @Value("${pos.gateway.base-url:http://api-gateway}") String gatewayBaseUrl) {
    this.restClient = restClientBuilder.baseUrl(gatewayBaseUrl).build();
  }

  public boolean checkRequirementsMet(@NonNull UUID customerId) {
    try {
      return Boolean.TRUE.equals(
          restClient
              .get()
              .uri("/customer/v1/customers/{id}/requirements-met", customerId)
              .retrieve()
              .body(Boolean.class));
    } catch (Exception e) {
      log.error("Failed to check customer requirements for {}", customerId, e);
      return false;
    }
  }

  public boolean checkApprovalStatus(@NonNull UUID approvalId) {
    try {
      return Boolean.TRUE.equals(
          restClient
              .get()
              .uri("/customer/v1/approvals/{id}/is-approved", approvalId)
              .retrieve()
              .body(Boolean.class));
    } catch (Exception e) {
      log.error("Failed to check customer approval for {}", approvalId, e);
      return false;
    }
  }
}
