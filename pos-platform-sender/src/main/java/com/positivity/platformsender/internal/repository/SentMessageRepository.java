package com.positivity.platformsender.internal.repository;

import com.positivity.platformsender.internal.entity.SentMessage;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SentMessageRepository extends JpaRepository<SentMessage, UUID> {

    /** The request already made under this idempotency key, in the bound tenant. */
    @NonNull
    Optional<SentMessage> findByMessageId(@NonNull UUID messageId);
}
