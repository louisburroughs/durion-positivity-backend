package com.positivity.platformsender.internal.service;

import com.positivity.platformsender.internal.dto.SendMessageRequest;
import com.positivity.platformsender.internal.dto.SendMessageResponse;
import org.jspecify.annotations.NonNull;

/** Delivers one rendered message to a CRM party, at most once per {@code messageId} (FI-2 §1). */
public interface MessageSendService {

    /**
     * Send, or answer a replay of an earlier send of the same {@code messageId}.
     *
     * @throws com.positivity.platformsender.internal.exception.MessageRefusedException when the
     *     message can never be delivered as sent
     * @throws com.positivity.platformsender.internal.exception.SenderUnavailableException when
     *     nothing was delivered and a later attempt may succeed
     */
    @NonNull
    SendResult send(@NonNull SendMessageRequest request);

    /**
     * @param response the acceptance
     * @param replay whether an earlier request with the same {@code messageId} already produced it
     */
    record SendResult(@NonNull SendMessageResponse response, boolean replay) {}
}
