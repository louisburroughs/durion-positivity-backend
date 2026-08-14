package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/** Idempotency log for consumed events (ADR-0044 §4). */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {}
