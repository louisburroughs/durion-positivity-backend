package com.positivity.peoplecontact.internal.repository;

import com.positivity.peoplecontact.internal.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {}
