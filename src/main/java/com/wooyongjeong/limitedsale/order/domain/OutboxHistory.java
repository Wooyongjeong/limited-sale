package com.wooyongjeong.limitedsale.order.domain;

import java.time.Duration;

/** The published outbox rows kept as history, and their removal once they are old enough (stage 4-2). */
public interface OutboxHistory {
    /**
     * Deletes up to {@code limit} rows, oldest id first, published at least {@code age} ago, except those of an
     * acceptance that is still ACCEPTED (the stall check reads its latest row). Returns how many were deleted.
     */
    int deletePublishedOlderThan(Duration age, int limit);
}
