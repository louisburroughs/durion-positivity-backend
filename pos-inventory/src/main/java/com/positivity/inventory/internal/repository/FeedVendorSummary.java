package com.positivity.inventory.internal.repository;

import java.time.Instant;

/** One feed vendor as a feed table reports it (CAP:550 S36, #2525): the vendor's id, its products and its latest report. */
public interface FeedVendorSummary {

    /** The feed's vendor id: a UUID for a manufacturer, the distributor's own text for a distributor. */
    Object getFeedVendorRef();

    long getProductCount();

    Instant getLastReportedAt();
}
