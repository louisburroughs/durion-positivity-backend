package com.positivity.tenant.internal.repository;

import com.positivity.tenant.internal.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {}
