package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ProcessorSettlementLine;
import com.positivity.accounting.internal.enums.SettlementLineMatchStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for processor settlement lines (story F1c, issue #963). */
public interface ProcessorSettlementLineRepository extends JpaRepository<ProcessorSettlementLine, UUID> {

    List<ProcessorSettlementLine> findBySettlementId(String settlementId);

    List<ProcessorSettlementLine> findBySettlementIdAndMatchStatus(
            String settlementId, SettlementLineMatchStatus matchStatus);

    List<ProcessorSettlementLine> findByMatchStatus(SettlementLineMatchStatus matchStatus);
}
