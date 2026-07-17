package com.positivity.warranty.internal.repository;

import com.positivity.warranty.internal.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {}
