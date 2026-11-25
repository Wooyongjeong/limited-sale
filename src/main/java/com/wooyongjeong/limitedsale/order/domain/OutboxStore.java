package com.wooyongjeong.limitedsale.order.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface OutboxStore {
    /** Up to {@code limit} unpublished rows in id order, read without locking. */
    List<OutboxMessage> unpublished(int limit);

    /** Marks the rows published at the database's current UTC time, with where the broker put each one. */
    void markPublished(Map<Long, OutboxPosition> positions);

    /** Adds one to the attempt count of each row whose send failed. */
    void recordFailures(Collection<Long> ids);
}
