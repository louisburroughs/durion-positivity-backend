package com.positivity.poseventreceiver.internal.service;

import com.positivity.poseventreceiver.dao.EventDao;
import com.positivity.poseventreceiver.internal.dto.EmitEventRequest;
import com.positivity.poseventreceiver.service.EmitEventService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

@Slf4j
@RequiredArgsConstructor
@Service
public class EmitEventServiceImpl implements EmitEventService {
    private static final Pattern EVENT_ID_PATTERN = Pattern.compile("^[A-Z\\d_]+$");
    private static final Pattern API_VERSION_PATTERN = Pattern.compile("^\\d+$");

    private final EventDao eventDao;

    /**
     * Listen to EventEmitted events published by the Events module
     * and store them in the Event Receiver persistence layer.
     *
     * @param request The event request containing id and timestamp
     */
    @Override
    public void onEventEmitted(@NonNull EmitEventRequest request) {
        receiveEvent(request);
    }

    @Override
    public boolean receiveEvent(@NonNull EmitEventRequest request) {
        validateRequest(request);
        log.info("Received EventEmitted event: id={}, apiVersion={}, timestamp={}, elapsedMs={}",
                request.id(), request.apiVersion(), request.timestamp(), request.elapsedMs());
        if (!eventDao.isPreregistered(request.id())) {
            log.warn("Rejected emitted event with unregistered id={}", request.id());
            return false;
        }
        eventDao.saveEmittedEvent(request);
        log.info("Successfully stored emitted event: id={}", request.id());
        return true;
    }

    private void validateRequest(EmitEventRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Event request cannot be null");
        }
        if (request.id() == null || request.id().isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (!EVENT_ID_PATTERN.matcher(request.id()).matches()) {
            throw new IllegalArgumentException("id must contain only uppercase letters, digits, and underscores");
        }
        if (request.apiVersion() == null || request.apiVersion().isBlank()) {
            throw new IllegalArgumentException("apiVersion is required");
        }
        if (!API_VERSION_PATTERN.matcher(request.apiVersion()).matches()) {
            throw new IllegalArgumentException("apiVersion must be numeric");
        }
        if (request.timestamp() <= 0) {
            throw new IllegalArgumentException("timestamp must be positive");
        }
        if (request.elapsedMs() < 0) {
            throw new IllegalArgumentException("elapsedMs must be zero or positive");
        }
        if (request.publishedAt() == null) {
            throw new IllegalArgumentException("publishedAt is required");
        }
    }
}
