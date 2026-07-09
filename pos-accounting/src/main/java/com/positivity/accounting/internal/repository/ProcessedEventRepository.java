package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {}
