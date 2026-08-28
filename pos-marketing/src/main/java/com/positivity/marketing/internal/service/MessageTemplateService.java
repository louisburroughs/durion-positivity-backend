package com.positivity.marketing.internal.service;

import com.positivity.marketing.internal.dto.MessageTemplateResponse;
import com.positivity.marketing.internal.dto.UpsertMessageTemplateRequest;
import com.positivity.marketing.internal.enums.AudienceType;
import com.positivity.marketing.internal.enums.CampaignChannel;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Public API for message templates (Story #1147).
 *
 * <p>Tokens are validated against the audience's catalog at save time, so a typo costs a
 * validation error rather than a batch of messages reading "Hi ," in front of customers.
 */
public interface MessageTemplateService {

    @NonNull
    MessageTemplateResponse create(@NonNull UpsertMessageTemplateRequest request);

    @NonNull
    MessageTemplateResponse update(@NonNull UUID templateId, @NonNull UpsertMessageTemplateRequest request);

    void delete(@NonNull UUID templateId);

    @NonNull
    MessageTemplateResponse get(@NonNull UUID templateId);

    @NonNull
    List<MessageTemplateResponse> list(@Nullable CampaignChannel channel, @Nullable AudienceType audienceType);
}
