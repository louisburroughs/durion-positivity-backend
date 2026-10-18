package com.positivity.inboundmail.internal.repository;

import com.positivity.inboundmail.internal.entity.InboundMessage;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboundMessageRepository extends JpaRepository<InboundMessage, UUID> {}
