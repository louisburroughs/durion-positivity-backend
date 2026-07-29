package com.positivity.marketing.service;

import com.positivity.marketing.internal.dto.CampaignSendResponse;
import com.positivity.marketing.internal.dto.PagedResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Public API for campaign dispatch (Story #1149).
 *
 * <p>{@link #dispatch} is asynchronous and idempotent: it materializes the audience into
 * {@code campaign_send} rows and returns, and a worker drains them. Re-invoking it never
 * double-sends, because the recipient rows carry a uniqueness constraint on
 * {@code (campaign, recipient, channel)}.
 */
public interface CampaignSendService {

    /** Queue the campaign's audience. Returns the number of recipients newly queued. */
    int dispatch(@NonNull UUID campaignId);

    @NonNull
    PagedResponse<CampaignSendResponse> listSends(@NonNull UUID campaignId, int page, int size);
}
