package com.positivity.poseventreceiver.dao;

import com.positivity.poseventreceiver.model.EmittedEvent;
import com.positivity.poseventreceiver.model.PreregisteredEvent;
import java.util.Optional;

public interface EventDao {
    boolean isPreregistered(String id);
    EmittedEvent saveEmittedEvent(EmittedEvent event);
    Optional<PreregisteredEvent> getPreregisteredEvent(String id);
}
