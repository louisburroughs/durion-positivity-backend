package com.positivity.inboundmail.internal.repository;

import com.positivity.inboundmail.internal.entity.StagedAttachmentContent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StagedAttachmentContentRepository extends JpaRepository<StagedAttachmentContent, UUID> {}
