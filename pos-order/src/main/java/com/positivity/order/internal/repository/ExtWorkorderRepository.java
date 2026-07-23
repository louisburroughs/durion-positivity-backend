package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.ExtWorkorder;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtWorkorderRepository extends JpaRepository<ExtWorkorder, UUID> {}
