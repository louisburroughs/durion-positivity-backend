package com.positivity.marketing.service;

import com.positivity.marketing.internal.dto.AudiencePreviewResponse;
import com.positivity.marketing.internal.dto.CampaignResponse;
import com.positivity.marketing.internal.dto.UpsertCampaignRequest;
import com.positivity.marketing.internal.enums.CampaignStatus;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Public API for campaign definition and lifecycle (Story #1146).
 *
 * <p>Lifecycle transitions are commands rather than a settable status field: each has its own
 * guards, and "schedule" in particular validates the segment, templates, and offer before the
 * campaign becomes eligible to dispatch.
 */
public interface CampaignService {

    @NonNull
    CampaignResponse create(@NonNull UpsertCampaignRequest request);

    /** Editing is refused once anything has been dispatched — see {@code CampaignStatus}. */
    @NonNull
    CampaignResponse update(@NonNull UUID campaignId, @NonNull UpsertCampaignRequest request);

    @NonNull
    CampaignResponse get(@NonNull UUID campaignId);

    @NonNull
    List<CampaignResponse> list(@Nullable CampaignStatus status, @Nullable UUID campaignProgramId);

    /**
     * Validate the campaign is sendable and move it to SCHEDULED. Fails if the segment is
     * missing or of the wrong audience, a channel has no template, or a referenced offer is not
     * ACTIVE.
     */
    @NonNull
    CampaignResponse schedule(@NonNull UUID campaignId);

    @NonNull
    CampaignResponse pause(@NonNull UUID campaignId);

    @NonNull
    CampaignResponse resume(@NonNull UUID campaignId);

    @NonNull
    CampaignResponse cancel(@NonNull UUID campaignId);

    /** Per-channel reach after consent and suppression, plus any blocking validation warnings. */
    @NonNull
    AudiencePreviewResponse previewAudience(@NonNull UUID campaignId);
}
