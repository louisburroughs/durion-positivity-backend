package com.positivity.marketing.internal.repository;

import com.positivity.marketing.internal.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {}
