package com.positivity.people.internal.config;

import com.positivity.people.internal.dto.WorkSessionCompletedEvent;
import com.positivity.people.internal.dto.WorkSessionCorrectedEvent;
import com.positivity.people.service.TimekeepingIngestionService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class WorkSessionEventListener {

	private final TimekeepingIngestionService service;

	public WorkSessionEventListener(TimekeepingIngestionService service) {
		this.service = service;
	}

	@EventListener
	public void onWorkSessionCompleted(WorkSessionCompletedEvent event) {
		service.ingestWorkSession(event);
	}

	@EventListener
	public void onWorkSessionCorrected(WorkSessionCorrectedEvent event) {
		service.recordCorrection(event);
	}

}