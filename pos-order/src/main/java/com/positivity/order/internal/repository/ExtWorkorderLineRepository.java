package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.ExtWorkorderLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtWorkorderLineRepository extends JpaRepository<ExtWorkorderLine, UUID> {

    List<ExtWorkorderLine> findByWorkorderId(UUID workorderId);

    void deleteByWorkorderId(UUID workorderId);
}
