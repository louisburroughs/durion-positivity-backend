package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.Bay;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BayRepository extends JpaRepository<Bay, UUID> {}
