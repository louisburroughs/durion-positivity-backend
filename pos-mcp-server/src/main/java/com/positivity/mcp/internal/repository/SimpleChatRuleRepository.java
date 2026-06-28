package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.entity.SimpleChatRule;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SimpleChatRuleRepository extends JpaRepository<SimpleChatRule, UUID> {

    List<SimpleChatRule> findByEnabledTrueOrderByPriorityAscPhraseAsc();
}
