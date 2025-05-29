package com.positivity.poseventreceiver.dao;

import com.positivity.poseventreceiver.model.EmittedEvent;
import com.positivity.poseventreceiver.model.PreregisteredEvent;
import com.positivity.poseventreceiver.repository.EmittedEventRepository;
import com.positivity.poseventreceiver.repository.PreregisteredEventRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class EventDaoImpl implements EventDao {
    private final PreregisteredEventRepository preregRepo;
    private final EmittedEventRepository emittedRepo;

    public EventDaoImpl(PreregisteredEventRepository preregRepo, EmittedEventRepository emittedRepo) {
        this.preregRepo = preregRepo;
        this.emittedRepo = emittedRepo;
    }

    @Override
    public boolean isPreregistered(String id) {
        return preregRepo.existsById(id);
    }

    @Override
    public EmittedEvent saveEmittedEvent(EmittedEvent event) {
        return emittedRepo.save(event);
    }

    @Override
    public Optional<PreregisteredEvent> getPreregisteredEvent(String id) {
        return preregRepo.findById(id);
    }
}
