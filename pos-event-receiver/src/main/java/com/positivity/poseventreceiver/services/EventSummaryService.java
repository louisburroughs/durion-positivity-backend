package com.positivity.poseventreceiver.services;

import com.positivity.poseventreceiver.internal.dto.EventSummaryResponse;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Service API for querying aggregated event summaries by timeframe.
 */
public interface EventSummaryService {

  /**
   * Returns event counts grouped by event type for the last hour.
   */
  @NonNull
  List<EventSummaryResponse> getLastHourSummary();

  /**
   * Returns event counts grouped by event type for the last day (24 hours).
   */
  @NonNull
  List<EventSummaryResponse> getLastDaySummary();

  /**
   * Returns event counts grouped by event type for the last week (7 days).
   */
  @NonNull
  List<EventSummaryResponse> getLastWeekSummary();
}
