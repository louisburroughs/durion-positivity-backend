package com.positivity.workorder.internal.config;

import com.positivity.events.EventsApiConstants;
import com.positivity.events.EventTypeInitializerSupport;
import com.positivity.events.EventTypeRegistration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@Slf4j
public class WorkorderPickEventTypeInitializer implements ApplicationRunner {

  private static final String SERVICE_NAME = "pos-workorder";

  private final RestClient restClient;
  private final EventTypeInitializerSupport initializerSupport;
  private final String apiSecret;
  private final boolean enabled;

  public WorkorderPickEventTypeInitializer(
      RestClient.Builder restClientBuilder,
      @Value("${gateway.base-url:http://localhost:8080}") String gatewayBaseUrl,
      @Value("${pos.events.api-secret:}") String apiSecret,
      @Value("${pos-events.registration.enabled:true}") boolean enabled) {
    this.restClient = restClientBuilder
        .baseUrl(gatewayBaseUrl + "/v1/event-receiver/v1/eventTypes/code")
        .build();
    this.initializerSupport = new EventTypeInitializerSupport(SERVICE_NAME);
    this.apiSecret = apiSecret;
    this.enabled = enabled;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!enabled) {
      log.info("[{}] Event type registration is disabled", SERVICE_NAME);
      return;
    }

    try {
      initializerSupport.registerEventTypes(
          WorkorderPickEventTypes.ALL_EVENT_TYPES,
          this::registerViaHttp);
    } catch (Exception e) {
      log.warn("[{}] Failed to register event types at startup: {}. " +
          "Events will still be emitted, but event types may need manual registration.",
          SERVICE_NAME, e.getMessage());
    }
  }

  private void registerViaHttp(EventTypeRegistration registration) {
    var request = restClient.put()
        .uri("/{typeCode}", registration.getTypeCode())
        .contentType(MediaType.APPLICATION_JSON)
        .body(registration);

    if (EventsApiConstants.hasSecret(apiSecret)) {
      request.header(EventsApiConstants.SECRET_HEADER, apiSecret);
    }

    request.retrieve().toBodilessEntity();
  }
}