package com.positivity.marketing.internal.service;

import com.positivity.marketing.internal.dto.CampaignStatsResponse;
import com.positivity.marketing.internal.dto.ProgramStatsResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** Public API for campaign reach and attribution analytics (Story #1152). */
public interface CampaignStatsService {

    @NonNull
    CampaignStatsResponse campaignStats(@NonNull UUID campaignId);

    /** The commercial and individual arms of one initiative, side by side. */
    @NonNull
    ProgramStatsResponse programStats(@NonNull UUID campaignProgramId);
}
