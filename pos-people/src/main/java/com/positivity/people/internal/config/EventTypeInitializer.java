package com.positivity.people.internal.config;

import com.positivity.events.EventsApiConstants;
import com.positivity.events.EventTypeInitializerSupport;
import com.positivity.events.EventTypeRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Initializes all event types for the pos-people module on application startup.
 * Uses the
 * pos-events upsert endpoint for idempotent registration.
 */
@Component
public class EventTypeInitializer implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(EventTypeInitializer.class);

	private final RestClient restClient;

	private final EventTypeInitializerSupport initializerSupport;

	private final String apiSecret;

	public EventTypeInitializer(RestClient.Builder restClientBuilder,
			@Value("${pos.events.base-url:http://localhost:8085}") String eventServiceBaseUrl,
			@Value("${pos.events.api-secret:}") String apiSecret) {
		this.restClient = restClientBuilder.baseUrl(eventServiceBaseUrl + "/v1/eventTypes/code").build();
		this.initializerSupport = new EventTypeInitializerSupport("pos-people");
		this.apiSecret = apiSecret;
	}

	@Override
	public void run(ApplicationArguments args) {
		log.info("Registering {} people event types", EventTypes.all().size());

		initializerSupport.registerEventTypes(EventTypes.all(), this::registerEventType);

		log.info("People event type registration complete");
	}

	private void registerEventType(EventTypeRegistration registration) {
		try {
			var request = restClient.put()
					.uri("/{typeCode}", registration.getTypeCode())
					.contentType(MediaType.APPLICATION_JSON)
					.body(registration);

			if (EventsApiConstants.hasSecret(apiSecret)) {
				request.header(EventsApiConstants.SECRET_HEADER, apiSecret);
			}

			request.retrieve().toBodilessEntity();
			log.debug("Registered event type: {}", registration.getTypeCode());
		} catch (Exception e) {
			log.warn("Failed to register event type {}: {}", registration.getTypeCode(), e.getMessage());
		}
	}

}
